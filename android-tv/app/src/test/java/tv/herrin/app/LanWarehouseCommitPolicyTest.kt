package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class LanWarehouseCommitPolicyTest {
    @Test fun anEmptySingleWarehouseCanBeCommittedWithoutSelectingOrExecutingASpider() {
        val parsed = TvBoxConfig.Single(
            listOf(TvBoxSite("待电视授权的节点", needsAdapter = true, spiderApi = "csp_AuthorizedFixture")),
            emptyList(), hasExternalEngine = true)
        assertTrue(LanWarehouseCommitPolicy.isParsedTvBox(parsed))
        assertFalse(TvBoxSpiderPolicy.mayRun(false, "https://new.invalid/tv", "new.jar",
            "https://old.invalid/tv", "old.jar"))
    }

    @Test fun multipleWarehousesCanBeImportedWithoutProbingAnyNode() {
        assertTrue(LanWarehouseCommitPolicy.isParsedTvBox(
            TvBoxConfig.Warehouses(listOf(TvBoxWarehouse("仓库甲","https://example.invalid/a")), ignored=0)))
        assertFalse(LanWarehouseCommitPolicy.isParsedTvBox(
            TvBoxConfig.Warehouses(emptyList(), ignored=0)))
        assertFalse(LanWarehouseCommitPolicy.isParsedTvBox(
            TvBoxConfig.Single(emptyList(), emptyList(), hasExternalEngine = false)))
    }

    @Test fun invalidOrOtherCatalogDocumentCannotOverwriteExistingTvBoxRoot() {
        assertFalse(LanWarehouseCommitPolicy.isParsedTvBox(TvBoxConfig.Error("INVALID")))
        assertFalse(LanWarehouseCommitPolicy.isParsedTvBox(TvBoxConfig.HerrinCatalog("https://example.invalid", 8)))
    }
}
