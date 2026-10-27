package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LiveChannelDiscoveryTest {
    private val channels = listOf(
        LiveChannel("新闻一", "https://licensed.example.org/news-1", "新闻"),
        LiveChannel("体育一", "https://licensed.example.org/sports", " 体育 "),
        LiveChannel("新闻二", "https://licensed.example.org/news-2", "新闻"),
        LiveChannel("独立台", "https://licensed.example.org/other"),
        LiveChannel("体育二", "https://licensed.example.org/sports-2", "体育")
    )

    @Test fun groupsPreserveSourceOrderAndDistinguishUngroupedFromAll() {
        assertEquals(listOf("新闻", "体育", ""), LiveChannelDiscovery.groups(channels))
        assertNull(LiveChannelDiscovery.selectedGroupOrAll(channels, null))
        assertEquals("", LiveChannelDiscovery.selectedGroupOrAll(channels, ""))
        assertNull(LiveChannelDiscovery.selectedGroupOrAll(channels, "不存在的旧分组"))
    }

    @Test fun groupAndFavoriteFiltersComposeWithoutReorderingOrLeakingOtherGroups() {
        val saved = setOf(channels[1].url, channels[2].url, channels[3].url)
        val isFavorite: (LiveChannel) -> Boolean = { it.url in saved }
        assertEquals(listOf("新闻二"), LiveChannelDiscovery.visible(
            channels, "新闻", true, isFavorite).map { it.name })
        assertEquals(listOf("体育一"), LiveChannelDiscovery.visible(
            channels, "体育", true, isFavorite).map { it.name })
        assertEquals(listOf("独立台"), LiveChannelDiscovery.visible(
            channels, "", true, isFavorite).map { it.name })
        assertEquals(listOf("体育一", "新闻二", "独立台"), LiveChannelDiscovery.visible(
            channels, null, true, isFavorite).map { it.name })
        assertEquals(channels, LiveChannelDiscovery.visible(channels, null, false) { false })
    }

    @Test fun staleGroupFallsBackToAllAfterPlaylistChange() {
        val replacement = listOf(LiveChannel("音乐", "https://licensed.example.org/music", "音乐"))
        assertEquals(replacement, LiveChannelDiscovery.visible(replacement, "新闻", false) { false })
        assertTrue(LiveChannelDiscovery.visible(emptyList(), "新闻", false) { false }.isEmpty())
    }
}
