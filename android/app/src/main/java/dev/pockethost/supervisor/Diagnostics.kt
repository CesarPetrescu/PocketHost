package dev.pockethost.supervisor

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Diagnostics {
    private val stampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun createBundle(context: Context): File {
        val appContext = context.applicationContext
        AppPaths.ensure(appContext)
        val stamp = stampFormatter.format(Instant.now())
        val out = File(AppPaths.dataDir(appContext), "diagnostics-$stamp.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putText(
                "manifest.txt",
                buildString {
                    appendLine("PocketHost diagnostics")
                    appendLine("created_utc=${Instant.now()}")
                    appendLine("files_dir=${appContext.filesDir.absolutePath}")
                    appendLine("native_library_dir=${appContext.applicationInfo.nativeLibraryDir}")
                }
            )
            zip.addDirectory(AppPaths.logsDir(appContext), "logs")
            val db = appContext.getDatabasePath("pockethost.db")
            if (db.exists()) {
                zip.addFile(db, "databases/pockethost.db")
            }
        }
        return out
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.addDirectory(dir: File, prefix: String) {
        if (!dir.exists()) return
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = dir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                addFile(file, "$prefix/$rel")
            }
    }

    private fun ZipOutputStream.addFile(file: File, entryName: String) {
        putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(this) }
        closeEntry()
    }
}
