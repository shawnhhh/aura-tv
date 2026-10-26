package tv.herrin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogProfilesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migratesExistingSingleCatalogAndSupportsSourceSwitching() {
        val prefs = context.getSharedPreferences("herrin", 0)
        prefs.edit().clear().putString("catalog_url", "https://legacy.example.org/feed")
            .putStringSet("favorites", setOf("old-film")).commit()
        val profiles = CatalogProfiles(context)
        assertEquals(1, profiles.list().size)
        assertEquals("legacy", profiles.activeId())
        assertEquals("https://legacy.example.org/feed", Preferences(context).catalogUrl)
        assertTrue("old-film" in Preferences(context).idsFor("https://legacy.example.org/feed"))
        assertTrue("old-film" in Preferences(context).ids())

        val result = profiles.addAndActivate("第二目录", "https://another.example.org/list")
        assertTrue(result is SaveProfileResult.Saved)
        assertEquals(2, profiles.list().size)
        assertEquals("https://another.example.org/list", Preferences(context).catalogUrl)
        assertTrue(profiles.activate("legacy"))
        assertEquals("https://legacy.example.org/feed", Preferences(context).catalogUrl)
        assertTrue(profiles.activate(null))
        assertNull(profiles.activeId())
        assertEquals("", Preferences(context).catalogUrl)
        assertEquals(2, profiles.list().size)
    }

    @Test fun duplicateInvalidAndMaximumEntriesDoNotCorruptActiveSelection() {
        val prefs = context.getSharedPreferences("herrin", 0)
        prefs.edit().clear().commit()
        val profiles = CatalogProfiles(context)
        assertTrue(profiles.addAndActivate("一号", "https://source1.example.org/list") is SaveProfileResult.Saved)
        val originalId = profiles.activeId()
        val duplicate = profiles.addAndActivate("重复", "https://source1.example.org/list")
        assertTrue(duplicate is SaveProfileResult.Saved && duplicate.existing)
        assertEquals(originalId, profiles.activeId())
        assertEquals(1, profiles.list().size)
        assertTrue(profiles.addAndActivate("不合法", "http://untrusted.example.org/") is SaveProfileResult.Error)
        assertEquals(originalId, profiles.activeId())
        for (i in 2..10) {
            assertTrue(profiles.addAndActivate("目录$i", "https://source$i.example.org/list") is SaveProfileResult.Saved)
        }
        assertEquals(10, profiles.list().size)
        assertTrue(profiles.addAndActivate("超额", "https://source11.example.org/list") is SaveProfileResult.Error)
        assertEquals(10, CatalogProfiles(context).list().size)
    }

    @Test fun favoritesStayWithinTheCurrentCatalogScope() {
        context.getSharedPreferences("herrin", 0).edit().clear().commit()
        val prefs = Preferences(context)
        val one = "https://source1.example.org/list"
        val two = "https://source2.example.org/list"
        prefs.toggleFor(one, "shared-id")
        assertTrue("shared-id" in prefs.idsFor(one))
        assertFalse("shared-id" in prefs.idsFor(two))
        assertFalse("shared-id" in prefs.ids())
        prefs.toggle("demo-film")
        assertTrue("demo-film" in prefs.ids())
        assertFalse("demo-film" in prefs.idsFor(one))
    }

    @Test fun editingTheActiveSourceUpdatesItsUrlButRejectsDuplicateAndInvalidEdits() {
        context.getSharedPreferences("herrin", 0).edit().clear().commit()
        val store = CatalogProfiles(context)
        val first = store.addAndActivate("片源 A", "https://catalog-a.example.org/feed") as SaveProfileResult.Saved
        store.addAndActivate("片源 B", "https://catalog-b.example.org/feed")
        assertTrue(store.activate(first.profile.id))

        assertTrue(store.update(first.profile.id, "重命名 A", "http://invalid.example.org") is SaveProfileResult.Error)
        assertTrue(store.update(first.profile.id, "重命名 A", "https://catalog-b.example.org/feed") is SaveProfileResult.Error)
        assertEquals("片源 A", store.list().first().name)
        assertEquals("https://catalog-a.example.org/feed", Preferences(context).catalogUrl)

        assertTrue(store.update(first.profile.id, "重命名 A", "https://catalog-a2.example.org/feed") is SaveProfileResult.Saved)
        assertEquals("重命名 A", store.list().first().name)
        assertEquals("https://catalog-a2.example.org/feed", Preferences(context).catalogUrl)
        assertEquals(first.profile.id, store.activeId())
    }

    @Test fun deletingActiveSourceFallsBackToDemoWithoutTouchingOtherSourcesOrFavorites() {
        context.getSharedPreferences("herrin", 0).edit().clear().commit()
        val store = CatalogProfiles(context)
        val a = store.addAndActivate("A", "https://a.example.org/list") as SaveProfileResult.Saved
        val b = store.addAndActivate("B", "https://b.example.org/list") as SaveProfileResult.Saved
        Preferences(context).toggleFor("https://b.example.org/list", "film-b")
        assertFalse(store.delete("missing"))
        assertTrue(store.delete(b.profile.id))
        assertNull(store.activeId())
        assertEquals("", Preferences(context).catalogUrl)
        assertEquals(listOf(a.profile.id), store.list().map { it.id })
        assertTrue("film-b" in Preferences(context).idsFor("https://b.example.org/list"))
    }

    @Test fun jsonExportImportMergesWithoutOverwritingAndRejectsInvalidBatchesAtomically() {
        context.getSharedPreferences("herrin", 0).edit().clear().commit()
        val store = CatalogProfiles(context)
        val original = store.addAndActivate("原始", "https://a.example.org/feed") as SaveProfileResult.Saved
        val snapshot = store.exportJson()
        assertEquals(1, CatalogProfileCodec.decode(snapshot).size)
        val merge = CatalogProfileCodec.encode(listOf(
            CatalogProfile("external1", "重复目录", "https://a.example.org/feed"),
            CatalogProfile("external2", "新增目录", "https://b.example.org/feed")
        ))
        assertEquals(ImportProfilesResult.Imported(1, 1), store.importJson(merge))
        assertEquals(2, store.list().size)
        assertEquals(original.profile.id, store.activeId())
        assertEquals("原始", store.list().first().name)
        assertEquals(ImportProfilesResult.Imported(0, 1), store.importJson(snapshot))

        val before = store.exportJson()
        assertTrue(store.importJson("{bad json}") is ImportProfilesResult.Error)
        assertEquals(before, store.exportJson())
        val oversized = CatalogProfileCodec.encode((1..9).map {
            CatalogProfile("p$it", "新增$it", "https://new$it.example.org/feed")
        })
        assertTrue(store.importJson(oversized) is ImportProfilesResult.Error)
        assertEquals(before, store.exportJson())
    }
}
