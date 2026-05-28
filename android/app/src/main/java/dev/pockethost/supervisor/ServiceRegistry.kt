package dev.pockethost.supervisor

import dev.pockethost.model.ServiceSpec

object ServiceRegistry {
    val specs: List<ServiceSpec> = listOf(
        ServiceSpec(
            id = "host",
            displayName = "Host API",
            binaryName = "hostd",
            defaultPort = 8099,
            startByDefault = true,
            description = "Local runtime/health API for the Android host.",
            args = { listOf("--addr", "127.0.0.1:8099") },
            env = { context -> adminTokenEnv(context) }
        ),
        ServiceSpec(
            id = "web",
            displayName = "Web Server",
            binaryName = "webd",
            defaultPort = 8080,
            startByDefault = true,
            description = "Static/local web server implemented in Go.",
            args = { context ->
                listOf("--addr", "127.0.0.1:8080", "--data-dir", AppPaths.webRoot(context).absolutePath)
            }
        ),
        ServiceSpec(
            id = "files",
            displayName = "MiniCloud Files",
            binaryName = "filed",
            defaultPort = 8090,
            startByDefault = true,
            description = "Token-capable file API and download server.",
            args = { context ->
                listOf("--addr", "127.0.0.1:8090", "--data-dir", AppPaths.filesRoot(context).absolutePath)
            },
            env = { context -> adminTokenEnv(context) }
        ),
        ServiceSpec(
            id = "proxy",
            displayName = "Local Reverse Proxy",
            binaryName = "proxyd",
            defaultPort = 8088,
            startByDefault = true,
            description = "Host-based reverse proxy for local services.",
            args = { listOf("--addr", "127.0.0.1:8088") },
            env = {
                mapOf(
                    "POCKETHOST_PROXY_ROUTES" to "web.local=http://127.0.0.1:8080,files.local=http://127.0.0.1:8090,matrix.local=http://127.0.0.1:6167,nextcloud.local=http://127.0.0.1:8081"
                )
            }
        ),
        ServiceSpec(
            id = "ddns",
            displayName = "DDNS Updater",
            binaryName = "ddnsd",
            defaultPort = 8091,
            startByDefault = false,
            description = "Optional Cloudflare DNS updater. Usually disabled when Tunnel is used.",
            args = { listOf("--addr", "127.0.0.1:8091", "--interval", "15m") },
            env = { context -> adminTokenEnv(context) }
        ),
        ServiceSpec(
            id = "matrix",
            displayName = "Matrix Server",
            binaryName = "matrixd",
            defaultPort = 6167,
            startByDefault = false,
            description = "Rust/Go Matrix homeserver slot. Placeholder binary can be replaced later.",
            args = { context ->
                listOf("--addr", "127.0.0.1:6167", "--data-dir", AppPaths.matrixRoot(context).absolutePath)
            },
            healthPath = "/_matrix/client/versions"
        ),
        ServiceSpec(
            id = "nextcloud",
            displayName = "Nextcloud Module",
            binaryName = "nextcloudd",
            defaultPort = 8081,
            startByDefault = false,
            description = "Isolated Linux-userland Nextcloud launcher slot. Not a native rewrite.",
            args = { context ->
                listOf("--addr", "127.0.0.1:8081", "--data-dir", AppPaths.nextcloudRoot(context).absolutePath)
            },
            healthPath = "/status.php"
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

    private fun adminTokenEnv(context: android.content.Context): Map<String, String> {
        val token = ServicePreferences.adminToken(context)
        return if (token.isBlank()) emptyMap() else mapOf("POCKETHOST_TOKEN" to token)
    }
}
