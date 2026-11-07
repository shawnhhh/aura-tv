package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class SpiderProcessRetirementTest {
    @Test fun retirementCannotSelectMainOtherUidOtherServiceOrAmbiguousPid(){
        val child=OwnedSpiderProcess(43,10001,"tv.herrin.app:spider")
        fun choose(vararg values:OwnedSpiderProcess)=ownedSpiderPid(42,10001,"tv.herrin.app",values.toList())
        assertEquals(43,choose(child))
        assertNull(choose(child.copy(pid=42)))
        assertNull(choose(child.copy(uid=10002)))
        assertNull(choose(child.copy(name="tv.herrin.app")))
        assertNull(choose(child.copy(name="tv.herrin.app:player")))
        assertNull(choose(child.copy(pid=0)))
        assertNull(choose(child,child.copy(pid=44)))
        assertEquals(43,choose(child,OwnedSpiderProcess(42,10001,"tv.herrin.app")))
    }
}
