package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class ProxyHealthDiagnosticTest {
    @Test fun finiteHealthCodesSurviveUiSanitizationWithoutPrivateExceptionText() {
        for (code in listOf("IO_MAIN_THREAD", "IO_CONNECT", "IO_SOCKET", "IO_POLICY", "IO_TLS", "IO_STREAM", "IO_UNKNOWN", "IO_FAILURE")) {
            val report = PlaybackErrorReport.source("SPIDER", "PROXY_$code https://private.invalid/?Token=private Cookie=private Authorization=private")
            assertTrue(report, report.contains("SOURCE_PROXY_$code"))
            assertTrue(report.contains("失败阶段：本机 Spider 代理健康检查"))
            assertFalse(report.contains("private"))
            assertFalse(report.contains("Authorization"))
        }
    }

    @Test fun policyTlsAndProgrammingFailuresDoNotAutomaticallySwitchAuthorizedSources() {
        for (code in listOf("IO_POLICY", "IO_TLS", "IO_MAIN_THREAD", "IO_UNKNOWN", "IO_FAILURE"))
            assertEquals(FongMiPlaybackRecovery.Kind.NETWORK_POLICY,
                HerrinAutomaticPlayback.resolutionFailure("PROXY_$code").kind)
    }
}
