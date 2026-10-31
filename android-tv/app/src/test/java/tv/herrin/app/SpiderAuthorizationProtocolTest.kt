package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SpiderAuthorizationProtocolTest {
    private val qr = """{"title":"token=private-token","message":"Cookie: private-cookie","qrcode":"https://example.invalid/original-test-scan","buttons":[{"id":"check","label":"确认","close":false}]}"""

    @Test fun documentedQrProtocolPublishesNoCredentialText() {
        val payload = SpiderAuthorizationProtocol.publicPayload(qr)
        assertFalse(payload.contains("private-token"))
        assertFalse(payload.contains("private-cookie"))
        assertFalse(payload.contains("message"))
        assertEquals("https://example.invalid/original-test-scan", JSONObject(payload).getString("qrcode"))
        assertFalse(SpiderAuthorizationProtocol.parse(qr).toString().contains("original-test-scan"))
    }
    @Test fun successfulMockAuthorizationStaysInPluginCallbackAndReturnsAction() {
        val published = mutableListOf<Pair<Int, String?>>()
        val broker = SpiderAuthorizationSession { id, payload -> published += id to payload }
        var privateToken = ""
        val result = broker.show(qr) { event ->
            assertEquals("check", JSONObject(event).getString("button"))
            // A mock platform grants a synthetic result to its plugin; UI receives only close status.
            privateToken = "original-mock-private-token"
            CompletableFuture.completedFuture("""{"close":true,"token":"original-mock-private-token"}""")
        }
        broker.action(published.first().first, "check")
        assertEquals("original-mock-private-token", privateToken)
        assertEquals("check", JSONObject(result.get()).getString("button"))
        assertFalse(result.get().contains(privateToken))
        assertNull(published.last().second)
    }
    @Test fun pendingAuthorizationCanRefreshCancelAndRejectStaleActions() {
        val published = mutableListOf<Pair<Int, String?>>()
        val broker = SpiderAuthorizationSession { id, payload -> published += id to payload }
        var callbacks = 0
        val first = broker.show(qr) {
            callbacks++
            CompletableFuture.completedFuture("""{"close":false,"qrcode":"https://example.invalid/refreshed"}""")
        }
        val firstId = published.first().first
        broker.action(firstId, "check")
        assertEquals(1, callbacks)
        assertTrue(published.last().second!!.contains("refreshed"))
        assertFalse(first.isDone)
        val next = broker.show("""{"qrcode":"https://example.invalid/new-session"}""", null)
        assertNull(first.get())
        broker.action(firstId, "check")
        assertEquals(1, callbacks)
        assertFalse(next.isDone)
        broker.cancel()
        assertNull(next.get())
        assertNull(published.last().second)
    }
    @Test fun cancelReleasesOutstandingCallbackWithoutPublishingLateCredentials() {
        val updates = mutableListOf<String?>()
        val broker = SpiderAuthorizationSession { _, raw -> updates += raw }
        val response = CompletableFuture<String>()
        val result = broker.show(qr) { response }
        broker.action(1, "check")
        broker.cancel(1)
        assertNull(result.get())
        assertTrue(response.isCancelled)
        assertFalse(response.complete("""{"token":"private","qrcode":"private"}"""))
        assertNull(updates.last())
    }

    @Test fun cloudSharePasswordFormReturnsValueOnlyToOwningSpiderNotPublicUi() {
        val source = """{"title":"private-token","message":"secret-share",
            "inputs":[{"name":"share_password","label":"provider-secret-label",
                       "type":"password","required":true,"maxLength":12}],
            "buttons":[{"id":"submit","label":"确认"}]}"""
        val public = SpiderAuthorizationProtocol.publicPayload(source)
        assertFalse(public.contains("private-token"))
        assertFalse(public.contains("secret-share"))
        assertFalse(public.contains("provider-secret-label"))
        val ui = JSONObject(public).getJSONArray("inputs").getJSONObject(0)
        assertEquals("share_password", ui.getString("name"))
        assertEquals("password", ui.getString("type"))
        val brokerEvents = mutableListOf<String>()
        val session = SpiderAuthorizationSession { _, published -> if (published != null) brokerEvents += published }
        val submitted = session.show(source, null)
        val password = "private-8527"
        val wrong = SpiderAuthorizationProtocol.event("submit",
            JSONObject().put("share_password", "a".repeat(20)))
        session.action(1, wrong)
        assertFalse(submitted.isDone)
        session.action(1, SpiderAuthorizationProtocol.event("submit",
            JSONObject().put("share_password", password)))
        val result = JSONObject(submitted.get())
        assertEquals(password, result.getJSONObject("values").getString("share_password"))
        assertTrue(brokerEvents.none { it.contains(password) })
    }

    @Test fun cloudShareFormRejectsUnknownInjectedFieldsAndMissingRequiredPassword() {
        val options = SpiderAuthorizationProtocol.parse(
            """{"inputs":[{"name":"passcode","type":"password","required":true}]}""")
        assertNull(SpiderAuthorizationProtocol.validatedEvent(options, "ok"))
        assertNull(SpiderAuthorizationProtocol.validatedEvent(options,
            SpiderAuthorizationProtocol.event("ok", JSONObject().put("other", "secret"))))
        assertNotNull(SpiderAuthorizationProtocol.validatedEvent(options,
            SpiderAuthorizationProtocol.event("ok", JSONObject().put("passcode", "1234"))))
    }

    @Test fun unsupportedFormsRemoteImagesOversizeAndDuplicateButtonsFailExplicitly() {
        for (raw in listOf("""{"inputs":[{"id":"password"}]}""", """{"image":"https://example.invalid/image"}""",
            """{"buttons":[{"id":"same"},{"id":"same"}]}""", "x".repeat(SpiderAuthorizationProtocol.MAX_CHARS + 1))) {
            try { SpiderAuthorizationProtocol.parse(raw); fail("Must reject unsupported payload") }
            catch (_: Exception) { }
        }
    }
}
