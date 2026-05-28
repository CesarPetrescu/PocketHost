package dev.pockethost.supervisor

import android.content.Context
import java.io.File

object AppPaths {
    fun configDir(context: Context): File = File(context.filesDir, "config")
    fun dataDir(context: Context): File = File(context.filesDir, "data")
    fun logsDir(context: Context): File = File(context.filesDir, "logs")
    fun publicDir(context: Context): File = File(context.filesDir, "public")
    fun webRoot(context: Context): File = File(publicDir(context), "www")
    fun filesRoot(context: Context): File = File(publicDir(context), "files")
    fun matrixRoot(context: Context): File = File(dataDir(context), "matrix")
    fun cloudflaredConfig(context: Context): File = File(configDir(context), "cloudflared.yml")

    fun ensure(context: Context) {
        listOf(
            configDir(context),
            dataDir(context),
            logsDir(context),
            publicDir(context),
            webRoot(context),
            filesRoot(context),
            matrixRoot(context),
        ).forEach { it.mkdirs() }
    }
}
