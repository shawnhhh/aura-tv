package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Route identity includes the original VOD and line; labels never act as keys. */
internal fun LazyListScope.herrinDiscoveryItems(snapshot: HerrinSourceDiscovery.Snapshot,
    running: Boolean, reduced: Boolean, select: (HerrinSourceDiscovery.Hit, String?) -> Unit,
    pause: () -> Unit, resume: () -> Unit) {
    item(key = "source-region") {
        val groups = snapshot.hits.filter { it.relevance == SourceRelevance.CONFIRMED }.groupBy { it.node.id }
        var selected by remember(snapshot.revision) { mutableStateOf<String?>(null) }
        var expanded by remember { mutableStateOf(false) }
        val current = selected?.takeIf { it in groups } ?: groups.keys.firstOrNull()
        // Pin the first visible node. Later results must never change it.
        LaunchedEffect(current) { if (selected == null) selected = current }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("片源")
                    if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Box {
                    if (current != null) TvButton(groups[current]!!.first().node.name + " ▾", reduced = reduced) { expanded = true }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        groups.forEach { (id, hits) ->
                            DropdownMenuItem(text = { Text(hits.first().node.name) }, onClick = { selected = id; expanded = false })
                        }
                    }
                }
            }
            val routes = snapshot.routes.filter { it.nodeId == current }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(routes.withIndex().toList(), key = { it.value.hitKey + ":" + it.value.lineId }) { indexed ->
                    val route = indexed.value
                    val hit = snapshot.hits.firstOrNull { it.key == route.hitKey }
                    if (hit != null) TvButton("线路 ${indexed.index + 1}",
                        modifier = Modifier.testTag("source-line-${route.hitKey}-${route.lineId}"), reduced = reduced) {
                        select(hit, route.lineId)
                    }
                }
            }
            if (!running && routes.isEmpty()) {
                Text("暂无可用线路")
                TvButton("重试", reduced = reduced, onClick = resume)
            }
        }
    }
}
