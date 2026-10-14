package com.example.execution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.transport.proto.CommandExecutionResult
import com.example.transport.proto.ExecutionCommand

/**
 * Binds the local Shizuku/Rish service and routes authenticated control-plane commands to it.
 * Commands are rejected unless the service is bound, Shizuku is alive, permission is granted,
 * the request has an unexpired deadline, and the service's own executable allowlist accepts it.
 */
class ShizukuCommandDispatcher(
    context: Context,
    private val onResult: (CommandExecutionResult) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var engine: ShizukuExecutionEngine? = null
    @Volatile private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engine = (binder as? ShizukuExecutionEngine.LocalBinder)?.service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
        }

        override fun onBindingDied(name: ComponentName?) {
            engine = null
            bound = false
        }

        override fun onNullBinding(name: ComponentName?) {
            engine = null
        }
    }

    @Synchronized
    fun bind() {
        if (bound) return
        bound = appContext.bindService(
            Intent(appContext, ShizukuExecutionEngine::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) throw IllegalStateException("Unable to bind Shizuku execution service")
    }

    fun requestPermission() {
        startForegroundService()
        val service = engine ?: throw IllegalStateException("Shizuku execution service is not bound yet")
        service.requestExecutionPermission()
    }

    fun stopBackgroundExecution() {
        close()
        appContext.stopService(Intent(appContext, ShizukuExecutionEngine::class.java))
    }

    private fun startForegroundService() {
        val intent = Intent(appContext, ShizukuExecutionEngine::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    suspend fun installRishBundle(
        scriptInput: InputStream,
        dexInput: InputStream,
        expectedScriptSha256: String,
        expectedDexSha256: String
    ) = withContext(Dispatchers.IO) {
        val service = engine ?: throw IllegalStateException("Shizuku execution service is not bound yet")
        service.installRishBundle(
            scriptInput,
            dexInput,
            expectedScriptSha256,
            expectedDexSha256
        )
    }

    fun dispatch(command: ExecutionCommand) {
        val now = System.currentTimeMillis()
        if (command.commandId.isBlank() || command.requestedBy.isBlank()) {
            reject(command, 126, "Command ID and authenticated requester are required.")
            return
        }
        if (command.deadlineEpochMs <= now) {
            reject(command, 124, "Command deadline expired before dispatch.", timedOut = true)
            return
        }
        val service = engine
        if (service == null || !service.isBinderConnected()) {
            reject(command, 126, "Shizuku binder is not connected.")
            return
        }
        if (!service.hasExecutionPermission()) {
            reject(command, 126, "Shizuku permission has not been granted by the device owner.")
            return
        }
        runCatching { startForegroundService() }
            .onFailure { reject(command, 126, "Unable to start visible background execution: " + (it.message ?: it.javaClass.simpleName)); return }

        val timeoutSeconds = ((command.deadlineEpochMs - now) / 1000L).coerceIn(1L, MAX_TIMEOUT_SECONDS)
        val request = ShizukuExecutionEngine.ExecutionRequest(
            commandId = command.commandId,
            executable = command.executable,
            arguments = command.argumentsList,
            timeoutSeconds = timeoutSeconds
        )
        service.executeAsync(request) { result ->
            result.fold(
                onSuccess = { value ->
                    onResult(
                        CommandExecutionResult.newBuilder()
                            .setCommandId(value.commandId)
                            .setExitCode(value.exitCode)
                            .setStdout(value.stdout)
                            .setStderr(value.stderr)
                            .setDurationMs(value.durationMs)
                            .setTimedOut(value.timedOut)
                            .build()
                    )
                },
                onFailure = { error ->
                    reject(command, 126, "Shizuku execution failed: " + (error.message ?: error.javaClass.simpleName))
                }
            )
        }
    }

    private fun reject(
        command: ExecutionCommand,
        exitCode: Int,
        reason: String,
        timedOut: Boolean = false
    ) {
        onResult(
            CommandExecutionResult.newBuilder()
                .setCommandId(command.commandId)
                .setExitCode(exitCode)
                .setStderr(reason.take(MAX_ERROR_LENGTH))
                .setDurationMs(0L)
                .setTimedOut(timedOut)
                .build()
        )
    }

    @Synchronized
    override fun close() {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        engine = null
    }

    companion object {
        private const val MAX_TIMEOUT_SECONDS = 900L
        private const val MAX_ERROR_LENGTH = 2048
    }
}
