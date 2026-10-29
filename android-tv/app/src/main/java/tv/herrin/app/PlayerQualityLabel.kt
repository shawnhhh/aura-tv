package tv.herrin.app

/** Video size is from Media3's selected/decoded track, never a filename claim. */
internal object PlayerQualityLabel {
    fun fromSize(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "清晰度识别中"
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return when {
            longEdge >= 3840 && shortEdge >= 1600 -> "4K"
            longEdge >= 2560 && shortEdge >= 1350 -> "1440p"
            longEdge >= 1920 && shortEdge >= 900 -> "1080p"
            longEdge >= 1280 && shortEdge >= 650 -> "720p"
            else -> "${shortEdge}p"
        }
    }
}
