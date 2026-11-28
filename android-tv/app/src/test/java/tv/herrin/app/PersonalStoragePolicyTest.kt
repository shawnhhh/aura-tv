package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalStoragePolicyTest {
    @Test fun manualHttpHttpsAndSmbAuthoritiesHaveExplicitPortsAndNoCredentials() {
        assertEquals("https://xn--bcher-kva.example:443/", PersonalStoragePolicy.endpoint("https", "bücher.example", 443))
        assertEquals("https://nas.example:443/", PersonalStoragePolicy.endpoint("https", "nas.example", 443))
        assertEquals("http://192.168.1.20:8080/", PersonalStoragePolicy.endpoint("http", "192.168.1.20", 8080))
        assertEquals("smb://nas.example:445/", PersonalStoragePolicy.endpoint("smb", "nas.example", 445))
        listOf("user:password@nas", "nas/path", "nas?token", "nas#fragment").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { PersonalStoragePolicy.endpoint("http", host, 80) }
        }
        assertThrows(IllegalArgumentException::class.java) { PersonalStoragePolicy.endpoint("ftp", "nas", 21) }
    }
    @Test fun defaultPortsShareAnOriginButOtherAuthoritiesNeverDo() {
        fun same(a: String, b: String) = PersonalStoragePolicy.sameOrigin(java.net.URI(a), java.net.URI(b))
        assertTrue(same("https://nas/dav/", "https://NAS:443/dav/file"))
        assertTrue(same("http://nas/dav/", "http://nas:80/dav/file"))
        assertFalse(same("https://nas/dav/", "http://nas:443/dav/file"))
        assertFalse(same("https://nas/dav/", "https://nas:444/dav/file"))
        assertFalse(same("https://nas/dav/", "https://foreign/dav/file"))
        assertFalse(same("https://nas/dav/", "https://user:secret@nas/dav/file"))
    }
    @Test fun folderContainmentDoesNotConfuseSiblingsAndRejectsTraversal() {
        assertTrue(PersonalStoragePolicy.contains("Movies", "Movies/2026"))
        assertFalse(PersonalStoragePolicy.contains("Movies", "Movies2"))
        listOf("Movies/../private", "%2e%2e", "Movies\\secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { PersonalStoragePolicy.path(it) }
        }
        assertEquals("Movies/100% Wolf", PersonalStoragePolicy.path("/Movies/100% Wolf/"))
        assertEquals("Movies/电影", PersonalStoragePolicy.path("/Movies/电影/"))
    }
    @Test fun fullWebDavUrlsPreserveProtocolPortAndBaseDirectory() {
        assertEquals(5244, PersonalStoragePolicy.webDavAddress("http://nas.example/dav", "5244").port)
        assertEquals("xn--bcher-kva.example", PersonalStoragePolicy.webDavAddress("https://bücher.example/电影").host)
        assertEquals("电影", PersonalStoragePolicy.webDavAddress("https://bücher.example/电影").basePath)
        assertThrows(IllegalArgumentException::class.java) {
            PersonalStoragePolicy.webDavAddress("http://nas.example:5244/dav", "8080")
        }
        val https = PersonalStoragePolicy.webDavAddress("https://nas.example:8443/dav/")
        assertEquals("https", https.scheme)
        assertEquals(8443, https.port)
        assertEquals("dav", https.basePath)
        val http = PersonalStoragePolicy.webDavAddress("http://192.168.1.8:5244/dav")
        assertEquals("http", http.scheme)
        assertEquals(5244, http.port)
        assertEquals(80, PersonalStoragePolicy.webDavAddress("http://nas.example/dav").port)
        assertEquals(443, PersonalStoragePolicy.webDavAddress("https://nas.example/dav").port)
        val c = PersonalConnections.Connection("test", "NAS", https.scheme, https.host, https.port,
            "", "", "", false, true, "Movies", https.basePath)
        assertEquals("https://nas.example:8443/dav/Movies", c.address("Movies"))
        assertEquals("https://nas.example:8443/dav/", c.address(""))
        for (url in listOf("ftp://nas.example/dav", "nas.example/dav",
            "https://user:secret@nas.example/dav", "https://nas.example/dav?token=secret",
            "https://nas.example/dav/%2e%2e/private")) {
            assertThrows(IllegalArgumentException::class.java) {
                PersonalStoragePolicy.webDavAddress(url)
            }
        }
    }
    @Test fun episodeAndYearClaimsAreParsedWithoutInferringDecoderCapabilities() {
        val name = PersonalStoragePolicy.parse("Example.Show.2024.S02E13.2160p.DV.mkv")
        assertEquals(2024, name.year); assertEquals(2, name.season); assertEquals(13, name.episode)
        assertTrue(PersonalStoragePolicy.extensions.containsAll(listOf("mkv", "mp4", "ts", "m2ts")))
    }
    @Test fun bracketedAnimeAndChineseSeasonsKeepWorkIdentityWithoutResolutionEpisodes() {
        val anime = PersonalStoragePolicy.parse("[ReleaseGroup] Example II - 03v2 [1080p][A1B2C3D4].mkv", "Anime/Example II/Season 2")
        assertEquals("Example II", anime.title); assertEquals(2, anime.season); assertEquals(3, anime.episode)
        val chinese = PersonalStoragePolicy.parse("第十二集.2160p.mkv", "剧集/长安 (2025)/第二季")
        assertEquals("长安", chinese.title); assertEquals(2025, chinese.year); assertEquals(2,chinese.season); assertEquals(12,chinese.episode)
        assertNull(PersonalStoragePolicy.parse("Example.[720].mkv", "Movies").episode)
        val numeric = PersonalStoragePolicy.parse("1917.1080p.mkv")
        assertEquals("1917", numeric.title); assertNull(numeric.year)
        assertEquals("Example II Part 2", PersonalStoragePolicy.parse("Example Ⅱ Part 2.1080p.mkv").title)
    }
    @Test fun contextualNamesKeepSequelDigitsAndUseSeasonDirectoryForNumericEpisodes() {
        val movie = PersonalStoragePolicy.parse("流浪地球2.2023.2160p.WEB-DL.H265.mkv")
        assertEquals("流浪地球2", movie.title)
        assertEquals(2023, movie.year)
        val episode = PersonalStoragePolicy.parse("01.mkv", "绝命毒师/Season 01")
        assertEquals("绝命毒师", episode.title)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        val special = PersonalStoragePolicy.parse("OVA 02.mkv", "Anime/Example/Specials")
        assertEquals("Example", special.title)
        assertEquals(0, special.season)
        assertEquals(2, special.episode)
    }

    @Test fun workDirectoryOwnsReleaseOnlyFilesAndDistinguishesSameNameYears() {
        val directory = "电影/流浪地球2（2023） [imdbid-tt13539646]"
        val remux = PersonalStoragePolicy.parse("2160p.REMUX.mkv", directory)
        val hd = PersonalStoragePolicy.parse("1080p.WEB-DL.mkv", directory)
        assertEquals("流浪地球2", remux.title)
        assertEquals(2023, remux.year)
        assertEquals("tt13539646", remux.imdb)
        assertNotNull(remux.workKey("movie"))
        assertEquals(remux.workKey("movie"), hd.workKey("movie"))
        assertNotEquals(remux.workKey("movie"),
            PersonalStoragePolicy.parse("1080p.WEB-DL.mkv", "电影/流浪地球2（2001）").workKey("movie"))
        assertNull(PersonalStoragePolicy.parse("Unexpected.2024.mkv", "电影/4K").workKey("movie"))
    }
}
