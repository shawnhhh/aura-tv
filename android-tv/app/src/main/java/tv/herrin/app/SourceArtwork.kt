package tv.herrin.app

import org.json.JSONObject

/** Prefer a separately provided cinematic still over stretching a small portrait poster. */
internal object SourceArtwork {
    private val fields = listOf("vod_pic_slide", "vod_pic_bg", "vod_backdrop",
        "vod_pic_fanart", "vod_pic_bj", "backdrop", "fanart")

    fun backdrop(item: JSONObject, allowHttp: Boolean): String? = fields.firstNotNullOfOrNull { field ->
        TvBoxUrl.normalize(item.optString(field), allowHttp)
    }
}
