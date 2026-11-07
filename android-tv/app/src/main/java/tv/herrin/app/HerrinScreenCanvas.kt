package tv.herrin.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Keep the existing Box/layout, but compile its content outside the huge
 * state/effect initialization method rather than inlining it into Herrin(). */
@Composable
internal fun HerrinScreenCanvas(content:@Composable BoxScope.()->Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10)),content=content)
}

// The failing DEX passed the live Composer parameter to mutableIntStateOf(0).
// Small non-inline factories avoid that high-register constant argument; they
// preserve exactly the same primitive snapshot states and initial values.
internal fun zeroIntState():MutableIntState=mutableIntStateOf(0)
internal fun zeroLongState():MutableLongState=mutableLongStateOf(0L)
internal fun zeroFloatState():MutableFloatState=mutableFloatStateOf(0f)
