package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SmbPolicyTest {
    @Test fun strictShareRootRejectsCredentialsTraversalAndAlternateStreams() {
        val root=SmbLocation.parse("smb://nas.local:1445/Movies/电影")
        assertEquals("nas.local",root.host);assertEquals(1445,root.port);assertEquals("Movies",root.share)
        assertTrue(root.contains(root.child(root.root,"Original.mkv")))
        assertFalse(root.contains("电影2\\Original.mkv"))
        for(url in listOf("http://nas/Movies","smb://user:password@nas/Movies","smb://nas","smb://nas/Movies/../private",
            "smb://nas/Movies/%2e%2e/private","smb://nas/Movies/%2fprivate","smb://nas/Movies/file:secret","smb://nas/Movies?password=x"))
            assertThrows(IllegalArgumentException::class.java){SmbLocation.parse(url)}
        assertThrows(IllegalArgumentException::class.java){root.child(root.root,"..")}
    }
    @Test fun rangesSupportLongOffsetsSuffixesAndRejectMultipleOrInvalidRanges() {
        val size=5L*1024*1024*1024+20
        assertEquals(20L,SmbByteRange.parse("bytes=5368709120-",size).length)
        assertEquals(size-10,SmbByteRange.parse("bytes=-10",size).start)
        assertEquals(size,SmbByteRange.parse(null,size).length)
        for(header in listOf("bytes=5-2","bytes=999999999999999999999999-","bytes=0-1,3-4","bytes=-0","bytes=99999999999-"))
            assertThrows(IllegalArgumentException::class.java){SmbByteRange.parse(header,size)}
    }
    @Test fun smb1AndDfsAreDisabledAndAccountNeverPrintsPassword() {
        val config=SmbAccess.configuration(true)
        assertEquals(5,config.supportedDialects.size)
        assertFalse(config.isUseMultiProtocolNegotiate);assertFalse(config.isDfsEnabled)
        assertTrue(config.isSigningRequired);assertTrue(config.isEncryptData)
        assertFalse(HerrinSmbAccount("smb://nas/media","u","sensitive-password","domain").toString().contains("sensitive-password"))
    }
    @Test fun anotherSourcesOfferCannotMintANasPlaybackCapability() {
        HerrinSmbSessions().use { sessions ->
            val source=HerrinSmbSource(HerrinSmbAccount("smb://nas/media"),sessions)
            val offer=HerrinSourceMovie("foreign",HerrinSourceType.NAS,Film("foreign","Original","","电影","2026","",1),"movie")
            assertTrue(source.playback(offer) is PlaybackResult.Unavailable)
        }
    }

}
