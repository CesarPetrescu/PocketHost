package dev.pockethost.supervisor

import android.content.Context
import java.security.SecureRandom

object ServicePreferences {
    private const val PREFS = "pockethost_prefs"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_ADMIN_TOKEN = "admin_token"

    fun autostartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutostartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

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
