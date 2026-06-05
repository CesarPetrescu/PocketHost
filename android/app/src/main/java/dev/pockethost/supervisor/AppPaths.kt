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
    fun matrixConfig(context: Context): File = File(configDir(context), "tuwunel.toml")
    fun cloudflaredConfig(context: Context): File = File(configDir(context), "cloudflared.yml")
    fun cloudflaredDir(context: Context): File = File(configDir(context), "cloudflared")
    fun nextcloudRoot(context: Context): File = File(dataDir(context), "nextcloud")
    fun nextcloudAppDir(context: Context): File = File(nextcloudRoot(context), "server")
    fun nextcloudDataDir(context: Context): File = File(nextcloudRoot(context), "data")
    fun nextcloudConfigDir(context: Context): File = File(configDir(context), "nextcloud")
    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")
    fun phpRuntimeRoot(context: Context): File = File(runtimeDir(context), "php")
    fun phpRuntimeDir(context: Context, abi: String = PhpRuntimeInstaller.currentAbi()): File =
        File(phpRuntimeRoot(context), abi)
    fun phpLibDir(context: Context, abi: String = PhpRuntimeInstaller.currentAbi()): File =
        File(phpRuntimeDir(context, abi), "lib")
    fun phpExtensionsDir(context: Context, abi: String = PhpRuntimeInstaller.currentAbi()): File =
        File(phpRuntimeDir(context, abi), "extensions")
    fun phpConfigDir(context: Context, abi: String = PhpRuntimeInstaller.currentAbi()): File =
        File(phpRuntimeDir(context, abi), "etc")
    fun phpIni(context: Context, abi: String = PhpRuntimeInstaller.currentAbi()): File =
        File(phpConfigDir(context, abi), "php.ini")
    fun phpExecutable(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libphp.so")

    fun ensure(context: Context) {
        listOf(
            configDir(context),
            dataDir(context),
            logsDir(context),
            publicDir(context),
            webRoot(context),
            filesRoot(context),
            matrixRoot(context),
            cloudflaredDir(context),
            nextcloudRoot(context),
            nextcloudDataDir(context),
            nextcloudConfigDir(context),
            runtimeDir(context),
            phpRuntimeRoot(context),
        ).forEach { it.mkdirs() }
    }
}
