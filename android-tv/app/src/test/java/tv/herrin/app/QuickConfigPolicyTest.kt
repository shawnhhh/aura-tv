package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickConfigPolicyTest {
    @Test fun everyUnexpectedFieldAndNonSourceEndpointIsRejected() {
        for (field in listOf("token", "password", "username", "authorization", "cookie", "epg", "headers", "jar", "secret")) {
            assertThrows(IllegalArgumentException::class.java) {
                QuickConfigPolicy.validate("/sources", JSONObject().put("url", "http://example.org/a").put(field, "sensitive"))
            }
        }
        for (path in listOf("/tmdb", "/nas", "/live", "/catalogs/save", "/status", "/entries/delete")) {
            assertThrows(IllegalStateException::class.java) { QuickConfigPolicy.validate(path, JSONObject()) }
        }
        for (url in listOf("http://a.example/a?x=secret", "http://a.example/a?", "http://u:p@a.example/a",
            "http://a.example/%74oken/secret", "https://a.example/a#secret")) {
            assertThrows(IllegalArgumentException::class.java) { QuickConfigPolicy.publicAddress(url) }
        }
        QuickConfigPolicy.validate("/sources", JSONObject().put("url", "http://饭太硬.net/tv"))
        assertThrows(IllegalArgumentException::class.java) {
            QuickConfigPolicy.validate("/entries", JSONObject().put("module", "nas"))
        }
    }
}
