package tv.herrin.app

import android.content.Context
import android.os.Build
import android.os.Process
import com.github.catvod.crawler.Spider
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Small host-side compatibility rules; no downloaded plugin runs in the UI process. */
internal object SpiderRuntimeCompat {
    private const val MAX_NATIVE_BYTES = 16 * 1024 * 1024L
    private val nativeName = Regex("lib[A-Za-z0-9_.-]+\\.so")

    // Include all initialization inputs. String.hashCode collisions and same-key
    // source edits must never keep the old Spider's cookies or runtime alive.
    fun sessionKey(jar: String, site: TvBoxSite): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fields = listOf(jar, site.key, site.spiderApi.orEmpty(), site.extension) +
            site.headers.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) }
        fields.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun selectAbi(supported: List<String>, is64Bit: Boolean): String? = supported.firstOrNull {
        if (is64Bit) it in setOf("arm64-v8a", "x86_64")
        else it in setOf("armeabi-v7a", "armeabi", "x86")
    }

    fun processAbi(): String? = selectAbi(Build.SUPPORTED_ABIS.toList(), Process.is64Bit())

    fun nativeEntries(names: List<String>, abi: String): List<String> = names.filter {
        it.startsWith("lib/$abi/") && nativeName.matches(it.removePrefix("lib/$abi/"))
    }.distinct().also { require(it.size <= 64) { "Spider native library count invalid" } }

    /** Standard APK/JAR JNI layout in addition to the existing legacy assets layout. */
    fun nativeDirectory(context: Context, jar: File): File? {
        val abi = processAbi() ?: return null
        return ZipFile(jar).use { zip ->
            val entries = nativeEntries(zip.entries().asSequence().map { it.name }.toList(), abi)
            if (entries.isEmpty()) return@use null
            val folder = File(context.codeCacheDir, "herrin-spider-native/${jar.nameWithoutExtension}")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建 Spider native 缓存" }
            var total = 0L
            entries.forEach { name ->
                val entry = zip.getEntry(name)
                require(entry.size in 1..MAX_NATIVE_BYTES) { "Spider native library size invalid" }
                total += entry.size
                require(total <= 32 * 1024 * 1024L) { "Spider native libraries exceed limit" }
                val target = File(folder, name.substringAfterLast('/'))
                val expected = zip.getInputStream(entry).use { input -> hash(input, entry.size) }
                if (target.isFile && target.length() == entry.size &&
                    target.inputStream().use { hash(it, entry.size) }.contentEquals(expected)) {
                    check(target.setReadOnly()) { "无法锁定 Spider native library" }
                } else {
                    val temporary = File(folder, target.name + ".tmp")
                    try {
                        zip.getInputStream(entry).use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var count = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    check(count <= entry.size) { "Spider native library exceeds declared size" }
                                    output.write(buffer, 0, read)
                                }
                                check(count == entry.size) { "Spider native library incomplete" }
                            }
                        }
                        check(temporary.inputStream().use { hash(it, entry.size) }.contentEquals(expected))
                        if (target.exists()) check(target.delete())
                        check(temporary.renameTo(target))
                        check(target.setReadOnly()) { "无法锁定 Spider native library" }
                    } finally {
                        temporary.delete()
                    }
                }
            }
            folder
        }
    }

    private fun hash(input: java.io.InputStream, limit: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            check(count <= limit) { "Spider native library exceeds declared size" }
            digest.update(buffer, 0, read)
        }
        check(count == limit) { "Spider native library incomplete" }
        return digest.digest()
    }

    /** Cleanup every host-owned dependency even when a plugin's destroy throws. */
    fun dispose(spider: Spider?) {
        if (spider == null) return
        cleanup(listOf({ spider.destroy() }, { spider.net?.close() },
            { spider.dialog.close() }, { spider.js.close() }))
    }

    fun cleanup(actions: List<() -> Unit>) {
        actions.forEach { action ->
            try { action() }
            catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
            }
        }
    }

    fun failure(error: Throwable): String {
        val cause = generateSequence(error) {
            (it as? InvocationTargetException)?.targetException ?: it.cause
        }.take(8).last()
        val message = cause.message.orEmpty()
            .replace(Regex("(?i)https?://[^\\s<>]+"), "[地址已隐藏]")
            .replace(Regex("(?i)(cookie|set-cookie|authorization|token|password|secret|api[_-]?key)\\s*[:=]\\s*[^\\r\\n]+"), "$1=[已隐藏]")
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [已隐藏]")
            .take(160)
        return "${cause.javaClass.simpleName}：$message"
    }
}
