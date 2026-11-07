package tv.herrin.app
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class P0ArtworkMemoryTest {
    @Test fun optionalImageOomReleasesPermitsWithoutRecyclingDisplayedOriginalBitmap(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        val output=ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.JPEG,95,output);source.recycle()
        val data=output.toByteArray()
        fun connection(raw:String)=object:HttpURLConnection(URL(raw)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentType()="image/jpeg"
            override fun getInputStream()=ByteArrayInputStream(data)
        }
        val url="https://fixture.invalid/${System.nanoTime()}"
        val held=AuthorizedPosterCache(context,::connection).load("$url/original.jpg",hero=true)!!
        assertEquals(Bitmap.Config.ARGB_8888,held.config)
        assertEquals(480,held.width)
        assertNull(AuthorizedPosterCache(context){throw OutOfMemoryError("synthetic image allocation")}.load("$url/next.jpg",hero=true))
        assertFalse("Cache eviction must not recycle a bitmap owned by the displayed UI",held.isRecycled)
        val deadline=SystemClock.uptimeMillis()+5000
        assertNotNull(AuthorizedPosterCache(context,::connection).load("$url/next.jpg",hero=true,
            cancelled={SystemClock.uptimeMillis()>deadline}))
        assertEquals(0,AuthorizedPosterCache.activeTasks)
    }
}
