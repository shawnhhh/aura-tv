package tv.herrin.app

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

/** Lazy provider groups retain every hit without mounting thousands of TV buttons at once. */
internal fun LazyListScope.herrinDiscoveryItems(snapshot: HerrinSourceDiscovery.Snapshot,
    running: Boolean, reduced: Boolean, select: (HerrinSourceDiscovery.Hit) -> Unit,
    pause: () -> Unit, resume: () -> Unit) {
    snapshot.hits.groupBy { it.node.id }.forEach { (id, hits) ->
        item(key = "discovery-provider-$id") {
            Text(hits.first().node.name + " · ${hits.size} 个真实搜索条目", fontSize = 18.sp)
        }
        items(hits, key = { "discovery-hit-" + it.key }) { hit ->
            TvButton(hit.movie.film.title + " · " + hit.movie.film.subtitle,
                modifier = Modifier.testTag("source-hit-" + hit.key), reduced = reduced) { select(hit) }
        }
    }
    if(running) item(key = "discovery-pause") {
        TvButton("暂停站点搜索（保留结果）", modifier = Modifier.testTag("source-discovery-pause"), reduced = reduced, onClick = pause)
    }
    if(snapshot.hasRemaining && !running) item(key = "discovery-resume") {
        TvButton("继续未完成站点（保留结果）", modifier = Modifier.testTag("source-discovery-resume"), reduced = reduced, onClick = resume)
    }
}
