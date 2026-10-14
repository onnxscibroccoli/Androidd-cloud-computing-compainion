package com.example.execution

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.example.MainActivity
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-authorized Shizuku/Rish execution service.
 *
 * Rish is launched as this app's own UID, so it can read its private DEX and use its Shizuku
 * binder. Rish then creates the remote shell through Shizuku. The app never invokes the Rish
 * script as the shell UID because the shell UID cannot read the app-private DEX.
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
    private val activePermission = AtomicBoolean(false)
    private var activeJob: Job? = null
    private val localBinder = LocalBinder()
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateShizukuState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive.set(false)
        activePermission.set(false)
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            activePermission.set(result == PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateShizukuState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Shizuku execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status for user-authorized remote command execution"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("OmniKali Shizuku execution")
            .setContentText("User-authorized remote execution service is active.")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun isBinderConnected(): Boolean = Shizuku.pingBinder() && binderAlive.get()

    fun hasExecutionPermission(): Boolean =
        isBinderConnected() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestExecutionPermission() {
        if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku service is not running")
        if (Shizuku.isPreV11()) throw IllegalStateException("Shizuku v11 or newer is required")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } else {
            activePermission.set(true)
        }
    }

    /**
     * Installs the official Rish shell script and its matching DEX into app-private storage.
     * Expected SHA-256 values must come from the trusted Shizuku export/provisioning channel.
     * On Android 14+, the DEX is made read-only because app_process rejects writable DEX files.
     */
    suspend fun installRishBundle(
        scriptInput: InputStream,
        dexInput: InputStream,
        expectedScriptSha256: String,
        expectedDexSha256: String
    ) = withContext(Dispatchers.IO) {
        require(expectedScriptSha256.matches(Regex("(?i)^[0-9a-f]{64}$"))) { "Invalid expected Rish script digest" }
        require(expectedDexSha256.matches(Regex("(?i)^[0-9a-f]{64}$"))) { "Invalid expected Rish DEX digest" }
        val scriptBytes = scriptInput.use { readLimitedBytes(it, MAX_RISH_SCRIPT_BYTES) }
        val dexBytes = dexInput.use { readLimitedBytes(it, MAX_RISH_DEX_BYTES) }
        require(scriptBytes.isNotEmpty() && scriptBytes.take(2).toByteArray().contentEquals("#!".toByteArray())) {
            "Rish script must start with a shebang"
        }
        require(dexBytes.isNotEmpty()) { "Rish DEX is empty" }
        check(sha256(scriptBytes).equals(expectedScriptSha256, true)) { "Rish script SHA-256 mismatch" }
        check(sha256(dexBytes).equals(expectedDexSha256, true)) { "Rish DEX SHA-256 mismatch" }

        val dir = noBackupFilesDir
        val dex = File(dir, RISH_DEX_NAME)
        val script = File(dir, RISH_SCRIPT_NAME)
        dex.writeBytes(dexBytes)
        check(dex.setReadable(false, false) && dex.setWritable(false, false) &&
            dex.setReadable(true, true)) { "Could not set Rish DEX to owner-readable and read-only" }
        script.writeBytes(scriptBytes)
        check(script.setReadable(false, false) && script.setWritable(false, false) &&
            script.setExecutable(false, false))
        check(script.setReadable(true, true) && script.setWritable(true, true) &&
            script.setExecutable(true, true)) { "Could not set Rish script permissions" }
    }

    suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        require(request.commandId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))) { "Invalid commandId" }
        require(request.timeoutSeconds in 1L..MAX_TIMEOUT_SECONDS) { "timeoutSeconds must be 1..900" }
        require(request.executable in ALLOWED_EXECUTABLES) { "Executable is not allowlisted" }
        require(request.arguments.size <= MAX_ARGUMENTS && request.arguments.all {
            it.length <= MAX_ARGUMENT_LENGTH && !it.contains('\u0000')
        }) {
            "Argument count or size exceeds execution limits"
        }
        validateCommandPolicy(request.executable, request.arguments)
        if (!hasExecutionPermission()) throw SecurityException("Shizuku binder or permission is unavailable")

        val rish = File(noBackupFilesDir, RISH_SCRIPT_NAME)
        val dex = File(noBackupFilesDir, RISH_DEX_NAME)
        check(rish.isFile && rish.canExecute() && dex.isFile && dex.canRead() && !dex.canWrite()) {
            "A verified Rish script and read-only Rish DEX must be installed before execution"
        }

        val command = (listOf(request.executable) + request.arguments).joinToString(" ") { shellQuote(it) }
        val builder = ProcessBuilder(rish.absolutePath, "-c", command)
            .directory(noBackupFilesDir)
        builder.environment()["RISH_PRESERVE_ENV"] = "1"
        builder.environment()["RISH_APPLICATION_ID"] = applicationInfo.packageName

        val started = System.nanoTime()
        val process = builder.start()
        val stdoutJob = scope.async { readBoundedText(process.inputStream, MAX_OUTPUT_CHARS) }
        val stderrJob = scope.async { readBoundedText(process.errorStream, MAX_OUTPUT_CHARS) }
        var completed = false
        try {
            completed = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
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
        activePermission.set(Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }

    private fun validateCommandPolicy(executable: String, arguments: List<String>) {
        when (executable) {
            "pm" -> require(arguments.firstOrNull()?.let { it in READ_ONLY_PM_VERBS } == true) {
                "Only read-only pm commands are allowed"
            }
            "cmd" -> require(
                arguments.size >= 2 &&
                    arguments[0] == "package" &&
                    arguments[1] in READ_ONLY_PACKAGE_COMMANDS
            ) { "Only read-only package queries are allowed through cmd" }
            "settings" -> require(arguments.firstOrNull()?.let { it == "get" || it == "list" } == true) {
                "Only read-only settings commands are allowed"
            }
            "dumpsys" -> require(arguments.size == 1 && arguments[0] in READ_ONLY_DUMPSYS_SERVICES) {
                "Only approved read-only dumpsys services are allowed"
            }
            "cat", "stat", "du" -> require(arguments.isNotEmpty() && arguments.all { isSafeReadPath(it) }) {
                "File access is restricted to approved read-only system paths"
            }
            "ls" -> require(arguments.filterNot { it.startsWith("-") }.all { isSafeReadPath(it) }) {
                "Directory listing is restricted to approved read-only system paths"
            }
            "df" -> require(arguments.filterNot { it.startsWith("-") }.all { isSafeReadPath(it) }) {
                "Filesystem inspection is restricted to approved paths"
            }
            "toybox" -> {
                val applet = arguments.firstOrNull()
                    ?: throw IllegalArgumentException("A toybox applet is required")
                require(applet in SAFE_TOYBOX_APPLETS) { "This toybox applet is not allowed" }
                if (applet in setOf("cat", "stat", "du")) {
                    require(arguments.drop(1).isNotEmpty() && arguments.drop(1).all { isSafeReadPath(it) }) {
                        "Toybox file access is restricted to approved read-only system paths"
                    }
                }
                if (applet == "ls") {
                    require(arguments.drop(1).filterNot { it.startsWith("-") }.all { isSafeReadPath(it) }) {
                        "Toybox directory listing is restricted to approved system paths"
                    }
                }
            }
            "getprop" -> require(arguments.size <= 1) { "getprop accepts at most one property name" }
        }
    }

    private fun isSafeReadPath(path: String): Boolean {
        if (path.isBlank() || path.contains("..") || path.contains('\u0000')) return false
        if (listOf("/mem", "/environ", "/fd/", "/map_files/").any { path.contains(it) }) return false
        return path in SAFE_SYSTEM_PATHS || SAFE_SYSTEM_PATH_PREFIXES.any { path.startsWith(it) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Input exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun readBoundedText(input: InputStream, limit: Int): String = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(2048)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length < limit) {
                    output.append(buffer, 0, minOf(count, limit - output.length))
                }
            }
        }
        output.toString()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun onDestroy() {
        cancelActiveExecution()
        stopForeground(true)
        scope.cancel()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.execution.STOP"
        private const val NOTIFICATION_ID = 7130
        private const val NOTIFICATION_CHANNEL_ID = "shizuku_execution"
        private const val PERMISSION_REQUEST_CODE = 7124
        private const val MAX_TIMEOUT_SECONDS = 900L
        private const val MAX_ARGUMENTS = 64
        private const val MAX_ARGUMENT_LENGTH = 4096
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val MAX_RISH_SCRIPT_BYTES = 128 * 1024
        private const val MAX_RISH_DEX_BYTES = 8 * 1024 * 1024
        private const val TIMEOUT_EXIT_CODE = 124
        private const val RISH_SCRIPT_NAME = "rish"
        private const val RISH_DEX_NAME = "rish_shizuku.dex"
        private val ALLOWED_EXECUTABLES = setOf(
            "id", "uname", "getprop", "pm", "cmd", "settings", "dumpsys",
            "ls", "cat", "stat", "df", "du", "ps", "top", "toybox"
        )
        private val READ_ONLY_PM_VERBS = setOf("list", "path", "dump", "help")
        private val READ_ONLY_PACKAGE_COMMANDS = setOf("list", "path", "resolve-activity", "query-activities")
        private val READ_ONLY_DUMPSYS_SERVICES = setOf(
            "activity", "window", "package", "meminfo", "cpuinfo", "diskstats",
            "procstats", "netstats", "power", "display", "input", "thermalservice"
        )
        private val SAFE_TOYBOX_APPLETS = setOf(
            "ls", "cat", "stat", "df", "du", "ps", "top", "uname", "id", "getprop"
        )
        private val SAFE_SYSTEM_PATHS = setOf("/", "/proc", "/sys", "/system", "/vendor", "/product", "/apex", "/odm")
        private val SAFE_SYSTEM_PATH_PREFIXES = listOf(
            "/proc/", "/sys/", "/system/", "/vendor/", "/product/", "/apex/", "/odm/"
        )
    }
}
