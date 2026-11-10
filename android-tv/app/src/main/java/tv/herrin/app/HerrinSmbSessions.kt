package tv.herrin.app

import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal data class SmbByteRange(val start:Long,val end:Long) {
    val length get()=end-start+1
    companion object {
        fun parse(header:String?,size:Long):SmbByteRange {
            require(size>0)
            if(header==null)return SmbByteRange(0,size-1)
            val match=Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header)?:throw IllegalArgumentException()
            val left=match.groupValues[1];val right=match.groupValues[2]
            if(left.isEmpty()) {
                val suffix=right.toLongOrNull()?:throw IllegalArgumentException()
                require(suffix>0)
                return SmbByteRange((size-suffix).coerceAtLeast(0),size-1)
            }
            val start=left.toLongOrNull()?:throw IllegalArgumentException()
            val end=if(right.isEmpty())size-1 else (right.toLongOrNull()?:throw IllegalArgumentException()).coerceAtMost(size-1)
            require(start<size && end>=start)
            return SmbByteRange(start,end)
        }
    }
}

/** Loopback capabilities carry no NAS credentials. Each HTTP request has its own offset-aware SMB handle. */
internal class HerrinSmbSessions(private val openFile:(HerrinSmbAccount,String)->SmbReadHandle={a,p->SmbAccess(a).open(p)}):Closeable {
    private val lock=Any()
    private var server:SmbServer?=null
    private var disposed=false
    fun media(account:HerrinSmbAccount,path:String):PlaybackMedia = synchronized(lock) {
        check(!disposed);require(account.location.contains(path))
        val current=server?.takeIf{it.isAlive} ?: SmbServer(openFile).let { created ->
            try{created.start(5000,true);server=created;created}catch(error:Exception){created.end();throw error}
        }
        PlaybackMedia(current.register(account,path),allowHttpRedirects=false,
            declaredMime=when(path.substringAfterLast('.').lowercase()){
                "mp4","m4v"->"video/mp4";"mkv"->"video/x-matroska";"ts","m2ts"->"video/mp2t";else->null
            })
    }
    override fun close()=synchronized(lock){disposed=true;server?.end();server=null}
    override fun toString()="SMB playback sessions (capabilities redacted)"
}

