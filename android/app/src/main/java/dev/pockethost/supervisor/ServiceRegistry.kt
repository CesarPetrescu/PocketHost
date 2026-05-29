package dev.pockethost.supervisor

import android.content.Context
import dev.pockethost.model.ServiceSpec

object ServiceRegistry {
    val specs: List<ServiceSpec> = listOf(
        ServiceSpec(
            id = "host",
            displayName = "Host API",
            binaryName = "hostd",
            defaultPort = 8099,
            startByDefault = true,
            description = "Local runtime/health API and web control panel for the Android host.",
            args = { context -> listOf("--addr", listenAddr(context, 8099)) },
            env = { context -> adminTokenEnv(context) + bindEnv(context) }
        ),
        ServiceSpec(
            id = "web",
            displayName = "Web Server",
            binaryName = "webd",
            defaultPort = 8080,
            startByDefault = true,
            description = "Static/local web server implemented in Go.",
            args = { context ->
                listOf("--addr", listenAddr(context, 8080), "--data-dir", AppPaths.webRoot(context).absolutePath)
            },
            env = { context -> bindEnv(context) }
        ),
        ServiceSpec(
            id = "files",
            displayName = "MiniCloud Files",
            binaryName = "filed",
            defaultPort = 8090,
            startByDefault = true,
            description = "Token-capable file API and download server.",
            args = { context ->
                listOf("--addr", listenAddr(context, 8090), "--data-dir", AppPaths.filesRoot(context).absolutePath)
            },
            env = { context -> adminTokenEnv(context) + bindEnv(context) }
        ),
        ServiceSpec(
            id = "proxy",
            displayName = "Local Reverse Proxy",
            binaryName = "proxyd",
            defaultPort = 8088,
            startByDefault = true,
            description = "Host-based reverse proxy for local services.",
            args = { context -> listOf("--addr", listenAddr(context, 8088)) },
            env = { context ->
                mapOf(
                    "POCKETHOST_PROXY_ROUTES" to "web.local=http://127.0.0.1:8080,files.local=http://127.0.0.1:8090,matrix.local=http://127.0.0.1:6167"
                ) + bindEnv(context)
            }
        ),
        ServiceSpec(
            id = "ddns",
            displayName = "DDNS Updater",
            binaryName = "ddnsd",
            defaultPort = 8091,
            startByDefault = false,
            description = "Optional Cloudflare DNS updater. Usually disabled when Tunnel is used.",
            args = { context -> listOf("--addr", listenAddr(context, 8091), "--interval", "15m") },
            env = { context -> adminTokenEnv(context) + bindEnv(context) }
        ),
        ServiceSpec(
            id = "matrix",
            displayName = "Matrix Server",
            binaryName = "matrixd",
            defaultPort = 6167,
            startByDefault = false,
            description = "Rust/Go Matrix homeserver slot. Placeholder binary can be replaced later.",
            args = { context ->
                listOf("--addr", listenAddr(context, 6167), "--data-dir", AppPaths.matrixRoot(context).absolutePath)
            },
            env = { context -> bindEnv(context) },
            healthPath = "/_matrix/client/versions"
        ),
        ServiceSpec(
            id = "cloudflared",
            displayName = "Cloudflare Tunnel",
            binaryName = "cloudflared",
            defaultPort = null,
            startByDefault = false,
            description = "Official cloudflared-compatible tunnel binary slot.",
            args = { context ->
                val config = AppPaths.cloudflaredConfig(context)
                listOf("tunnel", "--config", config.absolutePath, "run")
            },
            preflight = { context ->
                val config = AppPaths.cloudflaredConfig(context)
                if (config.exists()) null else "missing tunnel config: ${config.absolutePath}"
            }
        )
    )

    fun byId(id: String): ServiceSpec? = specs.firstOrNull { it.id == id }

    /** Listen address for a daemon, honoring the loopback/LAN bind toggle. */
    private fun listenAddr(context: Context, port: Int): String =
        "${ServicePreferences.bindHost(context)}:$port"

    private fun adminTokenEnv(context: Context): Map<String, String> {
        val token = ServicePreferences.adminToken(context)
        return if (token.isBlank()) emptyMap() else mapOf("POCKETHOST_TOKEN" to token)
    }

    /**
     * When the operator has opted into LAN exposure, daemons must be told it is
     * intentional; otherwise pocket.ValidateListenAddr refuses any non-loopback
     * bind. Loopback binds need no override.
     */
    private fun bindEnv(context: Context): Map<String, String> =
        if (ServicePreferences.exposeOnLan(context)) mapOf("POCKETHOST_ALLOW_PUBLIC_BIND" to "true") else emptyMap()
}
