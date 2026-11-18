package tv.herrin.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PersonalRemoteSessionTest {
    @Test fun directoryScanReusesOneAuthenticatedTransportAndClosesItAfterTraversal() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/child/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                </d:response></d:multistatus>
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
            val c = PersonalConnections.Connection("fixture", "Fixture", "http", "localhost", server.port,
                "fixture-user", "fixture-secret", "", false, true, "dav")
            PersonalRemoteFiles.Session(c, "dav").use { session ->
                assertEquals("dav/child", session.list("dav").single().path)
                assertTrue(session.list("dav/child").isEmpty())
            }
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals(0, first.sequenceNumber); assertEquals(1, second.sequenceNumber)
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            assertNotNull(first.getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        }
    }
}
