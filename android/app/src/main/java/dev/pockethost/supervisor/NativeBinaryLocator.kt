package dev.pockethost.supervisor

import android.content.Context
import dev.pockethost.model.ServiceSpec
import java.io.File

object NativeBinaryLocator {
    fun fileFor(context: Context, spec: ServiceSpec): File {
        return File(context.applicationInfo.nativeLibraryDir, "lib${spec.binaryName}.so")
    }

    fun exists(context: Context, spec: ServiceSpec): Boolean {
        return fileFor(context, spec).exists()
    }
}
