/*
 * Derived from FongMi/TV PlayerManager.handleRefreshError and
 * ExoPlayerEngine.handleError, fongmi 5e191aaced194e5cac5668cc91908ecb65336222.
 * Copyright (C) FongMi/TV contributors. GPL-3.0-only; see LICENSE-GPL-3.0.md.
 * Herrin changes: bounded transport backoff, explicit authorization exclusions,
 * cancellation generations and a budget owned by the selected episode/line.
 */
package tv.herrin.app

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** No URLs, headers or plugin messages are retained in a recovery decision. */
object FongMiPlaybackRecovery {
    enum class Kind {
        SPIDER_INITIALIZATION, SPIDER_PROCESS, PROXY_CONNECTION, PARSE,
        AUTHENTICATION, HTTP, MEDIA_READ, DECODE, TIMEOUT, LIVE_WINDOW, NETWORK_POLICY, UNKNOWN
    }
    enum class Action { RETRY_MEDIA, REFRESH_SOURCE, SEEK_LIVE }
    data class Failure(val kind: Kind, val retryable: Boolean = false,
                       val requiresFreshSource: Boolean = false)
    data class Plan(val action: Action, val delayMs: Long, internal val generation: Long)

    fun classify(code: String, status: Int?, error: Throwable, failedUrl: String): Failure {
        val safeStatus = PlaybackFailureHints.safeHttpStatus(status)
        if (safeStatus == 401 || safeStatus == 403 || safeStatus == 429)
            return Failure(Kind.AUTHENTICATION)
        if (safeStatus != null) return when (safeStatus) {
            410 -> Failure(Kind.HTTP, requiresFreshSource = true)
            in 500..599 -> Failure(Kind.HTTP, retryable = true)
            else -> Failure(Kind.HTTP)
        }
        val causes = PlaybackFailureHints.causes(error)
        if (causes.any { it is SSLException || it is java.security.cert.CertificateException })
            return Failure(Kind.NETWORK_POLICY)
        val safeCode = PlaybackFailureHints.safeCode(code)
        if (safeCode.contains("CLEARTEXT_NOT_PERMITTED") || safeCode.contains("NO_PERMISSION") ||
            safeCode.contains("DRM")) return Failure(Kind.NETWORK_POLICY)
        if (safeCode.contains("BEHIND_LIVE_WINDOW")) return Failure(Kind.LIVE_WINDOW, true)
        if (safeCode.contains("DECOD")) return Failure(Kind.DECODE)
        if (safeCode.contains("PARSING") || safeCode.contains("CONTAINER")) return Failure(Kind.PARSE)
        val local = runCatching { java.net.URI(failedUrl).host?.lowercase() }.getOrNull() in
            setOf("127.0.0.1", "::1", "[::1]", "localhost")
        if (local && causes.any { it is ConnectException || it is SocketException || it is SocketTimeoutException })
            return Failure(Kind.PROXY_CONNECTION, requiresFreshSource = true)
        if (causes.any { it is SocketTimeoutException } || safeCode.contains("TIMEOUT"))
            return Failure(Kind.TIMEOUT, true)
        if (causes.any { it is EOFException || it is SocketException || it is NoRouteToHostException ||
                    it is UnknownHostException }) return Failure(Kind.MEDIA_READ, true)
        return Failure(Kind.UNKNOWN)
    }

    /**
     * Select only a provider-supplied backup on a transient server failure.
     * Authorization denials and rate limiting must NEVER trigger this route.
     * Called from Media3's error callback before scheduling delayed retries.
     */
    fun preferAuthorizedAlternate(httpStatus: Int?, hasNext: Boolean): Boolean =
        hasNext && httpStatus != null && httpStatus in 500..599

    /** Exactly the health failures that prove a transport failure, not a denied session. */
    fun canRebuildProxy(healthName: String): Boolean = healthName in
        setOf("CONNECT_REFUSED", "TIMEOUT", "IO_FAILURE", "HTTP_5XX")

    /**
     * Keep this object outside a media/refresh key. READY must not reset counters:
     * an HLS segment can fail after each successful prepare, otherwise forever.
     */
    class Session {
        private var transportAttempts = 0
        private var refreshAttempts = 0
        private var liveAttempts = 0
        private var generation = 0L

        fun next(failure: Failure, canRefresh: Boolean, isLive: Boolean): Plan? {
            val action: Action
            val delay: Long
            when {
                failure.kind == Kind.LIVE_WINDOW && isLive && liveAttempts < 2 -> {
                    liveAttempts++
                    action = Action.SEEK_LIVE
                    delay = 0L
                }
                failure.requiresFreshSource && canRefresh && refreshAttempts < 1 -> {
                    refreshAttempts++
                    action = Action.REFRESH_SOURCE
                    delay = 400L
                }
                failure.retryable && failure.kind != Kind.LIVE_WINDOW && transportAttempts < 2 -> {
                    action = Action.RETRY_MEDIA
                    delay = 500L shl transportAttempts
                    transportAttempts++
                }
                else -> return null
            }
            return Plan(action, delay, ++generation)
        }

        fun isCurrent(plan: Plan): Boolean = plan.generation == generation
        fun cancelPending() { generation++ }
        /** Only an explicit user retry resets budgets, never a parser or player callback. */
        fun userRetry() {
            cancelPending()
            transportAttempts = 0
            refreshAttempts = 0
            liveAttempts = 0
        }
    }
}
