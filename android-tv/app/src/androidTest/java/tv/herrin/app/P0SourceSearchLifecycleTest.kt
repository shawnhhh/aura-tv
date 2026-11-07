package tv.herrin.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class P0SourceSearchLifecycleTest {
    @get:Rule val compose=createComposeRule()
    @Test fun stopCancelsImmediatelyAndCoalescedResumeStartsOneFreshSearch() {
        val owner=object:LifecycleOwner {
            val registry=LifecycleRegistry(this)
            override val lifecycle:Lifecycle get()=registry
        }
        val state=TmdbScreenState()
        val starts=AtomicInteger();val active=AtomicInteger()
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SourceSearchLifecycle(state)
                val foreground=state.sourceForeground
                LaunchedEffect(foreground,state.tmdbLookupEpoch) {
                    if(foreground) {
                        state.sourceSearchJob=currentCoroutineContext()[Job]
                        starts.incrementAndGet();active.incrementAndGet()
                        try{awaitCancellation()}finally{active.decrementAndGet()}
                    }
                }
            }
        }
        compose.waitUntil(3000){active.get()==1}
        val ticket=state.tmdbSearchGeneration.get()
        compose.runOnUiThread {
            val previous=state.sourceSearchJob!!
            owner.registry.currentState=Lifecycle.State.CREATED
            assertTrue("Stop must cancel without waiting for recomposition",previous.isCancelled)
            assertTrue(state.tmdbSearchGeneration.get()>ticket)
            // Deliberately no frame between Stop and Start.
            owner.registry.currentState=Lifecycle.State.STARTED
        }
        compose.waitUntil(3000){starts.get()==2 &&active.get()==1}
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.waitUntil(3000){active.get()==0}
        assertEquals(2,starts.get())
    }
}
