package tv.herrin.app

import android.content.Context
import java.io.File

/**
 * Conservative gate for future libmpv-based playback. This class NEVER loads MPVLib,
 * starts a player, or changes the existing Media3 renderer.
 *
 * FongMi/mpv-android JNI must be bytecode-compatible with is.xyz.mpv.MPVLib,
 * and the native runtime must be built with the matching NDK/unified FFmpeg ABI.
 * A positive gate is merely an installed-library presence check, not proof of
 * matching ELF/JNI symbols, device decoder support or real playback.
 */
internal object MpvNativeAvailability {
    val requiredLibraries = listOf(
        "libffmpeg.so", "libmpv.so", "libplayer.so", "libc++_shared.so"
    )

    data class Check(val available: Boolean, val missingLibraries: List<String>)

    /** Fails closed on Android versions that keep native libs unextracted in APK. */
    fun inspect(context: Context): Check {
        val nativeDir = context.applicationInfo.nativeLibraryDir
            ?.takeIf { it.isNotBlank() }?.let(::File)
        val missing = requiredLibraries.filter { name ->
            val file = nativeDir?.resolve(name)
            file == null || !file.isFile || file.length() == 0L
        }
        return Check(missing.isEmpty(), missing)
    }
}
