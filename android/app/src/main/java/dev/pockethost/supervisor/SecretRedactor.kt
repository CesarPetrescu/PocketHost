package dev.pockethost.supervisor

import android.content.Context

object SecretRedactor {
    private val bearerPattern = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+")
    private val assignmentPattern = Regex("(?i)\\b(token|secret|password|api[_-]?key)=([^\\s]+)")

    fun redact(message: String, context: Context? = null): String {
        var out = message
        if (context != null) {
            val token = runCatching { ServicePreferences.adminTokenOrNull(context) }.getOrNull()
            if (!token.isNullOrBlank()) {
                out = out.replace(token, "[redacted]")
            }
        }
        out = bearerPattern.replace(out) { match -> match.groupValues[1] + "[redacted]" }
        out = assignmentPattern.replace(out) { match -> "${match.groupValues[1]}=[redacted]" }
        return out
    }
}
