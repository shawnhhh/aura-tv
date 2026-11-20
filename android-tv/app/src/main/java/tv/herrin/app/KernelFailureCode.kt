package tv.herrin.app

/** Fixed host codes only. Never export an upstream message, URL or credential. */
internal object KernelFailureCode {
    fun classify(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        return when {
            causes.any { it is SecurityException || it is SourceHttpFailure && it.code in setOf(401, 403, 429) } -> "KERNEL_AUTH_REQUIRED"
            causes.any { it is javax.net.ssl.SSLException } -> "KERNEL_TLS_FAILED"
            causes.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException } -> "KERNEL_TIMEOUT"
            causes.any { it is java.net.UnknownHostException } -> "KERNEL_DNS_FAILED"
            causes.any { it is java.net.ConnectException } -> "KERNEL_CONNECT_FAILED"
            causes.any { it.message == "EMPTY_DETAIL" } -> "KERNEL_EMPTY_DETAIL"
            causes.any { it.message == "UPSTREAM_PLAYBACK_ERROR" } -> "KERNEL_PLAYBACK_FAILED"
            causes.any { it.message in setOf("Kernel movie session expired", "Warehouse selection changed",
                "Search site differs from active kernel session") } -> "KERNEL_SESSION_EXPIRED"
            else -> "KERNEL_REQUEST_FAILED"
        }
    }
}
