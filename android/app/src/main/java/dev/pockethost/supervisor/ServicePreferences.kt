package dev.pockethost.supervisor

import android.content.Context
import android.util.Base64
import dev.pockethost.R
import java.security.SecureRandom

object ServicePreferences {
    private const val PREFS = "pockethost_prefs"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_ADMIN_TOKEN = "admin_token"
    private const val KEY_EXPOSE_LAN = "expose_lan"
    private const val KEY_CF_TUNNEL = "cloudflare_tunnel"
    private const val KEY_CF_CREDENTIALS_FILE = "cloudflare_credentials_file"
    private const val KEY_CF_HOST_HOSTNAME = "cloudflare_host_hostname"
    private const val KEY_CF_WEB_HOSTNAME = "cloudflare_web_hostname"
    private const val KEY_CF_FILES_HOSTNAME = "cloudflare_files_hostname"
    private const val KEY_CF_PROXY_HOSTNAME = "cloudflare_proxy_hostname"
    private const val KEY_CF_MATRIX_HOSTNAME = "cloudflare_matrix_hostname"
    private const val KEY_CF_NEXTCLOUD_HOSTNAME = "cloudflare_nextcloud_hostname"
    private const val KEY_CF_QUICK_TUNNEL = "cloudflare_quick_tunnel"
    private const val KEY_CF_QUICK_URL = "cloudflare_quick_url"
    private const val KEY_MATRIX_SERVER_NAME = "matrix_server_name"
    private const val KEY_MATRIX_REGISTRATION_ENABLED = "matrix_registration_enabled"
    private const val KEY_MATRIX_REGISTRATION_TOKEN = "matrix_registration_token"
    private const val KEY_MATRIX_FEDERATION_ENABLED = "matrix_federation_enabled"
    private const val KEY_MATRIX_CONFIGURED = "matrix_configured"
    private const val KEY_NEXTCLOUD_ENABLED = "nextcloud_enabled"
    private const val KEY_NEXTCLOUD_INSTALLED_VERSION = "nextcloud_installed_version"
    private const val KEY_NEXTCLOUD_ADMIN_USER = "nextcloud_admin_user"
    private const val KEY_NEXTCLOUD_TRUSTED_DOMAIN = "nextcloud_trusted_domain"
    private const val KEY_NEXTCLOUD_PHP_MEMORY_LIMIT = "nextcloud_php_memory_limit"

    const val LOOPBACK_HOST = "127.0.0.1"
    const val ALL_INTERFACES_HOST = "0.0.0.0"

    data class CloudflareTunnelSettings(
        val tunnel: String = "",
        val credentialsFile: String = "",
        val hostHostname: String = "",
        val webHostname: String = "",
        val filesHostname: String = "",
        val proxyHostname: String = "",
        val matrixHostname: String = "",
        val nextcloudHostname: String = "",
        val quickTunnel: Boolean = true,
        val quickUrl: String = "http://127.0.0.1:8080"
    )

    data class MatrixSettings(
        val serverName: String = "",
        val registrationEnabled: Boolean = false,
        val registrationToken: String = "",
        val federationEnabled: Boolean = false,
        val configured: Boolean = false
    )

    data class NextcloudSettings(
        val enabled: Boolean = false,
        val installedVersion: String = "",
        val adminUser: String = "admin",
        val trustedDomain: String = "localhost",
        val phpMemoryLimit: String = "512M"
    )

    fun autostartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutostartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    /**
     * Whether daemons should bind to all network interfaces (0.0.0.0) instead of
     * loopback. Defaults to false: PocketHost binds 127.0.0.1 unless the operator
     * explicitly opts into LAN/WAN exposure (AGENTS.md architecture rules 1 & 2).
     */
    fun exposeOnLan(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPOSE_LAN, false)

