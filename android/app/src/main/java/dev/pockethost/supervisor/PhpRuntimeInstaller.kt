package dev.pockethost.supervisor

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipInputStream

object PhpRuntimeInstaller {
    private val supportedAbis = setOf("arm64-v8a", "x86_64")
    private val requiredExtensions = listOf(
        "sqlite3",
        "pdo_sqlite",
        "mbstring",
        "intl",
        "xml",
        "xmlreader",
        "xmlwriter",
        "simplexml",
        "dom",
        "zip",
        "curl",
        "gd",
        "fileinfo",
        "openssl",
        "sodium",
        "ctype",
        "session",
        "zlib",
        "posix"
    )

    fun currentAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis } ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    fun assetName(abi: String = currentAbi()): String = "php-runtime-$abi.zip"

    fun installFromAssets(context: Context, memoryLimit: String): File {
        AppPaths.ensure(context)
        val abi = currentAbi()
        require(abi in supportedAbis) { "unsupported PHP ABI: $abi" }
        val runtimeDir = AppPaths.phpRuntimeDir(context, abi)
        val marker = File(runtimeDir, ".pockethost-php-runtime")
        if (!marker.exists()) {
            val tempDir = File(AppPaths.phpRuntimeRoot(context), "$abi.tmp")
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()
            context.assets.open(assetName(abi)).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = safeZipTarget(tempDir, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            out.setReadable(true, true)
                            out.setWritable(true, true)
                            if (out.name == "php" || out.name.endsWith(".so")) out.setExecutable(true, true)
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (runtimeDir.exists()) runtimeDir.deleteRecursively()
            runtimeDir.parentFile?.mkdirs()
            tempDir.renameTo(runtimeDir)
            marker.writeText("abi=$abi\n")
        }
        writePhpIni(context, abi, memoryLimit)
        return runtimeDir
    }

    fun preflight(context: Context): String? {
        val abi = currentAbi()
        if (abi !in supportedAbis) return "unsupported PHP ABI: $abi"
        val executable = AppPaths.phpExecutable(context)
        if (!executable.exists()) return "missing PHP executable: ${executable.absolutePath}"
        val runtimeDir = AppPaths.phpRuntimeDir(context, abi)
        if (!runtimeDir.exists()) return "missing PHP runtime payload: ${runtimeDir.absolutePath}"
        val ini = AppPaths.phpIni(context, abi)
        if (!ini.exists()) return "missing PHP config: ${ini.absolutePath}"
        val extensionsDir = AppPaths.phpExtensionsDir(context, abi)
        if (!extensionsDir.exists()) return "missing PHP extension directory: ${extensionsDir.absolutePath}"
        val missing = requiredExtensions.filterNot { extensionPresent(extensionsDir, it) }
        if (missing.isNotEmpty()) return "missing PHP extensions: ${missing.joinToString(", ")}"
        return null
    }

    fun runtimeSummary(context: Context): String {
        val abi = currentAbi()
        return buildString {
            appendLine("ABI: $abi")
            appendLine("Executable: ${AppPaths.phpExecutable(context).absolutePath}")
            appendLine("Runtime: ${AppPaths.phpRuntimeDir(context, abi).absolutePath}")
            appendLine("php.ini: ${AppPaths.phpIni(context, abi).absolutePath}")
            appendLine("Extensions: ${AppPaths.phpExtensionsDir(context, abi).absolutePath}")
            appendLine("Preflight: ${preflight(context) ?: "ok"}")
        }.trim()
    }

    fun runModuleSelfCheck(context: Context, memoryLimit: String): String {
        val abi = currentAbi()
        val staticCheck = preflight(context)
        if (staticCheck != null) return "Preflight failed: $staticCheck"
        val runtimeDir = AppPaths.phpRuntimeDir(context, abi)
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to AppPaths.phpLibDir(context, abi).absolutePath,
            "PHPRC" to AppPaths.phpConfigDir(context, abi).absolutePath,
            "PHP_INI_SCAN_DIR" to "",
            "TMPDIR" to File(AppPaths.nextcloudRoot(context), "tmp").absolutePath,
            "POCKETHOST_PHP_EXTENSION_DIR" to AppPaths.phpExtensionsDir(context, abi).absolutePath
        )
        val process = ProcessBuilder(
            AppPaths.phpExecutable(context).absolutePath,
            "-c", AppPaths.phpIni(context, abi).absolutePath,
            "-d", "memory_limit=${memoryLimit.ifBlank { "512M" }}",
            "-d", "extension_dir=${AppPaths.phpExtensionsDir(context, abi).absolutePath}",
            "-m"
        )
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (exit != 0) return "php -m failed ($exit): $output"
        val loaded = output.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .toSet()
        val missing = requiredExtensions.filterNot { it.lowercase() in loaded }
        return if (missing.isEmpty()) {
            "php -m ok\n$output"
        } else {
            "php -m missing: ${missing.joinToString(", ")}\n$output"
        }
    }

    private fun writePhpIni(context: Context, abi: String, memoryLimit: String) {
        val configDir = AppPaths.phpConfigDir(context, abi)
        val extensionsDir = AppPaths.phpExtensionsDir(context, abi)
        val tempDir = File(AppPaths.nextcloudRoot(context), "tmp")
        configDir.mkdirs()
        extensionsDir.mkdirs()
        tempDir.mkdirs()
        AppPaths.phpIni(context, abi).writeText(
            buildString {
                appendLine("memory_limit=${memoryLimit.ifBlank { "512M" }}")
                appendLine("variables_order=EGPCS")
                appendLine("sys_temp_dir=${tempDir.absolutePath}")
                appendLine("upload_tmp_dir=${tempDir.absolutePath}")
                appendLine("extension_dir=${extensionsDir.absolutePath}")
                appendLine("date.timezone=UTC")
                appendLine("opcache.enable_cli=1")
            }
        )
    }

    private fun extensionPresent(dir: File, name: String): Boolean {
        val manifest = File(dir.parentFile, "extensions.txt")
        val manifestHasExtension = manifest.takeIf { it.exists() }
            ?.readLines()
            ?.any { it.trim().equals(name, ignoreCase = true) } == true
        return manifestHasExtension ||
            listOf("$name.so", "lib$name.so").any { File(dir, it).exists() } ||
            name in setOf("ctype", "session", "zlib", "posix")
    }

    private fun safeZipTarget(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "zip entry escapes target directory"
        }
        return target
    }
}
