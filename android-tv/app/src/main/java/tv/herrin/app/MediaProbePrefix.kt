package tv.herrin.app

/** Only conclusive byte signatures permit early completion; MIME alone is insufficient. */
internal object MediaProbePrefix {
    fun identified(bytes: ByteArray, size: Int): Boolean {
        if (size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) return true
        if (size >= 4 && bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte()) return true
        // Two MPEG-TS sync bytes at the actual packet interval, never one arbitrary 0x47.
        if (size >= 189 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) return true
        val text = bytes.copyOf(size).toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
        return text.startsWith("#EXTM3U\n") || text.startsWith("#EXTM3U\r\n") ||
            Regex("<MPD(?:\\s|>)").containsMatchIn(text)
    }
}
