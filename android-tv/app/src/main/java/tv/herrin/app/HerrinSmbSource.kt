package tv.herrin.app

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.*
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.*
import java.io.Closeable
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal data class SmbLocation(val host:String,val port:Int,val share:String,val root:String) {
    fun child(parent:String,name:String):String {
        require(name.isNotBlank() && name.length<=255 && name !in setOf(".","..") && name.none{it=='/'||it=='\\'||it==':'||it.code<32})
        require(contains(parent))
        return listOf(parent,name).filter{it.isNotEmpty()}.joinToString("\\")
    }
    fun contains(path:String):Boolean = !path.startsWith("\\") && path.none{it=='/'||it==':'||it.code<32} &&
        path.split('\\').none{it in setOf(".","..")} && (root.isEmpty() || path==root || path.startsWith(root+"\\"))
    companion object {
        fun parse(raw:String):SmbLocation {
            require(raw.length in 1..2048)
            val uri=URI(raw.trim())
            require(uri.scheme=="smb" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.fragment==null)
            require(uri.port==-1 || uri.port in 1..65535)
            require(!Regex("%2f|%5c",RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty()))
            val parts=uri.path.orEmpty().split('/').filter{it.isNotEmpty()}
            require(parts.isNotEmpty() && parts.all{part->part.length<=255 && part !in setOf(".","..") && part.none{it=='\\'||it==':'||it.code<32}})
            return SmbLocation(uri.host,if(uri.port==-1)445 else uri.port,parts.first(),parts.drop(1).joinToString("\\"))
        }
    }
}

internal class HerrinSmbAccount(val url:String,val username:String="",val password:String="",val domain:String="",val encryption:Boolean=false) {
    val location=SmbLocation.parse(url)
    val id="smb:"+SourceHealthIndex.digest(url+"\u0000"+username+"\u0000"+domain)
    override fun toString()="SMB account (credentials redacted)"
}

