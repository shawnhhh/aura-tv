package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpiderStartupTraceTest {
    @Test fun stagePersistsAcrossProcessBoundariesWithoutSourceMetadata() {
        val folder = Files.createTempDirectory("herrin-stage").toFile()
        val file = File(folder, "stage")
        try {
            val now = 1_790_000_000_000L
            SpiderStartupTrace.write(file, SpiderStartupTrace.Stage.INITIALIZER, now)
            assertEquals("执行 Init.init", SpiderStartupTrace.read(file, now + 5_000))
            assertEquals(1, file.readText().count { it == '|' })
            assertNull(SpiderStartupTrace.read(file, now + 180_000))
            SpiderStartupTrace.write(file, SpiderStartupTrace.Stage.HOME, now + 200_000)
            assertEquals("读取影片目录", SpiderStartupTrace.read(file, now + 200_001))
            SpiderStartupTrace.write(file, SpiderStartupTrace.Stage.READY, now + 200_010)
            assertNull(SpiderStartupTrace.read(file, now + 200_020))
        } finally {
            file.delete()
            folder.delete()
        }
    }

    @Test fun corruptOrUnknownStageDoesNotBecomeAFalseCrashDiagnosis() {
        val folder = Files.createTempDirectory("herrin-stage-bad").toFile()
        val file = File(folder, "stage")
        try {
            file.writeText("not-a-time|INITIALIZER")
            assertNull(SpiderStartupTrace.read(file, 1_790_000_000_000L))
            file.writeText("1790000000000|FOREIGN_SECRET_DATA")
            assertNull(SpiderStartupTrace.read(file, 1_790_000_000_001L))
        } finally {
            file.delete()
            folder.delete()
        }
    }
}
