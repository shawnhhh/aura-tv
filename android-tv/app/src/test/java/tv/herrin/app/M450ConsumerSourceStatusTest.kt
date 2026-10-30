package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M450ConsumerSourceStatusTest {
    @Test fun importedCatalogKeepsCountsButHidesRuntimeInternals() {
        val raw = "已识别单仓：49 个站点，2 组直播配置；可尝试自动加载 Java Spider，其他运行时可能不兼容。"
        val display = ConsumerSourceStatus.display(raw)
        assertTrue(display.contains("49 个站点"))
        assertTrue(display.contains("2 组直播配置"))
        assertTrue(display.contains("请选择需要浏览的影视点"))
        assertFalse(display.contains("Spider"))
        assertFalse(display.contains("Java"))
    }

    @Test fun pluginFailureIsActionableWithoutImplementationOrRawException() {
        val raw = "配置读取成功，但 Spider 子进程异常退出；崩溃日志包含私有路径"
        val display = ConsumerSourceStatus.display(raw)
        assertTrue(display.contains("停止自动重试"))
        assertTrue(display.contains("选择其他影视点"))
        assertFalse(display.contains("Spider"))
        assertFalse(display.contains("私有路径"))
        assertFalse(display.contains("子进程"))
    }

    @Test fun loadingAndUnsupportedSiteAreNotPresentedAsDeveloperMessages() {
        assertEquals("正在连接影视点，请稍候…",
            ConsumerSourceStatus.display("正在尝试 Spider 测试影视点（1/5）…"))
        assertEquals("该影视点暂不支持，请尝试其他影视点。",
            ConsumerSourceStatus.display("当前属于 TVBox JS 规则，请选择 Spider 站点"))
    }

    @Test fun ordinarySourceStatusRemainsAvailableForConsumerTroubleshooting() {
        val ok = "已导入 12 个影视点；选择影视点后才加载影片目录"
        val failure = "当前没有可播放影片，请更换影视点"
        assertEquals(ok, ConsumerSourceStatus.display(ok))
        assertEquals(failure, ConsumerSourceStatus.display(failure))
    }

    @Test fun unfamiliarInternalFailureDoesNotLeakRuntimeDetails() {
        val text = ConsumerSourceStatus.display("JAR 初始化失败；系统原因：/private/user/secret")
        assertTrue(text.contains("重试"))
        assertFalse(text.contains("JAR"))
        assertFalse(text.contains("secret"))
    }
}
