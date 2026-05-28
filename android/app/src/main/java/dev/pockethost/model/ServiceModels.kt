package dev.pockethost.model

import android.content.Context

enum class ServiceState(val label: String) {
    Stopped("Stopped"),
    Starting("Starting"),
    Running("Running"),
    Degraded("Degraded"),
    Stopping("Stopping"),
    Failed("Failed"),
    MissingBinary("Missing binary")
}

data class ServiceStatus(
    val id: String,
    val state: ServiceState = ServiceState.Stopped,
    val port: Int? = null,
    val startedAtEpochMs: Long? = null,
    val lastExitCode: Int? = null,
    val lastMessage: String = ""
)

data class ServiceSpec(
    val id: String,
    val displayName: String,
    val binaryName: String,
    val defaultPort: Int?,
    val startByDefault: Boolean,
    val description: String,
    val args: (Context) -> List<String>,
    val env: (Context) -> Map<String, String> = { emptyMap() },
    val healthPath: String = "/health",
    val preflight: (Context) -> String? = { null }
)

data class LogLine(
    val timestampEpochMs: Long,
    val serviceId: String,
    val level: String,
    val message: String
)
