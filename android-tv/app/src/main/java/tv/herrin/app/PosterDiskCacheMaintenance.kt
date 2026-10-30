package tv.herrin.app

import java.io.File

/**
 * Avoid enumerating and sorting the whole image directory for EVERY poster
 * download. The directory gets checked once on first write, then every 16
 * successful writes. Downloaded bytes and bitmaps remain source-isolated.
 */
internal object PosterDiskCacheMaintenance {
    const val MAX_FILES = 150
    const val MAX_BYTES = 100_000_000L
    const val SCAN_EVERY_WRITES = 16

    private val initializedDirectories = mutableSetOf<String>()
    private val writesSinceScan = mutableMapOf<String, Int>()

    internal fun shouldScan(initialized: Boolean, writes: Int): Boolean =
        !initialized || writes >= SCAN_EVERY_WRITES

    fun onImageStored(directory: File, fresh: File) {
        synchronized(this) {
            val key = directory.absolutePath
            val next = (writesSinceScan[key] ?: 0) + 1
            val initialized = key in initializedDirectories
            if (shouldScan(initialized, next)) {
                initializedDirectories.add(key)
                writesSinceScan[key] = 0
                prune(directory, protectedFile = fresh)
            } else {
                writesSinceScan[key] = next
            }
        }
    }

    /**
     * Delete only .img cache entries, oldest first, until BOTH limits hold.
     * Never delete the image just written, even when filesystem timestamps
     * have one-second precision. Other application/cache files are untouched.
     */
    internal fun prune(
        directory: File,
        protectedFile: File? = null,
        maxFiles: Int = MAX_FILES,
        maxBytes: Long = MAX_BYTES
    ) {
        require(maxFiles > 0 && maxBytes > 0)
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension == "img" }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: return
        var remaining = files.size
        var totalBytes = files.sumOf { it.length() }
        for (candidate in files) {
            if (remaining <= maxFiles && totalBytes <= maxBytes) break
            if (candidate.absolutePath == protectedFile?.absolutePath) continue
            val size = candidate.length()
            if (candidate.delete()) {
                remaining--
                totalBytes -= size
            }
        }
    }
}