/** One read-only SMB2/3 connection per bounded operation/HTTP stream. DFS cannot forward credentials. */
internal class SmbAccess(private val account:HerrinSmbAccount):Closeable {
    companion object {
        fun configuration(encryption:Boolean=false):SmbConfig = SmbConfig.builder().withSocketFactory(SmbSocketFactory())
            .withDialects(SMB2Dialect.SMB_3_1_1,SMB2Dialect.SMB_3_0_2,SMB2Dialect.SMB_3_0,SMB2Dialect.SMB_2_1,SMB2Dialect.SMB_2_0_2)
            .withMultiProtocolNegotiate(false).withDfsEnabled(false).withSigningEnabled(true).withSigningRequired(true)
            .withEncryptData(encryption).withReadBufferSize(1024*1024)
            .withTimeout(3,TimeUnit.SECONDS).withSoTimeout(3,TimeUnit.SECONDS).build()
    }
    private val client=SMBClient(configuration(account.encryption))
    private var connection:com.hierynomus.smbj.connection.Connection?=null
    private var session:com.hierynomus.smbj.session.Session?=null
    private var disk:DiskShare?=null
    fun share():DiskShare {
        disk?.let{return it}
        try {
            val location=account.location
            val conn=client.connect(location.host,location.port).also{connection=it}
            val auth=AuthenticationContext(account.username,account.password.toCharArray(),account.domain)
            val login=try{conn.authenticate(auth)}finally{auth.password.fill('\u0000')}
            session=login
            return (login.connectShare(location.share) as? DiskShare ?: error("SMB_NOT_A_DISK_SHARE")).also{disk=it}
        }catch(error:Exception){close();throw error}
    }
    fun open(path:String):SmbReadHandle {
        require(account.location.contains(path))
        try {
            val file=share().openFile(path,EnumSet.of(AccessMask.GENERIC_READ),EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            val info=file.fileInformation
            val handle=object:SmbReadHandle {
                override val size=info.standardInformation.endOfFile
                override val version=info.basicInformation.lastWriteTime.toString()
                override fun read(buffer:ByteArray,position:Long,offset:Int,length:Int)=file.read(buffer,position,offset,length)
                private val ended=java.util.concurrent.atomic.AtomicBoolean()
                override fun close(){if(ended.compareAndSet(false,true)){this@SmbAccess.close();runCatching{file.close()}}}
            }
            return if(path.substringAfterLast('.').equals("m2ts",ignoreCase=true))SmbM2tsHandle.adapt(handle) else handle
        }catch(error:Exception){close();throw error}
    }
    override fun close() {
        // Abort the transport first: cancellation/Seek must not wait on a remote CLOSE/LOGOFF.
        runCatching{connection?.close(true)}
        runCatching{disk?.close()};runCatching{session?.close()};runCatching{client.close()}
    }
}

internal interface SmbReadHandle:Closeable {
    val size:Long
    val version:String
    fun read(buffer:ByteArray,position:Long,offset:Int,length:Int):Int
}

internal class HerrinSmbSource(private val account:HerrinSmbAccount,private val streams:HerrinSmbSessions):HerrinPersonalSource {
    override val id=account.id
    override val type=HerrinSourceType.NAS
    private val paths=java.util.concurrent.ConcurrentHashMap<String,String>()
    suspend fun testConnection():Boolean = runInterruptible(Dispatchers.IO) {
        SmbAccess(account).use{it.share().folderExists(account.location.root)}
    }
    override suspend fun search(identity:HerrinFilmIdentity):List<HerrinSourceMovie> = withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()
        try { runInterruptible {
            SmbAccess(account).use { access ->
                val share=access.share();val location=account.location
                val pending=ArrayDeque<Pair<String,Int>>();pending.add(location.root to 0)
                paths.clear()
                val found=mutableListOf<HerrinSourceMovie>();var visited=0;var inspected=0
                while(pending.isNotEmpty() && visited++<32 && inspected<2000 && found.size<80) {
                    job.ensureActive()
                    val (parent,depth)=pending.removeFirst()
                    share.openDirectory(parent,EnumSet.of(AccessMask.FILE_LIST_DIRECTORY,AccessMask.FILE_READ_ATTRIBUTES),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)).use { directory ->
                        val iterator=directory.iterator();var count=0
                        while(count++<250 && inspected++<2000 && iterator.hasNext() && found.size<80) {
                            job.ensureActive()
                            val row=iterator.next();val name=row.fileName
                            if(name in setOf(".",".."))continue
                            val child=runCatching{location.child(parent,name)}.getOrNull()?:continue
                            if(row.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value!=0L)continue
                            if(row.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value!=0L) {
                                if(depth<3)pending.add(child to depth+1)
                                continue
                            }
                            offer(name,child,identity)?.let { found+=it;paths[it.film.id]=child }
                        }
                    }
                }
                found
            }
        } }catch(error:Exception){
            // SMBJ wraps InterruptedException. Preserve coroutine cancellation before
            // classifying provider failures; a cancelled title must not fail its parent UI.
            job.ensureActive()
            throw SourceCandidateFailure(SourceSearchStage.SEARCH,SmbFailure.outcome(error))
        }
    }
    private fun offer(name:String,path:String,identity:HerrinFilmIdentity):HerrinSourceMovie? {
        if(name.substringAfterLast('.').lowercase() !in setOf("mp4","mkv","m2ts","ts","mov","m4v","webm","avi"))return null
        val raw=name.substringBeforeLast('.')
        val episodePattern=Regex("(?i)(?:^|[ ._-])S(\\d{1,2})E(\\d{1,3})(?=$|[ ._-])")
        val match=episodePattern.find(raw)
        val season=match?.groupValues?.get(1)?.toIntOrNull()
        val episode=match?.groupValues?.get(2)?.toIntOrNull()
        val title=raw.replace(episodePattern," ")
        val year=Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(title)?.value.orEmpty()
        val film=Film(id+"-"+SourceHealthIndex.digest(path),ArtworkTitle.searchQuery(title),raw.take(80),
            if(match!=null)"剧集" else "电影",year,"",R.drawable.photofilm_orbit)
        val result=HerrinSourceMovie(id,type,film,if(match!=null)"tv" else identity.mediaType,
            season=season,episode=episode,resolutionClaim=HerrinFilmIdentityMatch.resolutionClaim(raw))
        return result.takeIf{HerrinFilmIdentityMatch.matches(identity,it)}
    }
    override fun playback(offer:HerrinSourceMovie):PlaybackResult {
        val path=paths[offer.film.id]
        if(offer.sourceId!=id || offer.sourceType!=type || path==null || !account.location.contains(path))
            return PlaybackResult.Unavailable("SMB 文件未获此 NAS 会话授权")
        return runCatching { PlaybackResult.Ready(streams.media(account,path)) }
            .getOrElse{PlaybackResult.Unavailable("SMB 会话已结束，请重新选择 NAS 片源")}
    }
}

/** Finite diagnostics only; upstream exception messages may contain a path or account. */
internal object SmbFailure {
    fun outcome(error:Throwable):SourceSearchOutcome {
        val chain=generateSequence(error){it.cause}.take(8).toList()
        if(chain.filterIsInstance<SMBApiException>().any{it.statusCode in setOf(0xC000006DL,0xC000006AL,0xC0000064L,0xC0000072L,0xC0000234L,0xC0000022L)})return SourceSearchOutcome.AUTH_REQUIRED
        if(chain.any{it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException})return SourceSearchOutcome.TIMEOUT
        return SourceSearchOutcome.NETWORK
    }
    fun message(error:Throwable)=when(outcome(error)) {
        SourceSearchOutcome.AUTH_REQUIRED->"SMB 认证或共享目录权限失败，请核对账号、域和权限"
        SourceSearchOutcome.TIMEOUT->"SMB 连接超时，请检查 NAS 网络后重试"
        else->"SMB 共享目录不可用，请检查地址、服务器协议和网络"
    }
}
