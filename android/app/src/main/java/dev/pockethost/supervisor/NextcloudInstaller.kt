package dev.pockethost.supervisor

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object NextcloudInstaller {
    const val VERSION = "33.0.5"
    private const val ASSET_NAME = "nextcloud-server-33.0.5.zip"

    fun installFromAssets(context: Context): File {
        AppPaths.ensure(context)
        val appDir = AppPaths.nextcloudAppDir(context)
        if (File(appDir, "index.php").exists()) return appDir
        val tempDir = File(AppPaths.nextcloudRoot(context), "server.tmp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        context.assets.open(ASSET_NAME).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = safeZipTarget(tempDir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }

        val detectedRoot = findNextcloudRoot(tempDir)
        require(detectedRoot != null) { "Nextcloud payload missing index.php" }
        if (appDir.exists()) appDir.deleteRecursively()
        appDir.parentFile?.mkdirs()
        detectedRoot.copyRecursively(appDir, overwrite = true)
        tempDir.deleteRecursively()
        return appDir
    }

    private fun safeZipTarget(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "zip entry escapes target directory"
        }
        return target
    }

    private fun findNextcloudRoot(root: File): File? {
        if (File(root, "index.php").exists()) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && File(it, "index.php").exists() }
    }
}
