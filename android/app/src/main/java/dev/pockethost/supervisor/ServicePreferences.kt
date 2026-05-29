package dev.pockethost.supervisor

import android.content.Context
import java.security.SecureRandom

object ServicePreferences {
    private const val PREFS = "pockethost_prefs"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_ADMIN_TOKEN = "admin_token"
    private const val KEY_EXPOSE_LAN = "expose_lan"

    const val LOOPBACK_HOST = "127.0.0.1"
    const val ALL_INTERFACES_HOST = "0.0.0.0"

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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
