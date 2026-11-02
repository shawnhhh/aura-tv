package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class M468ImdbRatingsTest {
    @Test fun parsesUserSelectedOfficialTsvRowsWithoutBundlingDataset() {
        assertEquals("tt0816692" to HerrinImdbScore(8.7, 2040000),
            HerrinImdbTsv.row("tt0816692\t8.7\t2040000"))
        assertNull(HerrinImdbTsv.row("tconst\taverageRating\tnumVotes"))
        assertNull(HerrinImdbTsv.row("tt0816692\t0.0\t200"))
        assertNull(HerrinImdbTsv.row("tt0816692\t8.7\t0"))
        assertNull(HerrinImdbTsv.row("something\t8.7\t1"))
        assertNull(HerrinImdbTsv.row("tt0816692\tNaN\t1"))
    }
}
