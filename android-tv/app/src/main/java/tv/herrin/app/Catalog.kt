package tv.herrin.app

/** Network implementations can replace this fixture without changing UI or navigation. */
interface CatalogSource {
    fun titles(): List<Film>
    fun search(query: String): List<Film> = titles().filter {
        it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true)
    }
}
data class Film(val id: String, val title: String, val subtitle: String, val category: String,
                val year: String, val synopsis: String, val artwork: Int, val episodes: Int = 1,
                val artworkUrl: String? = null, val playbackEpisodes: List<PlaybackEpisode> = emptyList(),
                val spiderVodId: String? = null, val backdropUrl: String? = null,
                val cmsVodId: String? = null,
                val genres: List<String> = emptyList(), val ratingValue: Double? = null,
                val ratingProvider: String? = null, val metadataSource: String? = null,
                val originalDisplay: FilmDisplayOrigin? = null,
                val actors: List<String> = emptyList(), val directors: List<String> = emptyList(),
                val regions: List<String> = emptyList())
class DemoCatalog : CatalogSource {
    override fun titles() = listOf(
        Film("orbit", "最后的轨道", "THE LAST ORBIT", "电影", "2026", "在寂静的远方，一位航行者寻找回家的信号。原创虚构故事，合成影像仅用于界面演示。", R.drawable.photofilm_orbit),
        Film("tide", "潮汐之间", "BETWEEN TIDES", "剧集", "2025", "海边观测站的灯光再次亮起，一段关于时间与重逢的故事由此开始。演示剧集。", R.drawable.photofilm_tide, 6),
        Film("dawn", "群山回声", "ECHOES AT DAWN", "电影", "2026", "穿越群山，在黎明抵达之前寻找失落的天文台。原创虚构演示内容。", R.drawable.photofilm_dawn),
        Film("night", "星夜邮局", "LETTERS TO THE STARS", "动漫", "2025", "一封寄往星空的信，开启了小小邮递员的远行。原创虚构演示内容。", R.drawable.photofilm_night, 4),
        Film("return", "远岸来信", "THE FAR SHORE", "剧集", "2026", "来自另一片海岸的信号，连接起两个陌生人的生活。原创演示故事。", R.drawable.photofilm_return, 3),
        Film("blue", "蓝色边界", "BLUE FRONTIER", "动漫", "2026", "飞越蓝色山脊，探索未知世界的边界。原创虚构演示内容。", R.drawable.photofilm_blue, 5)
    )
}
interface FavoritesStore { fun ids(): Set<String>; fun toggle(id: String) }
/** Legal source resolvers supply URLs and headers; credentials must never be committed. */
data class PlaybackRequest(val filmId: String, val episode: Int, val line: String)
data class PlaybackMedia(val uri: String, val headers: Map<String, String> = emptyMap(),
                         val subtitles: List<AuthorizedSubtitle> = emptyList(),
                         val alternateUris: List<String> = emptyList(),
                         val allowHttpRedirects: Boolean = false)
sealed interface PlaybackResult {
    data class Ready(val media: PlaybackMedia) : PlaybackResult
    data class Unavailable(val reason: String) : PlaybackResult
}
interface PlaybackSource { suspend fun resolve(request: PlaybackRequest): PlaybackResult }
class DemoPlaybackSource : PlaybackSource {
    override suspend fun resolve(request: PlaybackRequest) = PlaybackResult.Unavailable(
        "播放演示 · 未连接视频流或 FongMi 内核。第 ${request.episode} 集 / ${request.line}")
}
