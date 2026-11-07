package tv.herrin.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SourceHttpFailure(val code: Int, val retryAfterMs: Long? = null) : IOException("HTTP $code")
internal object SourceHttpWork { val active=java.util.concurrent.atomic.AtomicInteger() }

/** Cancellation closes the socket, including a stalled response body, not merely its coroutine waiter. */
internal suspend fun <T> readSourceResponse(client: OkHttpClient, request: Request,
    read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call=client.newCall(request)
    SourceHttpWork.active.incrementAndGet()
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object:Callback {
        override fun onFailure(call:Call,e:IOException){SourceHttpWork.active.decrementAndGet();if(continuation.isActive)continuation.resumeWithException(e)}
        override fun onResponse(call:Call,response:Response){
            try{response.use{if(continuation.isActive){val value=read(it);continuation.resume(value)}}}
            catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
            finally{SourceHttpWork.active.decrementAndGet()}
        }
    })
}
