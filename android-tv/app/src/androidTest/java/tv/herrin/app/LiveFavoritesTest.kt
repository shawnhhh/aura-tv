package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFavoritesTest {
    @Test fun savedChannelsStayWithinOnePlaylistAndDoNotStoreRawStreamUrls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("herrin", 0).edit().clear().commit()
        val favorites = LiveFavorites(context)
        val a = "https://user.example.org/list-a.m3u"
        val b = "https://user.example.org/list-b.m3u"
        val channel = "https://licensed.example.org/watch.m3u8?token=private"
        assertTrue(favorites.toggle(a, channel))
        assertTrue(LiveFavorites(context).contains(a, channel))
        assertFalse(favorites.contains(b, channel))
        assertFalse(context.getSharedPreferences("herrin", 0).all.values
            .any { value -> value.toString().contains("token=private") })
        assertFalse(favorites.toggle(a, channel))
        assertFalse(favorites.contains(a, channel))
        assertTrue(runCatching {
            favorites.toggle(a, "http://unlicensed.example.org/watch")
        }.exceptionOrNull() is IllegalArgumentException)
    }
}
