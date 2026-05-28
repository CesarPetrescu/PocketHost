package dev.pockethost.supervisor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object HealthMonitor {
    data class ProbeResult(
        val ok: Boolean,
        val message: String
    )

    suspend fun probeLocal(port: Int, path: String = "/health"): ProbeResult = withContext(Dispatchers.IO) {
        val url = URL("http://127.0.0.1:$port$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 750
            readTimeout = 750
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(200) }.orEmpty()
            if (code in 200..299) {
                ProbeResult(ok = true, message = "health ok $code")
            } else {
                ProbeResult(ok = false, message = "health returned $code ${body.take(80)}".trim())
            }
        } catch (t: Throwable) {
            ProbeResult(ok = false, message = "health failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}
