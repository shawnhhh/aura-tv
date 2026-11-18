package tv.herrin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

/** Only an explicit play intent mounts this effect. Focused posters never discover media. */
@Composable
internal fun OneTapDeadline(screen: String, film: String, onTimeout: () -> Unit) {
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(screen, film) {
        if (screen == "play-intent") { delay(20_000); timeout() }
    }
}

@Composable
internal fun OneTapLoading(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().testTag("one-tap-loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
