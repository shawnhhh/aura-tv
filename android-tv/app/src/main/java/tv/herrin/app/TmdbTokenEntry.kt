package tv.herrin.app

/**
 * TV remote / phone-to-TV keyboard apps often cap one paste at 200 characters.
 * Keep up to three in-memory, password-masked fragments; only the assembled
 * value is persisted by the explicit Save action. Never log or export fragments.
 */
internal object TmdbTokenEntry {
    const val PART_LIMIT = 200
    const val MAX_LENGTH = PART_LIMIT * 3
    private val validToken = Regex("^[A-Za-z0-9._~-]{40,$MAX_LENGTH}$")

    fun assemble(single: String, pieces: List<String>, split: Boolean): String? {
        val token = if (!split) single.trim() else {
            if (pieces.size != 3 || pieces.any { it.length > PART_LIMIT }) return null
            val trimmed = pieces.map { it.trim() }
            if (trimmed[0].isEmpty() || (trimmed[1].isEmpty() && trimmed[2].isNotEmpty()))
                return null
            trimmed.joinToString("")
        }
        return token.takeIf { validToken.matches(it) }
    }

    /** Switch between full and segmented entry without silently losing chars. */
    fun split(value: String): List<String> {
        require(value.length <= MAX_LENGTH)
        return listOf(
            value.take(PART_LIMIT),
            value.drop(PART_LIMIT).take(PART_LIMIT),
            value.drop(2 * PART_LIMIT).take(PART_LIMIT)
        )
    }
}
