package tv.herrin.app

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Only this text ticks; the detail page and focus tree do not recompose on the timer. */
@Composable internal fun SourceSearchStatus(text:String,stageStart:Long,running:Boolean,requestStart:Long=0){
    var now by remember(stageStart){mutableLongStateOf(System.nanoTime())}
    LaunchedEffect(stageStart,running){while(running){now=System.nanoTime();delay(500)}}
    Text(text+(if(running &&stageStart>0)" · 本阶段 ${(now-stageStart).coerceAtLeast(0)/1_000_000}ms" else ""),
        Modifier.testTag("tmdb-source-lookup-status").drawWithContent {
            drawContent()
            SourceSearchDiagnostics.feedbackDraw(requestStart)
        },color=Color(0xFFCFD7E4),fontSize=14.sp)
}
