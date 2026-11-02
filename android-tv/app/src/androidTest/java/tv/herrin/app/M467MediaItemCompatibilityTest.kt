package tv.herrin.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android runtime verification of the real Media3 RequestMetadata Bundle ABI.
 * Uses dummy local media URI; never issues HTTP requests with the test credentials.
 */
@RunWith(AndroidJUnit4::class)
class M467MediaItemCompatibilityTest {
    @Test fun perSessionHeadersRoundTripThroughActualMediaItem() {
        val first = MediaItem.Builder().setUri(Uri.parse("https://licensed.invalid/one.m3u8"))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply {
                putString("Referer", "https://licensed.invalid/")
                putString("Authorization", "Bearer private-fixture-one")
                putInt("nonStringMetadata", 7)
            }).build()).build()
        val second = MediaItem.Builder().setUri(Uri.parse("https://licensed.invalid/two.m3u8"))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply {
                putString("Authorization", "Bearer private-fixture-two")
            }).build()).build()
        val firstHeaders = FongMiPlaybackItemSupport.extractHeaders(first)
        assertEquals("Bearer private-fixture-one", firstHeaders["Authorization"])
        assertEquals("https://licensed.invalid/", firstHeaders["Referer"])
        assertFalse(firstHeaders.containsKey("nonStringMetadata"))
        assertEquals(mapOf("Authorization" to "Bearer private-fixture-two"),
            FongMiPlaybackItemSupport.extractHeaders(second))
        assertEquals("Bearer private-fixture-one", firstHeaders["Authorization"])
    }

    @Test fun missingRequestMetadataHeadersReturnEmptyMap() {
        val item = MediaItem.Builder().setUri(Uri.parse("https://licensed.invalid/missing.mp4")).build()
        assertTrue(FongMiPlaybackItemSupport.extractHeaders(item).isEmpty())
    }
}
