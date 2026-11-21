package tv.herrin.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SpiderAuthorizationProtocolTest {
    @Test fun originalSelectAndSourceValuesAreRetainedWithoutInventingAnAuthorizationResult() {
        val raw = """{"qrcode":"fixture-qr-payload","inputs":[{"name":"provider","type":"select","options":[{"label":"one","value":"a"},"b"],"value":"a"}]}"""
        val options = SpiderAuthorizationProtocol.parse(SpiderAuthorizationProtocol.publicPayload(raw))
        assertEquals(listOf("a","b"), options.inputs.single().choices)
        assertEquals("a", options.inputs.single().initial)
        assertNull(SpiderAuthorizationProtocol.validatedEvent(options,
            SpiderAuthorizationProtocol.event("ok", JSONObject().put("provider","unoffered"))))
        val session = SpiderAuthorizationSession { _, _ -> }
        val pending = session.show(raw, null)
        assertFalse(pending.isDone)
        session.cancel()
        assertNull(pending.get())
    }
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

    @Test fun validationScopeSurvivesIpcAndRefreshDoesNotRequireAnUnrelatedPassword() {
        val raw = """{"inputs":[{"name":"password","type":"password","required":true},
            {"name":"phone","required":true}],"buttons":[
            {"id":"refresh","label":"刷新","close":false,"validate":[]},
            {"id":"phone","label":"确认","validate":["phone"]}]}"""
        val options = SpiderAuthorizationProtocol.parse(SpiderAuthorizationProtocol.publicPayload(raw))
        assertTrue(options.buttons.first().skipValidation)
        assertNotNull(SpiderAuthorizationProtocol.validatedEvent(options, "refresh"))
        assertNotNull(SpiderAuthorizationProtocol.validatedEvent(options,
            SpiderAuthorizationProtocol.event("phone", JSONObject().put("phone", "123"))))
        assertNull(SpiderAuthorizationProtocol.validatedEvent(options, "phone"))
    }

    @Test fun callbackCanEnableNextLoginStepAndEmptyErrorsPermitCompletion() {
        val published = mutableListOf<Pair<Int,String?>>()
        val broker = SpiderAuthorizationSession { id, raw -> published += id to raw }
        val initial = """{"inputs":[{"name":"password","type":"password","required":true,"enabled":false}],
            "buttons":[{"id":"refresh","label":"刷新","close":false,"validate":[]},
            {"id":"login","label":"确认","enabled":false}]}"""
        val result = broker.show(initial) { event ->
            if (JSONObject(event).getString("button") == "refresh") CompletableFuture.completedFuture(
                """{"inputs":[{"name":"password","enabled":true}],"buttons":[{"id":"refresh","enabled":false},
                    {"id":"login","enabled":true}],"errors":{"password":"private error text"},"close":false}""")
            else CompletableFuture.completedFuture("""{"close":true,"errors":{"password":""}}""")
        }
        broker.action(1, "refresh")
        val public = published.last().second!!
        assertFalse(public.contains("private error text"))
        val options = SpiderAuthorizationProtocol.parse(public)
        assertTrue(options.invalid)
        assertTrue(options.inputs.single().enabled)
        assertFalse(options.buttons.first().enabled)
        assertTrue(options.buttons.last().enabled)
        broker.action(1, "refresh") // Disabled action is ignored.
        assertFalse(result.isDone)
        broker.action(1, SpiderAuthorizationProtocol.event("login", JSONObject().put("password", "original-fixture")))
        assertEquals("login", JSONObject(result.get()).getString("button"))
        assertNull(published.last().second)
    }

    @Test fun unsupportedFormsRemoteImagesOversizeAndDuplicateButtonsFailExplicitly() {
        for (raw in listOf("""{"inputs":[{"id":"password"}]}""", """{"image":"https://example.invalid/image"}""",
            """{"buttons":[{"id":"same"},{"id":"same"}]}""", "x".repeat(SpiderAuthorizationProtocol.MAX_CHARS + 1))) {
            try { SpiderAuthorizationProtocol.parse(raw); fail("Must reject unsupported payload") }
            catch (_: Exception) { }
        }
    }
}
