package tv.herrin.app

/** Map third-party TVBox category names to HERRIN's fixed top-level tabs. */
internal object TvBoxCategories {
    fun topLevel(raw: String): String {
        val name = raw.trim().lowercase()
        return when {
            listOf("纪录", "纪录片", "documentary").any { it in name } -> "纪录片"
            listOf("综艺", "真人秀", "脱口秀", "variety", "reality").any { it in name } -> "综艺"
            listOf("动漫", "动画", "卡通", "番剧", "anime", "animation").any { it in name } -> "动漫"
            listOf("电视剧", "连续剧", "剧集", "短剧", "国产剧", "美剧", "韩剧", "日剧", "泰剧", "tv series", "drama").any { it in name } -> "剧集"
            else -> "电影"
        }
    }
}
