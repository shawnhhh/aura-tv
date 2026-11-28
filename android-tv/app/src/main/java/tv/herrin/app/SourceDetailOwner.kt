package tv.herrin.app

/** A returned source detail may bind only the still-current film, selection and authorization. */
internal data class SourceDetailOwner(
    val film: String, val selection: Int, val sourceEpoch: Int, val consent: Long, val revision: String
) {
    fun accepts(currentFilm: String, screen: String, foreground: Boolean, currentSelection: Int,
                currentSourceEpoch: Int, currentConsent: Long, currentRevision: String?): Boolean =
        currentFilm == film && screen in setOf("detail","play-intent") && foreground &&
            currentSelection == selection && currentSourceEpoch == sourceEpoch &&
            currentConsent == consent && currentRevision == revision
}
