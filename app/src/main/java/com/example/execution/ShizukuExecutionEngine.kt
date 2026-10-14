package com.example.execution

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-authorized Shizuku/Rish execution service. This service never auto-grants elevated access.
 * The Rish launcher must be installed into this app's private no-backup directory by a trusted
 * provisioning path before commands can run. Commands are argument arrays, not arbitrary shell
 * strings, and the executable is allowlisted.
 */
class ShizukuExecutionEngine : Service() {
    data class ExecutionRequest(
        val commandId: String,
        val executable: String,
        val arguments: List<String> = emptyList(),
        val timeoutSeconds: Long = 60
    )

    data class ExecutionResult(
        val commandId: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean
    )

    inner class LocalBinder : Binder() {
        fun service(): ShizukuExecutionEngine = this@ShizukuExecutionEngine
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binderAlive = AtomicBoolean(false)
    private val granted = AtomicBoolean(false)
    private var activeJob: Job? = null
    private val localBinder = LocalBinder()
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateShizukuState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive.set(false)
        granted.set(false)
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            granted.set(result == PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateShizukuState()
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    fun isBinderConnected(): Boolean = Shizuku.pingBinder() && binderAlive.get()

    fun hasExecutionPermission(): Boolean =
        isBinderConnected() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestExecutionPermission() {
        if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku service is not running")
        if (Shizuku.isPreV11()) throw IllegalStateException("Shizuku v11 or newer is required")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } else {
            granted.set(true)
        }
    }

    /** Install a Rish launcher supplied through a trusted local provisioning workflow. */
    suspend fun installRishLauncher(input: InputStream) = withContext(Dispatchers.IO) {
        val destination = rishFile()
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_RISH_BYTES) { "Rish launcher exceeds size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.isNotEmpty() && bytes.take(2).toByteArray().contentEquals("#!".toByteArray())) {
            "Rish launcher must be a non-empty script with a shebang"
        }
        destination.writeBytes(bytes)
        check(destination.setReadable(false, false) && destination.setWritable(false, false) &&
            destination.setExecutable(false, false))
        check(destination.setReadable(true, true) && destination.setWritable(true, true) &&
            destination.setExecutable(true, true)) { "Unable to set private Rish launcher permissions" }
    }

    suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        require(request.commandId.isNotBlank()) { "commandId is required" }
        require(request.timeoutSeconds in 1L..MAX_TIMEOUT_SECONDS) { "timeoutSeconds must be 1..900" }
        require(request.executable in ALLOWED_EXECUTABLES) { "Executable is not allowlisted" }
        require(request.arguments.size <= MAX_ARGUMENTS && request.arguments.all { it.length <= MAX_ARGUMENT_LENGTH }) {
            "Argument count or size exceeds execution limits"
        }
        if (!hasExecutionPermission()) throw SecurityException("Shizuku binder or permission is unavailable")
        val rish = rishFile()
        check(rish.isFile && rish.canExecute()) {
            "Rish launcher is not installed. Provision a trusted Rish launcher before execution."
        }

        val started = System.nanoTime()
        val command = (listOf(request.executable) + request.arguments).joinToString(" ") { shellQuote(it) }
        val process = Shizuku.newProcess(
            arrayOf(rish.absolutePath, "-c", command),
            arrayOf("RISH_PRESERVE_ENV=1"),
            noBackupFilesDir.absolutePath
        )
        val stdoutJob = scope.async { readBounded(process.inputStream) }
        val stderrJob = scope.async { readBounded(process.errorStream) }
        var completed = false
        try {
            completed = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            val stdout = stdoutJob.await()
            val stderr = stderrJob.await()
            ExecutionResult(
                commandId = request.commandId,
                exitCode = if (completed) process.exitValue() else TIMEOUT_EXIT_CODE,
                stdout = stdout,
                stderr = if (completed) stderr else (stderr + "\nExecution timed out").take(MAX_OUTPUT_CHARS),
                durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                timedOut = !completed
            )
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            stdoutJob.cancel()
            stderrJob.cancel()
            throw cancelled
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    fun executeAsync(request: ExecutionRequest, callback: (Result<ExecutionResult>) -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val result = runCatching { execute(request) }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun cancelActiveExecution() {
        activeJob?.cancel(CancellationException("Cancelled by caller"))
        activeJob = null
    }

    private fun updateShizukuState() {
        binderAlive.set(Shizuku.pingBinder())
        granted.set(Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }

    private fun rishFile() = File(noBackupFilesDir, "rish")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun readBounded(input: InputStream): String = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(2048)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length < MAX_OUTPUT_CHARS) {
                    output.append(buffer, 0, minOf(count, MAX_OUTPUT_CHARS - output.length))
                }
            }
        }
        output.toString()
    }

    override fun onDestroy() {
        cancelActiveExecution()
        scope.cancel()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7124
        private const val MAX_TIMEOUT_SECONDS = 900L
        private const val MAX_ARGUMENTS = 64
        private const val MAX_ARGUMENT_LENGTH = 4096
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val MAX_RISH_BYTES = 4 * 1024 * 1024
        private const val TIMEOUT_EXIT_CODE = 124
        private val ALLOWED_EXECUTABLES = setOf(
            "id", "uname", "getprop", "pm", "cmd", "settings", "dumpsys",
            "ls", "cat", "stat", "df", "du", "ps", "top", "toybox"
        )
    }
}
