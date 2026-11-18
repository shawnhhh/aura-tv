package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Route identity includes the original VOD and line; labels never act as keys. */
internal fun LazyListScope.herrinDiscoveryItems(snapshot: HerrinSourceDiscovery.Snapshot,
    running: Boolean, reduced: Boolean, select: (HerrinSourceDiscovery.Hit, String?) -> Unit,
    pause: () -> Unit, resume: () -> Unit, selectedNode: String?, onNode: (String) -> Unit,
    firstLineFocus: FocusRequester) {
    item(key = "source-region") {
        val groups = snapshot.hits.filter { it.relevance == SourceRelevance.CONFIRMED && it.episodes.isNotEmpty() }.groupBy { it.node.id }
        var expanded by remember { mutableStateOf(false) }
        val current = selectedNode?.takeIf { it in groups } ?: groups.keys.firstOrNull()
        // Pin the first visible node. Later results must never change it.
        LaunchedEffect(current) { if (selectedNode == null && current != null) onNode(current) }
        Column(Modifier.testTag("source-region").drawWithContent { drawContent(); SourceSearchDiagnostics.feedbackDraw(SourceSearchDiagnostics.currentStart()) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("片源")
                    if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Box {
                    if (current != null) TvButton(groups[current]!!.first().node.name + " ▾", modifier = Modifier.testTag("source-node-menu"), reduced = reduced) { expanded = true }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        groups.forEach { (id, hits) ->
                            DropdownMenuItem(modifier = Modifier.testTag("source-node-$id"), text = { Text(hits.first().node.name) }, onClick = {
                                onNode(id); expanded = false
                                snapshot.routes.firstOrNull { it.nodeId == id }?.let { route ->
                                    snapshot.hits.firstOrNull { it.key == route.hitKey }?.let { select(it, route.lineId) }
                                }
                            })
                        }
                    }
                }
            }
            val routes = snapshot.routes.filter { it.nodeId == current }
            LazyRow(modifier = Modifier.drawWithContent { drawContent(); if (routes.isNotEmpty()) DetailPlaybackMetrics.timeline.linesDrawn() }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(routes.withIndex().toList(), key = { "${it.value.hitKey.length}:${it.value.hitKey}|${it.value.lineId.length}:${it.value.lineId}" }) { indexed ->
                    val route = indexed.value
                    val hit = snapshot.hits.firstOrNull { it.key == route.hitKey }
                    if (hit != null) TvButton("线路 ${indexed.index + 1}",
                        modifier = Modifier.testTag("source-line-${route.hitKey}-${route.lineId}").then(if (indexed.index == 0) Modifier.focusRequester(firstLineFocus) else Modifier), reduced = reduced) {
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
