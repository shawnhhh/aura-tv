package tv.herrin.app

/** Internal dependency seam for controlled instrumentation; no URL/Intent or UI activation path. */
internal object MetadataFetchers {
    @Volatile var overrideForTests: ArtworkJsonFetcher? = null
    val current: ArtworkJsonFetcher get() = overrideForTests ?: NetworkArtworkFetcher
}
