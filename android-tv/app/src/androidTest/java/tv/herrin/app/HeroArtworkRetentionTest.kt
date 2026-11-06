package tv.herrin.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Controlled animation frames, no sleeps, network images or deliberate OOM. */
class HeroArtworkRetentionTest {
    @get:Rule val compose = createComposeRule()
    private fun count(prefix: String) = compose.onAllNodes(SemanticsMatcher("layer prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }, useUnmergedTree=true).fetchSemanticsNodes().size

    @Test fun interruptedFrameworkCrossfadeAccumulatesButProductionHeroKeepsAtMostTwoLayers() {
        var selected by mutableStateOf(0)
        val legacyOwners = mutableSetOf<Int>()
        compose.setContent {
            MaterialTheme { Row {
                // Exact framework primitive used by 67, isolated as a harmless negative fixture.
                Crossfade(selected, animationSpec=tween(RemoteMotionPolicy.HERO_CROSSFADE_MS),label="legacy") {
                    val id = it
                    DisposableEffect(id) {
                        legacyOwners.add(id)
                        onDispose { legacyOwners.remove(id) }
                    }
                    Box(Modifier.size(20.dp).testTag("legacy-layer-$id"))
                }
                HeroArtworkTransition(Film("original-$selected","Original $selected","","电影","2026","",R.drawable.photofilm_orbit),
                    false,Modifier.size(160.dp))
            } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        try {
            for (index in 1..8) {
                compose.runOnUiThread { selected=index }
                compose.mainClock.advanceTimeBy(32)
                assertTrue("Artwork owners grew while remote input interrupted the fade",count("hero-art-")<=2)
            }
            compose.runOnIdle {
                assertTrue("Negative fixture must reproduce retained composition owners",legacyOwners.size>2)
            }
            compose.mainClock.advanceTimeBy(RemoteMotionPolicy.HERO_CROSSFADE_MS+64L)
            assertEquals(1,count("hero-art-"))
        } finally { compose.mainClock.autoAdvance=true }
    }

    @Test fun reducedMotionKeepsOnlyTheCurrentArtworkOwner() {
        var selected by mutableStateOf(0)
        compose.setContent {
            MaterialTheme { HeroArtworkTransition(
                Film("reduced-$selected","Original","","电影","2026","",R.drawable.photofilm_orbit),true,Modifier.size(160.dp)) }
        }
        for (index in 1..5) {
            compose.runOnIdle { selected=index }
            compose.waitForIdle()
            assertEquals(1,count("hero-art-"))
            compose.onNodeWithTag("hero-art-reduced-$index",useUnmergedTree=true).assertExists()
        }
    }
}
