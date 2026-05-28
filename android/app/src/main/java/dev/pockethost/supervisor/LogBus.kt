package dev.pockethost.supervisor

import android.content.Context
import dev.pockethost.data.PocketHostDatabase
import dev.pockethost.model.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object LogBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    @Volatile private var database: PocketHostDatabase? = null

    fun attach(context: Context) {
        if (database != null) return
        val db = PocketHostDatabase.get(context.applicationContext)
        database = db
        scope.launch {
            _logs.value = db.recentLogs(300)
        }
    }

    fun emit(context: Context, serviceId: String, level: String, message: String) {
        val cleanMessage = SecretRedactor.redact(message, context.applicationContext).take(4_000)
        val line = LogLine(
            timestampEpochMs = System.currentTimeMillis(),
            serviceId = serviceId,
            level = level,
            message = cleanMessage
        )
        _logs.update { current -> (listOf(line) + current).take(300) }
        scope.launch {
            val db = database ?: PocketHostDatabase.get(context.applicationContext).also { database = it }
            db.insertLog(line)
        }
    }
}
