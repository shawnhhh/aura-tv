package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LanConfigIdentityPolicyTest {
    @Test fun onlyCanonicalLocalIpv4IdentitiesCanBindAndWarehousesKeepIndependentRules() {
        for(host in listOf("192.168.1.2","10.0.0.2","172.16.1.2","127.0.0.1"))
            assertEquals(host,LanConfigIdentityPolicy.address(host))
        for(host in listOf("0.0.0.0","8.8.8.8","example.org","localhost","::","127.1","192.168.01.2","172.32.1.2","169.254.1.2","256.1.1.1"))
            assertThrows(IllegalArgumentException::class.java){LanConfigIdentityPolicy.address(host)}
        assertEquals(TvBoxUrl.normalize("http://饭太硬.net/tv",true),AdditionalServiceConfigs.sourceAddress("http://饭太硬.net/tv"))
        for(url in listOf("javascript:alert(1)","http://user:secret@example.org/a","http://example.org/a#secret"))
            assertThrows(IllegalArgumentException::class.java){AdditionalServiceConfigs.sourceAddress(url)}
    }
}
