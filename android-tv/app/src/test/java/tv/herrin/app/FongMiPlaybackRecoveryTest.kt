package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class FongMiPlaybackRecoveryTest {
    private val remote = "https://private.invalid/media?token=NEVER_LOG"
    private val proxy = "http://127.0.0.1:32100/proxy?session=NEVER_LOG"
    private fun failure(code: String = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", status: Int? = null,
                        error: Throwable = ConnectException("Cookie=NEVER_LOG"), url: String = remote) =
        FongMiPlaybackRecovery.classify(code, status, error, url)

    @Test fun denialsNeverRefreshRetryOrChangeMimeEvenWhenWrappedInNetworkError() {
        for (status in listOf(401, 403, 404, 429)) {
            val failure = failure(status = status, url = proxy)
            val session = FongMiPlaybackRecovery.Session()
            assertNull("HTTP $status must be final", session.next(failure, true, true))
            assertFalse(PlaybackFormatRetry.retryNextSource(2004, status))
        }
    }

    @Test fun expiredResultRefreshesOnlyOnceAcrossReplacementPlayerInstances() {
        // MainActivity owns one session outside the playerRetryEpoch key.
        val selectedEpisode = FongMiPlaybackRecovery.Session()
        val expired = failure("ERROR_CODE_IO_BAD_HTTP_STATUS", 410)
        val outgoingPlayerPlan = selectedEpisode.next(expired, true, false)!!
        assertEquals(FongMiPlaybackRecovery.Action.REFRESH_SOURCE, outgoingPlayerPlan.action)
        selectedEpisode.cancelPending() // outgoing player's onDispose
        assertFalse(selectedEpisode.isCurrent(outgoingPlayerPlan))
        assertNull(selectedEpisode.next(expired, true, false)) // incoming player, same selection
        selectedEpisode.userRetry()
        assertNotNull(selectedEpisode.next(expired, true, false))
    }

    @Test fun interruptedRangeReadsRetrySameMediaWithFiniteExponentialBackoff() {
        val session = FongMiPlaybackRecovery.Session()
        val interruptedRange = failure("ERROR_CODE_IO_UNSPECIFIED", error = EOFException(remote))
        assertEquals(FongMiPlaybackRecovery.Kind.MEDIA_READ, interruptedRange.kind)
        val first = session.next(interruptedRange, false, false)!!
        assertEquals(FongMiPlaybackRecovery.Action.RETRY_MEDIA, first.action)
        assertEquals(500L, first.delayMs)
        val second = session.next(interruptedRange, false, false)!!
        assertEquals(1_000L, second.delayMs)
        assertFalse(session.isCurrent(first))
        repeat(50) { assertNull(session.next(interruptedRange, false, false)) }
    }

    @Test fun timeoutAndTransientServerFailuresShareOneBudgetInsteadOfMultiplyingRetries() {
        val session = FongMiPlaybackRecovery.Session()
        val timeout = failure("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", error = SocketTimeoutException(remote))
        assertEquals(FongMiPlaybackRecovery.Kind.TIMEOUT, timeout.kind)
        assertNotNull(session.next(timeout, false, false))
        assertNotNull(session.next(failure("ERROR_CODE_IO_BAD_HTTP_STATUS", 503), false, false))
        assertNull(session.next(timeout, false, false))
    }

    @Test fun loopbackConnectionLossRefreshesSourceRatherThanSelectingSoftwareDecoder() {
        val session = FongMiPlaybackRecovery.Session()
        val stoppedProxy = failure(url = proxy)
        assertEquals(FongMiPlaybackRecovery.Kind.PROXY_CONNECTION, stoppedProxy.kind)
        assertEquals(FongMiPlaybackRecovery.Action.REFRESH_SOURCE,
            session.next(stoppedProxy, true, false)?.action)
        assertNull(session.next(stoppedProxy, true, false))
        assertNull(FongMiPlaybackRecovery.Session().next(stoppedProxy, false, false))
    }

    @Test fun invalidOrDeniedProxySessionIsNeverRebuiltBlindly() {
        for (health in listOf("HTTP_403", "HTTP_404", "INVALID_SESSION", "INVALID_PORT", "NOT_CATVOD", "READY"))
            assertFalse(health, FongMiPlaybackRecovery.canRebuildProxy(health))
        for (health in listOf("CONNECT_REFUSED", "TIMEOUT", "IO_FAILURE", "HTTP_5XX"))
            assertTrue(health, FongMiPlaybackRecovery.canRebuildProxy(health))
    }

    @Test fun liveWindowUsesUpstreamSeekDefaultAndCannotLoopForever() {
        val session = FongMiPlaybackRecovery.Session()
        val live = failure("ERROR_CODE_BEHIND_LIVE_WINDOW", error = IllegalStateException())
        assertNull(session.next(live, true, false))
        repeat(2) {
            val plan = session.next(live, true, true)!!
            assertEquals(FongMiPlaybackRecovery.Action.SEEK_LIVE, plan.action)
        }
        assertNull(session.next(live, true, true))
    }

    @Test fun cancellationInvalidatesDelayedCallbacksAndDoesNotResetBudget() {
        val session = FongMiPlaybackRecovery.Session()
        val error = failure(error = SocketTimeoutException())
        val first = session.next(error, true, false)!!
        session.cancelPending()
        assertFalse(session.isCurrent(first))
        assertNotNull(session.next(error, true, false))
        assertNull(session.next(error, true, false))
    }

    @Test fun tlsDrmPermissionsAndDecoderFailuresAreNotRetriedAsTransport() {
        val failures = listOf(
            failure(error = SSLHandshakeException(remote)),
            failure("ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED"),
            failure("ERROR_CODE_IO_NO_PERMISSION"),
            failure("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED"),
            failure("ERROR_CODE_DECODING_FAILED"),
            failure("ERROR_CODE_PARSING_MANIFEST_MALFORMED")
        )
        failures.forEach { assertNull(FongMiPlaybackRecovery.Session().next(it, true, true)) }
    }

    @Test fun unknownPluginMessagesDoNotBecomeRecoverableAndDecisionsContainNoSecrets() {
        val error = failure("http://NEVER_LOG", error = RuntimeException("token=NEVER_LOG"))
        assertEquals(FongMiPlaybackRecovery.Kind.UNKNOWN, error.kind)
        assertNull(FongMiPlaybackRecovery.Session().next(error, true, false))
        assertFalse(error.toString().contains("NEVER_LOG"))
    }

    @Test fun onlyExplicitUserRetryStartsNewRecoveryBudget() {
        val session = FongMiPlaybackRecovery.Session()
        val read = failure(error = EOFException())
        repeat(2) { assertNotNull(session.next(read, false, false)) }
        assertNull(session.next(read, false, false))
        session.userRetry()
        assertNotNull(session.next(read, false, false))
        // A different episode/line receives a different Session, independent budget.
        assertNotNull(FongMiPlaybackRecovery.Session().next(read, false, false))
    }
}
