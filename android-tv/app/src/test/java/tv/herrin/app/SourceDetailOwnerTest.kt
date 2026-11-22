package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SourceDetailOwnerTest {
    private val owner=SourceDetailOwner("film-a",4,3,10,"layout")
    @Test fun currentDetailAndExplicitPlayIntentMayBind() {
        assertTrue(owner.accepts("film-a","detail",true,4,3,10,"layout"))
        assertTrue(owner.accepts("film-a","play-intent",true,4,3,10,"layout"))
    }
    @Test fun anotherFilmCannotReceiveOldVodEvenWithIdenticalConfigurationAndRevision() {
        assertFalse(owner.accepts("film-b","detail",true,4,3,10,"layout"))
    }
    @Test fun leavingDetailsOrForegroundRetiresTheResult() {
        for(screen in listOf("browse","search","player","settings"))
            assertFalse(owner.accepts("film-a",screen,true,4,3,10,"layout"))
        assertFalse(owner.accepts("film-a","detail",false,4,3,10,"layout"))
    }
    @Test fun newerNodeOrPersonalConnectionSelectionRetiresTheResultImmediately() {
        assertFalse(owner.accepts("film-a","detail",true,5,3,10,"layout"))
        assertFalse(owner.accepts("film-a","detail",true,4,4,10,"layout"))
    }
    @Test fun changedConsentOrLayoutCannotReuseTheReturnedDetail() {
        assertFalse(owner.accepts("film-a","detail",true,4,3,11,"layout"))
        assertFalse(owner.accepts("film-a","detail",true,4,3,10,"new-layout"))
        assertFalse(owner.accepts("film-a","detail",true,4,3,10,null))
    }
}