    fun setExposeOnLan(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPOSE_LAN, enabled).apply()
    }

    /** Host that daemons should bind their listen address to. */
    fun bindHost(context: Context): String =
        if (exposeOnLan(context)) ALL_INTERFACES_HOST else LOOPBACK_HOST

    fun adminToken(context: Context): String {
        val existing = adminTokenOrNull(context)
        if (!existing.isNullOrBlank()) return existing
        val generated = generateToken()
        prefs(context).edit().putString(KEY_ADMIN_TOKEN, generated).apply()
        return generated
    }

    fun adminTokenOrNull(context: Context): String? =
        prefs(context).getString(KEY_ADMIN_TOKEN, null)

    fun rotateAdminToken(context: Context): String {
        val generated = generateToken()
        prefs(context).edit().putString(KEY_ADMIN_TOKEN, generated).apply()
        return generated
    }

    fun cloudflareTunnelSettings(context: Context): CloudflareTunnelSettings {
        val p = prefs(context)
        return CloudflareTunnelSettings(
            tunnel = p.getString(KEY_CF_TUNNEL, "").orEmpty(),
            credentialsFile = p.getString(KEY_CF_CREDENTIALS_FILE, "").orEmpty(),
            hostHostname = p.getString(KEY_CF_HOST_HOSTNAME, "").orEmpty(),
            webHostname = p.getString(KEY_CF_WEB_HOSTNAME, "").orEmpty(),
            filesHostname = p.getString(KEY_CF_FILES_HOSTNAME, "").orEmpty(),
            proxyHostname = p.getString(KEY_CF_PROXY_HOSTNAME, "").orEmpty(),
            matrixHostname = p.getString(KEY_CF_MATRIX_HOSTNAME, "").orEmpty(),
            nextcloudHostname = p.getString(KEY_CF_NEXTCLOUD_HOSTNAME, "").orEmpty(),
            quickTunnel = p.getBoolean(KEY_CF_QUICK_TUNNEL, true),
            quickUrl = p.getString(KEY_CF_QUICK_URL, "http://127.0.0.1:8080").orEmpty()
        )
    }

    fun setCloudflareTunnelSettings(context: Context, settings: CloudflareTunnelSettings) {
        prefs(context).edit()
            .putString(KEY_CF_TUNNEL, settings.tunnel.trim())
            .putString(KEY_CF_CREDENTIALS_FILE, settings.credentialsFile.trim())
            .putString(KEY_CF_HOST_HOSTNAME, settings.hostHostname.trim())
            .putString(KEY_CF_WEB_HOSTNAME, settings.webHostname.trim())
            .putString(KEY_CF_FILES_HOSTNAME, settings.filesHostname.trim())
            .putString(KEY_CF_PROXY_HOSTNAME, settings.proxyHostname.trim())
            .putString(KEY_CF_MATRIX_HOSTNAME, settings.matrixHostname.trim())
            .putString(KEY_CF_NEXTCLOUD_HOSTNAME, settings.nextcloudHostname.trim())
            .putBoolean(KEY_CF_QUICK_TUNNEL, settings.quickTunnel)
            .putString(KEY_CF_QUICK_URL, settings.quickUrl.trim())
            .apply()
    }

    fun cloudflaredArgs(context: Context): List<String> {
        val settings = cloudflareTunnelSettings(context)
        return if (settings.quickTunnel) {
            listOf("tunnel", "--url", settings.quickUrl.ifBlank { "http://127.0.0.1:8080" })
        } else {
            listOf("tunnel", "--config", AppPaths.cloudflaredConfig(context).absolutePath, "run")
        }
    }

    fun cloudflaredPreflight(context: Context): String? {
        val settings = cloudflareTunnelSettings(context)
        if (settings.quickTunnel) {
            return if (settings.quickUrl.trim().startsWith("http://127.0.0.1:")) null
            else "quick tunnel URL must target a local PocketHost service, for example http://127.0.0.1:8080"
        }
        val config = AppPaths.cloudflaredConfig(context)
        if (!config.exists()) return "missing tunnel config: ${config.absolutePath}"
        val credentials = cloudflareTunnelSettings(context).credentialsFile.trim()
        if (credentials.isBlank()) return "missing Cloudflare credentials file path"
        val file = java.io.File(credentials)
        if (!file.exists()) return "missing Cloudflare credentials file: ${file.absolutePath}"
        return validateCloudflareCredentials(file)
    }

    fun writeCloudflaredConfig(context: Context): java.io.File {
        AppPaths.ensure(context)
        val settings = cloudflareTunnelSettings(context)
        val routes = listOf(
            settings.hostHostname.trim() to "http://127.0.0.1:8099",
            settings.webHostname.trim() to "http://127.0.0.1:8080",
            settings.filesHostname.trim() to "http://127.0.0.1:8090",
            settings.proxyHostname.trim() to "http://127.0.0.1:8088",
            settings.matrixHostname.trim() to "http://127.0.0.1:6167",
            settings.nextcloudHostname.trim() to "http://127.0.0.1:8092"
        ).filter { (hostname, _) -> hostname.isNotBlank() }

        require(settings.tunnel.trim().isNotBlank()) { "Cloudflare tunnel name or UUID is required." }
        require(settings.credentialsFile.trim().isNotBlank()) { "Cloudflare credentials file path is required." }
        require(routes.isNotEmpty()) { "At least one public hostname route is required." }

        val config = buildString {
            appendLine("tunnel: ${yamlString(settings.tunnel)}")
            appendLine("credentials-file: ${yamlString(settings.credentialsFile)}")
            appendLine("ingress:")
            routes.forEach { (hostname, service) ->
                appendLine("  - hostname: ${yamlString(hostname)}")
                appendLine("    service: ${yamlString(service)}")
            }
            appendLine("  - service: http_status:404")
        }
        return AppPaths.cloudflaredConfig(context).apply { writeText(config) }
    }

    fun matrixSettings(context: Context): MatrixSettings {
        val p = prefs(context)
        return MatrixSettings(
            serverName = p.getString(KEY_MATRIX_SERVER_NAME, "").orEmpty(),
            registrationEnabled = p.getBoolean(KEY_MATRIX_REGISTRATION_ENABLED, false),
            registrationToken = p.getString(KEY_MATRIX_REGISTRATION_TOKEN, "").orEmpty(),
            federationEnabled = p.getBoolean(KEY_MATRIX_FEDERATION_ENABLED, false),
            configured = p.getBoolean(KEY_MATRIX_CONFIGURED, false)
        )
    }

    fun setMatrixSettings(context: Context, settings: MatrixSettings) {
        prefs(context).edit()
            .putString(KEY_MATRIX_SERVER_NAME, settings.serverName.trim())
            .putBoolean(KEY_MATRIX_REGISTRATION_ENABLED, settings.registrationEnabled)
            .putString(KEY_MATRIX_REGISTRATION_TOKEN, settings.registrationToken.trim())
            .putBoolean(KEY_MATRIX_FEDERATION_ENABLED, settings.federationEnabled)
            .putBoolean(KEY_MATRIX_CONFIGURED, settings.configured)
            .apply()
    }

    /**
     * Render the bundled Dendrite (Matrix homeserver) config template into the app-private
     * config dir, substituting the operator's server name, the matrix data dir, and the
     * registration gate. Also ensures a signing key exists. Returns the written YAML file.
     */
    fun writeDendriteConfig(context: Context): java.io.File {
        AppPaths.ensure(context)
        val settings = matrixSettings(context)
        require(settings.serverName.trim().isNotBlank()) { "Matrix server name is required." }
        if (settings.registrationEnabled) {
            require(settings.registrationToken.trim().isNotBlank()) { "Registration token is required when registration is enabled." }
        }
        ensureMatrixSigningKey(context)
        val matrixDir = AppPaths.matrixRoot(context).absolutePath
        val sharedSecret = settings.registrationToken.trim().ifBlank { "disabled" }
        val template = context.resources.openRawResource(R.raw.dendrite_template)
            .bufferedReader().use { it.readText() }
        val config = template
            .replace("__PH_SERVER_NAME__", settings.serverName.trim())
            .replace("__PH_MATRIX_DIR__", matrixDir)
            .replace("__PH_REG_DISABLED__", (!settings.registrationEnabled).toString())
            .replace("__PH_REG_SECRET__", dendriteScalar(sharedSecret))
        return AppPaths.matrixConfig(context).apply { writeText(config) }
    }

    /**
     * A Matrix server signing key is just a base64-encoded 32-byte ed25519 seed wrapped in a
     * "MATRIX PRIVATE KEY" PEM block; Dendrite derives the keypair from the seed at startup.
     * Generate one with SecureRandom (no external crypto provider needed on minSdk 26) and
     * persist it so the homeserver identity stays stable across restarts.
     */
    fun ensureMatrixSigningKey(context: Context): java.io.File {
        val keyFile = AppPaths.matrixSigningKey(context)
        if (keyFile.exists() && keyFile.length() > 0) return keyFile
        keyFile.parentFile?.mkdirs()
        val rnd = SecureRandom()
        val seed = ByteArray(32).also { rnd.nextBytes(it) }
        val b64 = Base64.encodeToString(seed, Base64.NO_WRAP)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val keyId = buildString { repeat(6) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        keyFile.writeText(
            "-----BEGIN MATRIX PRIVATE KEY-----\n" +
                "Key-ID: ed25519:$keyId\n\n" +
                "$b64\n" +
                "-----END MATRIX PRIVATE KEY-----\n"
        )
        return keyFile
    }

    /** Emit a bare YAML scalar when safe, otherwise a double-quoted one. */
    private fun dendriteScalar(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun matrixPreflight(context: Context): String? {
        val settings = matrixSettings(context)
        if (!settings.configured) return "Matrix is not configured. Set a server name first."
        return runCatching {
            writeDendriteConfig(context)
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    fun nextcloudSettings(context: Context): NextcloudSettings {
        val p = prefs(context)
        return NextcloudSettings(
            enabled = p.getBoolean(KEY_NEXTCLOUD_ENABLED, false),
            installedVersion = p.getString(KEY_NEXTCLOUD_INSTALLED_VERSION, "").orEmpty(),
            adminUser = p.getString(KEY_NEXTCLOUD_ADMIN_USER, "admin").orEmpty(),
            trustedDomain = p.getString(KEY_NEXTCLOUD_TRUSTED_DOMAIN, "localhost").orEmpty(),
            phpMemoryLimit = p.getString(KEY_NEXTCLOUD_PHP_MEMORY_LIMIT, "512M").orEmpty()
        )
    }

    fun setNextcloudSettings(context: Context, settings: NextcloudSettings) {
        prefs(context).edit()
            .putBoolean(KEY_NEXTCLOUD_ENABLED, settings.enabled)
            .putString(KEY_NEXTCLOUD_INSTALLED_VERSION, settings.installedVersion.trim())
            .putString(KEY_NEXTCLOUD_ADMIN_USER, settings.adminUser.trim())
            .putString(KEY_NEXTCLOUD_TRUSTED_DOMAIN, settings.trustedDomain.trim())
            .putString(KEY_NEXTCLOUD_PHP_MEMORY_LIMIT, settings.phpMemoryLimit.trim())
            .apply()
    }

    fun nextcloudPreflight(context: Context): String? {
        if (!nextcloudSettings(context).enabled) return "Nextcloud experimental module is disabled. Enable it from the Nextcloud screen first."
        PhpRuntimeInstaller.preflight(context)?.let { return it }
        if (!AppPaths.nextcloudAppDir(context).exists()) return "missing Nextcloud server files: ${AppPaths.nextcloudAppDir(context).absolutePath}"
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun validateCloudflareCredentials(file: java.io.File): String? {
        val text = runCatching { file.readText().take(64 * 1024) }
            .getOrElse { return "could not read Cloudflare credentials file: ${it.message}" }
        val required = listOf("AccountTag", "TunnelID", "TunnelSecret")
        val missing = required.filter { !text.contains("\"$it\"") }
        return if (missing.isEmpty()) null else "Cloudflare credentials JSON missing: ${missing.joinToString(", ")}"
    }

    private fun yamlString(value: String): String =
        "\"" + value.trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ") + "\""

    private fun tomlString(value: String): String = yamlString(value)
}
