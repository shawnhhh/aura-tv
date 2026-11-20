package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletionException

class KernelFailureCodeTest {
    @Test fun wrappedDenialsAndTransportFailuresKeepTheirActualStage() {
        assertEquals("KERNEL_AUTH_REQUIRED", KernelFailureCode.classify(CompletionException(SecurityException("secret"))))
        assertEquals("KERNEL_TLS_FAILED", KernelFailureCode.classify(javax.net.ssl.SSLException("secret")))
        assertEquals("KERNEL_CONNECT_FAILED", KernelFailureCode.classify(java.net.ConnectException("secret")))
        assertEquals("KERNEL_EMPTY_DETAIL", KernelFailureCode.classify(IllegalStateException("EMPTY_DETAIL")))
    }
    @Test fun unknownErrorsRemainUnknownWithoutLeakingTheirMessages() {
        val report = PlaybackErrorReport.source("DETAIL", KernelFailureCode.classify(IllegalStateException("https://secret/token")))
        assertTrue(report.contains("SOURCE_KERNEL_REQUEST_FAILED"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("SOURCE_UNCLASSIFIED"))
        assertTrue(PlaybackErrorReport.source("PLAYER", "KERNEL_SELECTION_EXPIRED").contains("SOURCE_KERNEL_SELECTION_EXPIRED"))
    }
}
