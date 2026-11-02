package tv.herrin.app

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Availability gate only. Matching JNI symbols, ABI linking and actual
 * playback are independent gates. Never use this check to claim codec support.
 */
internal object MpvNativeAvailability {
    val requiredLibraries = listOf(
        "libffmpeg.so", "libmpv.so", "libplayer.so", "libc++_shared.so"
    )

    data class Check(val available: Boolean, val missingLibraries: List<String>)

    /**
     * Modern Android installs may load non-extracted .so entries directly from
     * APK. Check both the native directory and APK entries for the CURRENT
     * process bitness; seeing ARM32 files on a 64-bit process isn't enough.
     */
    fun inspect(context: Context): Check {
        val directory = context.applicationInfo.nativeLibraryDir
            ?.takeIf(String::isNotBlank)?.let(::File)
        val abiCandidates = Build.SUPPORTED_ABIS.orEmpty().filter { abi ->
            if (android.os.Process.is64Bit()) abi in setOf("arm64-v8a", "x86_64")
            else abi in setOf("armeabi-v7a", "armeabi", "x86")
        }
        val archive = context.applicationInfo.sourceDir
            ?.takeIf(String::isNotBlank)?.let(::File)
        val packaged = try {
            if (archive == null || !archive.isFile) emptySet() else ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.size > 0 }
                    .map { it.name }.toSet()
                abiCandidates.flatMap { abi ->
                    requiredLibraries.filter { name -> "lib/$abi/$name" in names }
                        .map { "$abi:$it" }
                }.toSet()
            }
        } catch (_: Exception) { emptySet() }
        val missing = requiredLibraries.filter { lib ->
            val extracted = directory?.resolve(lib)?.let { it.isFile && it.length() > 0 } == true
            !extracted && abiCandidates.none { "$it:$lib" in packaged }
        }
        return Check(missing.isEmpty(), missing)
    }
}
