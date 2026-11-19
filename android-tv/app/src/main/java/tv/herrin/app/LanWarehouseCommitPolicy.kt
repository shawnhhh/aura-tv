package tv.herrin.app

/**
 * A trusted configuration is committed when its warehouse syntax was parsed,
 * not when an arbitrary CMS/Spider node returns a movie. Spider execution
 * remains subject to separate on-TV authorization.
 */
internal object LanWarehouseCommitPolicy {
    fun isParsedTvBox(config: TvBoxConfig): Boolean =
        config is TvBoxConfig.Single || config is TvBoxConfig.Warehouses
}
