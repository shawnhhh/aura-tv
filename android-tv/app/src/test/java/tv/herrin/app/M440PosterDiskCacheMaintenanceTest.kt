package tv.herrin.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M440PosterDiskCacheMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun image(folder: File, name: String, size: Int, age: Long): File =
        File(folder, "$name.img").apply {
            writeBytes(ByteArray(size) { 7 })
            assertTrue(setLastModified(age))
        }

    @Test fun batchPolicyDoesNotEnumerateOnEveryImage() {
        assertTrue(PosterDiskCacheMaintenance.shouldScan(false, 1))
        for (n in 1 until PosterDiskCacheMaintenance.SCAN_EVERY_WRITES) {
            assertFalse("Scan at write $n", PosterDiskCacheMaintenance.shouldScan(true, n))
        }
        assertTrue(PosterDiskCacheMaintenance.shouldScan(
            true, PosterDiskCacheMaintenance.SCAN_EVERY_WRITES))
    }

    @Test fun removesOnlyOldestImageFilesAndNeverTouchesOtherAppData() {
        val folder = temporary.newFolder()
        val first = image(folder, "01", 2, 1_000)
        val second = image(folder, "02", 2, 2_000)
        val third = image(folder, "03", 2, 3_000)
        val fourth = image(folder, "04", 2, 4_000)
        val newest = image(folder, "05", 2, 5_000)
        val unrelated = File(folder, "watch-history.json").apply { writeText("keep") }
        PosterDiskCacheMaintenance.prune(folder, protectedFile = newest,
            maxFiles = 3, maxBytes = 100)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue(third.exists())
        assertTrue(fourth.exists())
        assertTrue(newest.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun enforcesByteBudgetEvenWhenFileCountIsUnderLimit() {
        val folder = temporary.newFolder()
        val oldest = image(folder, "old", 4, 1_000)
        val middle = image(folder, "mid", 4, 2_000)
        val newest = image(folder, "new", 4, 3_000)
        PosterDiskCacheMaintenance.prune(folder, protectedFile = newest,
            maxFiles = 10, maxBytes = 8)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
        assertEquals(8L, folder.listFiles()!!.sumOf { it.length() })
    }

    @Test fun protectsJustWrittenFileWhenTimestampsTie() {
        val folder = temporary.newFolder()
        val fresh = image(folder, "a-fresh", 2, 10_000)
        image(folder, "b-old", 2, 10_000)
        image(folder, "c-old", 2, 10_000)
        PosterDiskCacheMaintenance.prune(folder, protectedFile = fresh,
            maxFiles = 1, maxBytes = 2)
        assertTrue(fresh.exists())
        assertEquals(listOf("a-fresh.img"), folder.listFiles()!!.map { it.name })
    }
}
