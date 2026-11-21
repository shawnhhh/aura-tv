package tv.herrin.app

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.*
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.Closeable
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal data class PersonalRemoteFile(val path: String, val directory: Boolean, val size: Long, val modified: String) {
    val name get() = path.substringAfterLast('/')
}

/** Read-only bounded operations; a scan owns one reusable transport and releases it on every exit. */
internal object PersonalRemoteFiles {
    class Session(private val c: PersonalConnections.Connection, root: String) : Closeable {
        private val base = PersonalStoragePolicy.path(root)
        private val client = if(c.protocol == "smb") null else OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS).build()
        private val dav = client?.let { HerrinWebDavSource(HerrinWebDavAccount(c.name, c.endpoint, c.username, c.password), it) }
        private val smb = if(c.protocol == "smb") {
            require(base.isNotEmpty()) { "SMB_SHARE_REQUIRED" }
            SmbAccess(HerrinSmbAccount(c.address(base), c.username, c.password, c.domain, c.encryption))
        } else null

        suspend fun list(path: String): List<PersonalRemoteFile> = withContext(Dispatchers.IO) {
            val clean = PersonalStoragePolicy.path(path)
            require(PersonalStoragePolicy.contains(base, clean))
            val job = currentCoroutineContext()
            try {
                val source = dav
                if(source != null) {
                    source.list(c.address(clean).trimEnd('/') + "/").mapNotNull { e ->
                        val absolute = PersonalStoragePolicy.path(URI(e.address).path)
                        if (!PersonalStoragePolicy.contains(c.basePath, absolute)) return@mapNotNull null
                        val relative = if (c.basePath.isEmpty()) absolute
                            else absolute.removePrefix(c.basePath).trimStart('/')
                        PersonalRemoteFile(relative, e.isCollection, e.size, e.modified)
                    }.filter { it.path != clean }
                } else runInterruptible {
                    val location = SmbLocation.parse(c.address(clean))
                    require(clean.substringBefore('/') == base.substringBefore('/'))
                    requireNotNull(smb).share().openDirectory(location.root,
                        EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY), SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)).use { dir ->
                        val rows = ArrayList<PersonalRemoteFile>()
                        val iterator = dir.iterator()
                        while(iterator.hasNext()) {
                            job.ensureActive()
                            val row = iterator.next()
                            if(row.fileName in setOf(".", "..")) continue
                            check(rows.size < 10000) { "SMB_DIRECTORY_LIMIT" }
                            if(row.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) continue
                            val child = location.child(location.root, row.fileName)
                            rows += PersonalRemoteFile(location.share + "/" + child.replace('\\', '/'),
                                row.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                                row.endOfFile, row.lastWriteTime.toString())
                        }
                        rows
                    }
                }
            } catch(error: Exception) { job.ensureActive(); throw error }
        }
        override fun close() {
            smb?.close()
            client?.dispatcher?.cancelAll()
            client?.connectionPool?.evictAll()
            client?.dispatcher?.executorService?.shutdown()
        }
    }
    suspend fun list(c: PersonalConnections.Connection, path: String): List<PersonalRemoteFile> =
        Session(c, path).use { it.list(path) }
    fun message(error: Exception): String = when (error) {
        is SourceHttpFailure -> when (error.code) {
            401, 403 -> "WebDAV 认证或目录权限失败"
            404 -> "WebDAV 目录不存在"
            405, 501 -> "服务器不支持 WebDAV PROPFIND"
            else -> "WebDAV HTTP 请求失败"
        }
        is javax.net.ssl.SSLException -> "HTTPS 证书校验失败"
        is java.net.SocketTimeoutException -> "服务器连接超时"
        is java.net.UnknownHostException -> "服务器域名无法解析"
        is IllegalArgumentException -> if(error.message == "SMB_SHARE_REQUIRED") "请输入 SMB 共享名称后浏览；无需再次输入服务器账号" else "路径或连接字段无效"
        else -> if(generateSequence<Throwable>(error) { it.cause }.take(8).any { it is com.hierynomus.mssmb2.SMBApiException }) SmbFailure.message(error)
            else "连接或目录读取失败，请检查服务器、共享名称及权限；超大目录须拆分导入"
    }
}
