package dev.pockethost.supervisor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom

object PublicCollabSuite {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                AppPaths.ensure(appContext)
                LogBus.attach(appContext)
                LogBus.emit(appContext, "suite", "INFO", "Preparing public Matrix + Nextcloud quick tunnel suite")

                ServicePreferences.adminToken(appContext)
                configureMatrix(appContext)
                configureNextcloud(appContext)

                LogBus.emit(appContext, "suite", "INFO", "Installing packaged PHP runtime")
                PhpRuntimeInstaller.installFromAssets(appContext, ServicePreferences.nextcloudSettings(appContext).phpMemoryLimit)
                LogBus.emit(appContext, "suite", "INFO", "Installing packaged Nextcloud payload")
                NextcloudInstaller.installFromAssets(appContext)

                ProcessSupervisor.start(appContext, "matrix")
                ProcessSupervisor.start(appContext, "nextcloud")
                ProcessSupervisor.start(appContext, "cloudflared-matrix")
                ProcessSupervisor.start(appContext, "cloudflared-nextcloud")
                LogBus.emit(appContext, "suite", "INFO", "Started Matrix, Nextcloud, and two Cloudflare Quick Tunnels")
            }.onFailure {
                LogBus.emit(appContext, "suite", "ERROR", "Suite start failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun configureMatrix(context: Context) {
        val existing = ServicePreferences.matrixSettings(context)
        val settings = ServicePreferences.MatrixSettings(
            serverName = existing.serverName.ifBlank { "localhost" },
            registrationEnabled = true,
            registrationToken = existing.registrationToken.ifBlank { randomToken() },
            federationEnabled = false,
            configured = true
        )
        ServicePreferences.setMatrixSettings(context, settings)
        ServicePreferences.writeDendriteConfig(context)
        LogBus.emit(context, "matrix", "INFO", "Matrix config ready for ${settings.serverName}")
    }

    private fun configureNextcloud(context: Context) {
        val existing = ServicePreferences.nextcloudSettings(context)
        val settings = ServicePreferences.NextcloudSettings(
            enabled = true,
            installedVersion = existing.installedVersion,
            adminUser = existing.adminUser.ifBlank { "admin" },
            adminPassword = existing.adminPassword.ifBlank { randomPassword() },
            trustedDomain = existing.trustedDomain.ifBlank { "localhost" },
            phpMemoryLimit = existing.phpMemoryLimit.ifBlank { "512M" }
        )
        ServicePreferences.setNextcloudSettings(context, settings)
        LogBus.emit(context, "nextcloud", "INFO", "Nextcloud settings ready")
    }

    private fun randomToken(): String = randomHex(18)

    private fun randomPassword(): String = "PocketHost-" + randomHex(12)

    private fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return raw.joinToString(separator = "") { "%02x".format(it) }
    }
}