private class SmbServer(private val openFile:(HerrinSmbAccount,String)->SmbReadHandle):NanoHTTPD("127.0.0.1",0) {
    private data class Resource(val account:HerrinSmbAccount,val path:String,val users:java.util.concurrent.atomic.AtomicInteger=java.util.concurrent.atomic.AtomicInteger())
    private val token=random()
    private val resources=java.util.LinkedHashMap<String,Resource>(16,0.75f,true)
    private val keys=mutableMapOf<String,String>()
    private val handles=ConcurrentHashMap.newKeySet<SmbReadHandle>()
    private val closed=AtomicBoolean(false)
    @Volatile private var lastUsed=System.nanoTime()
    private val expiry=java.util.Timer("smb-session-expiry",true)
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,ArrayBlockingQueue(8),
        {task->Thread(task,"smb-range-worker").apply{isDaemon=true}})
    private val clients=ConcurrentHashMap.newKeySet<ClientHandler>()
    init {
        setAsyncRunner(object:AsyncRunner {
            override fun exec(client:ClientHandler) {
                clients.add(client)
                try{workers.execute(client)}catch(_:RejectedExecutionException){clients.remove(client);client.close()}
            }
            override fun closed(client:ClientHandler){clients.remove(client)}
            override fun closeAll(){clients.forEach{it.close()};clients.clear();workers.shutdownNow()}
        })
        expiry.scheduleAtFixedRate(object:java.util.TimerTask(){override fun run(){
            if(System.nanoTime()-lastUsed>TimeUnit.MINUTES.toNanos(10) && handles.isEmpty())end()
        }},30_000,30_000)
    }
    fun register(account:HerrinSmbAccount,path:String):String=synchronized(resources) {
        check(!closed.get());lastUsed=System.nanoTime()
        val key=account.id+"|"+path
        val id=keys[key]?:run{
            if(resources.size>=128) {
                val expired=resources.entries.firstOrNull{it.value.users.get()==0}?.key?:error("SMB_RESOURCE_LIMIT")
                resources.remove(expired);keys.entries.removeAll{it.value==expired}
            }
            random().also{keys[key]=it;resources[it]=Resource(account,path)}
        }
        "http://127.0.0.1:$listeningPort/$token/$id"
    }
    override fun serve(session:IHTTPSession):Response {
        fun error(status:Response.Status)=newFixedLengthResponse(status,"text/plain","")
        if(closed.get() || session.headers["host"]!="127.0.0.1:$listeningPort" || session.headers.containsKey("origin"))return error(Response.Status.FORBIDDEN)
        if(session.method !in setOf(Method.GET,Method.HEAD))return error(Response.Status.METHOD_NOT_ALLOWED)
        if(session.headers.containsKey("transfer-encoding") || session.headers["content-length"]?.let{it!="0"}==true)return error(Response.Status.BAD_REQUEST)
        val parts=session.uri.split('/')
        if(parts.size!=3 || !MessageDigest.isEqual(parts[1].toByteArray(),token.toByteArray()))return error(Response.Status.FORBIDDEN)
        val resource=synchronized(resources){resources[parts[2]]?.also{it.users.incrementAndGet()}}?:return error(Response.Status.NOT_FOUND)
        var reader:SmbReadHandle?=null
        var transferred=false
        try {
            lastUsed=System.nanoTime()
            reader=openFile(resource.account,resource.path).also{handles.add(it)}
            val size=reader.size
            val range=try{SmbByteRange.parse(session.headers["range"],size)}catch(_:IllegalArgumentException){
                return error(Response.Status.RANGE_NOT_SATISFIABLE).apply{addHeader("Content-Range","bytes */$size")}
            }
            val first=requireNotNull(reader)
            val stream=object:InputStream(){
                private var active=first
                private var position=range.start
                private var left=range.length
                private var ended=false
                private var reopened=false
                override fun read():Int {
                    val one=ByteArray(1);val n=read(one,0,1)
                    return if(n<0)-1 else one[0].toInt() and 255
                }
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    require(offset>=0 && length>=0 && offset<=buffer.size-length)
                    if(length==0)return 0
                    if(ended || closed.get())throw java.io.IOException("SMB_SESSION_CLOSED")
                    if(left<=0)return -1
                    lastUsed=System.nanoTime()
                    val wanted=minOf(length.toLong(),left,1024L*1024).toInt()
                    val n=try{active.read(buffer,position,offset,wanted)}catch(error:Exception){
                        if(reopened || closed.get())throw java.io.IOException("SMB_READ_FAILED")
                        reopened=true
                        active.close();handles.remove(active)
                        try {
                            val replacement=openFile(resource.account,resource.path)
                            if(replacement.size!=size || replacement.version!=first.version){replacement.close();throw java.io.IOException("SMB_FILE_CHANGED")}
                            active=replacement;handles.add(active)
                            active.read(buffer,position,offset,wanted)
                        }catch(_:Exception){throw java.io.IOException("SMB_RECONNECT_FAILED")}
                    }
                    if(n<=0)throw java.io.IOException("SMB_TRUNCATED_FILE")
                    position+=n;left-=n
                    return n
                }
                override fun close(){if(!ended){ended=true;active.close();handles.remove(active);resource.users.decrementAndGet()}}
            }
            val status=if(session.headers.containsKey("range"))Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val result=newFixedLengthResponse(status,"application/octet-stream",stream,range.length)
            result.addHeader("Accept-Ranges","bytes");result.addHeader("Cache-Control","no-store")
            if(status==Response.Status.PARTIAL_CONTENT)result.addHeader("Content-Range","bytes ${range.start}-${range.end}/$size")
            if(session.method==Method.HEAD)stream.close()
            transferred=true
            reader=null // Response owns its stream and closes it on disconnect/Seek.
            return result
        }catch(_:Exception){return error(Response.Status.SERVICE_UNAVAILABLE)}
        finally{reader?.let{it.close();handles.remove(it)};if(!transferred)resource.users.decrementAndGet()}
    }
    fun end(){if(closed.compareAndSet(false,true)){
        expiry.cancel();handles.forEach{runCatching{it.close()}};handles.clear()
        synchronized(resources){resources.clear();keys.clear()};stop();workers.shutdownNow()
    }}
    private companion object {fun random()=ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}}
}
