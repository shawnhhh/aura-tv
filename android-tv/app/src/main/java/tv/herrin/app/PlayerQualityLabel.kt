package tv.herrin.app

/** Video size is from Media3's selected/decoded track, never a filename claim. */
internal object PlayerQualityLabel {
    /** Decoder dimensions plus its input Format. These are stream facts, not panel output claims. */
    fun fromDecoded(width: Int, height: Int, frameRate: Float, mime: String?,
                    colorTransfer: Int, renderedFrames: Int): String {
        if (renderedFrames <= 0 || width <= 0 || height <= 0) return "清晰度识别中"
        val fps = if (frameRate.isFinite() && frameRate > 0f && frameRate <= 240f)
            java.math.BigDecimal(frameRate.toString()).setScale(3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + " fps"
            else "帧率未提供"
        val range = when {
            mime == "video/dolby-vision" -> "Dolby Vision 片源（输出待确认）"
            colorTransfer == 6 -> "HDR PQ 片源（输出待确认）"
            colorTransfer == 7 -> "HDR HLG 片源（输出待确认）"
            colorTransfer == 3 -> "SDR"
            else -> "HDR 信息未提供"
        }
        return "${fromSize(width, height)} · ${width}×${height} · $fps · $range"
    }

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
