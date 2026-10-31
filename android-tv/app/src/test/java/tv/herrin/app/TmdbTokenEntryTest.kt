package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class TmdbTokenEntryTest {
    @Test fun splitPartsNeedNotBeFull200Characters() {
        val token = "A".repeat(137) + "B".repeat(69) + "C".repeat(63)
        assertEquals(token, TmdbTokenEntry.assemble("",
            listOf(token.take(137), token.substring(137, 206), token.drop(206)), true))
        assertEquals(token, TmdbTokenEntry.assemble(token, listOf("", "", ""), false))
        assertEquals(token, TmdbTokenEntry.assemble("", TmdbTokenEntry.split(token), true))
    }

    @Test fun emptyFirstGapOversizeAndInvalidCharactersRejectWithoutSaving() {
        val good = "A".repeat(100)
        assertNull(TmdbTokenEntry.assemble("", listOf("", good, ""), true))
        assertNull(TmdbTokenEntry.assemble("", listOf(good, "", good), true))
        assertNull(TmdbTokenEntry.assemble("", listOf("A".repeat(201), "", ""), true))
        assertNull(TmdbTokenEntry.assemble("", listOf("A".repeat(80), " ", "B".repeat(80)), true))
        assertNull(TmdbTokenEntry.assemble("", listOf("A".repeat(80), "\\nB", ""), true))
        assertNull(TmdbTokenEntry.assemble("short", listOf("", "", ""), false))
    }
}
