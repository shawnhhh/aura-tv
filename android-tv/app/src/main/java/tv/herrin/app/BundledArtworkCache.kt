package tv.herrin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalArtworkReady=compositionLocalOf{true}
/** Original bundled pixels, decoded on IO. No density/quality downshift or bitmap recycling. */
internal object BundledArtworkCache {
    private val cache=object:android.util.LruCache<Int,Bitmap>(32*1024*1024){
        override fun sizeOf(key:Int,value:Bitmap)=value.allocationByteCount
    }
    fun peek(id:Int):Bitmap?=cache.get(id)
    @Synchronized fun load(context:Context,id:Int):Bitmap? {
        cache.get(id)?.let{return it}
        return BitmapFactory.decodeResource(context.resources,id)?.also{cache.put(id,it)}
    }
}
@Composable internal fun rememberBundledArtwork(id:Int):Bitmap? {
    val context=LocalContext.current
    val enabled=LocalArtworkReady.current
    var bitmap by remember(id){mutableStateOf(BundledArtworkCache.peek(id))}
    LaunchedEffect(id,enabled){if(enabled &&bitmap==null)bitmap=withContext(Dispatchers.IO){BundledArtworkCache.load(context,id)}}
    return bitmap
}
