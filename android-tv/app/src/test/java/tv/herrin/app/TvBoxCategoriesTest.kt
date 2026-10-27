package tv.herrin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TvBoxCategoriesTest {
    @Test fun mapsProviderNamesToStableMovieSeriesAndAnimationTabs() {
        assertEquals("电影", TvBoxCategories.topLevel("动作电影"))
        assertEquals("电影", TvBoxCategories.topLevel("综合影视"))
        assertEquals("剧集", TvBoxCategories.topLevel("国产剧"))
        assertEquals("剧集", TvBoxCategories.topLevel("韩剧"))
        assertEquals("动漫", TvBoxCategories.topLevel("日本动漫"))
        assertEquals("动漫", TvBoxCategories.topLevel("Anime"))
    }
}
