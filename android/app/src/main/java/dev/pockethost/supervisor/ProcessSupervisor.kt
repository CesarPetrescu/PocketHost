package dev.pockethost.supervisor

import android.content.Context
import dev.pockethost.model.ServiceState
import dev.pockethost.model.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ProcessSupervisor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, Process>()
    private val healthJobs = ConcurrentHashMap<String, Job>()
    private val _statuses = MutableStateFlow(
        ServiceRegistry.specs.associate { spec ->
            spec.id to ServiceStatus(id = spec.id, state = ServiceState.Stopped, port = spec.defaultPort)
        }
    )
    val statuses: StateFlow<Map<String, ServiceStatus>> = _statuses.asStateFlow()

    fun startAll(context: Context, onlyDefault: Boolean = true) {
        AppPaths.ensure(context)
        LogBus.attach(context.applicationContext)
        ServiceRegistry.specs
            .filter { !onlyDefault || it.startByDefault }
            .forEach { spec -> start(context, spec.id) }
    }

    fun stopAll(context: Context) {
        ServiceRegistry.specs.forEach { stop(context, it.id) }
    }

    fun restart(context: Context, id: String) {
        stop(context, id)
        start(context, id)
    }

    fun start(context: Context, id: String) {
        val appContext = context.applicationContext
        val spec = ServiceRegistry.byId(id) ?: return
        val existing = processes[id]
        if (existing != null && existing.isAlive) {
            update(id, ServiceState.Running, "already running")
            return
        }

        val binary = NativeBinaryLocator.fileFor(appContext, spec)
        if (!binary.exists()) {
            update(id, ServiceState.MissingBinary, "missing ${binary.absolutePath}")
            LogBus.emit(appContext, id, "ERROR", "Missing native binary: ${binary.absolutePath}")
            return
        }
        val preflightError = spec.preflight(appContext)
        if (preflightError != null) {
            update(id, ServiceState.Failed, preflightError)
            LogBus.emit(appContext, id, "ERROR", preflightError)
            return
        }

        scope.launch {
            try {
                AppPaths.ensure(appContext)
                update(id, ServiceState.Starting, "starting ${binary.name}")
                val args = spec.args(appContext)
                val pb = ProcessBuilder(listOf(binary.absolutePath) + args)
                    .directory(appContext.filesDir)
                    .redirectErrorStream(true)

                val env = pb.environment()
                env["HOME"] = appContext.filesDir.absolutePath
                env["TMPDIR"] = File(appContext.cacheDir, "tmp").apply { mkdirs() }.absolutePath
                env["POCKETHOST_FILES"] = appContext.filesDir.absolutePath
                env.putAll(spec.env(appContext))

                val process = pb.start()
                processes[id] = process
                update(id, ServiceState.Running, "running ${binary.name}", startedAt = System.currentTimeMillis(), exitCode = null)
                LogBus.emit(appContext, id, "INFO", "Started: ${binary.absolutePath} ${args.joinToString(" ")}")

                streamLogs(appContext, id, process, File(AppPaths.logsDir(appContext), "$id.log"))
                spec.defaultPort?.let { launchHealthLoop(appContext, id, it, spec.healthPath) }

                val exit = process.waitFor()
                healthJobs.remove(id)?.cancel()
                processes.remove(id)
                val state = if (exit == 0) ServiceState.Stopped else ServiceState.Failed
                update(id, state, "process exited with code $exit", exitCode = exit)
                LogBus.emit(appContext, id, if (exit == 0) "INFO" else "ERROR", "Process exited with code $exit")
            } catch (t: Throwable) {
                healthJobs.remove(id)?.cancel()
                processes.remove(id)
                update(id, ServiceState.Failed, t.message ?: t.javaClass.simpleName)
                LogBus.emit(appContext, id, "ERROR", "Failed to start: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stop(context: Context, id: String) {
        val appContext = context.applicationContext
        val process = processes[id]
        if (process == null || !process.isAlive) {
            healthJobs.remove(id)?.cancel()
            processes.remove(id)
            update(id, ServiceState.Stopped, "not running")
            return
        }
        scope.launch {
            update(id, ServiceState.Stopping, "stopping")
            try {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
                healthJobs.remove(id)?.cancel()
                processes.remove(id)
                update(id, ServiceState.Stopped, "stopped")
                LogBus.emit(appContext, id, "INFO", "Stopped")
            } catch (t: Throwable) {
                update(id, ServiceState.Failed, "stop failed: ${t.message}")
                LogBus.emit(appContext, id, "ERROR", "Stop failed: ${t.message}")
            }
        }
    }

    private fun streamLogs(context: Context, id: String, process: Process, logFile: File) {
        scope.launch {
            logFile.parentFile?.mkdirs()
            logFile.appendText("\n--- ${System.currentTimeMillis()} service=$id start ---\n")
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logFile.appendText(line + "\n")
                    LogBus.emit(context, id, "INFO", line)
                    update(id, ServiceState.Running, line.take(200))
                }
            }
        }
    }


    private fun launchHealthLoop(context: Context, id: String, port: Int, path: String) {
        healthJobs.remove(id)?.cancel()
        healthJobs[id] = scope.launch {
            var failures = 0
            delay(1_500)
            while (processes[id]?.isAlive == true) {
                val probe = HealthMonitor.probeLocal(port, path)
                if (probe.ok) {
                    failures = 0
                    update(id, ServiceState.Running, probe.message)
                } else {
                    failures += 1
                    val state = if (failures >= 3) ServiceState.Degraded else ServiceState.Running
                    update(id, state, probe.message)
                    if (failures >= 3) {
                        LogBus.emit(context, id, "WARN", probe.message)
                    }
                }
                delay(5_000)
            }
        }
    }

    private fun update(
        id: String,
        state: ServiceState,
        message: String,
        startedAt: Long? = null,
        exitCode: Int? = null
    ) {
        _statuses.update { current ->
            val old = current[id] ?: ServiceStatus(id = id)
            current + (id to old.copy(
                state = state,
                startedAtEpochMs = startedAt ?: old.startedAtEpochMs,
                lastExitCode = exitCode,
                lastMessage = message
            ))
        }
    }
}
