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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Show a node as soon as it has a confirmed route with an episode layout. */
internal fun LazyListScope.herrinDiscoveryItems(
    snapshot: HerrinSourceDiscovery.Snapshot,
    running: Boolean, reduced: Boolean,
    select: (HerrinSourceDiscovery.Hit, String?) -> Unit,
    resume: (String?) -> Unit, selectedNode: String?, onNode: (String) -> Unit,
    lineFocus: (Int) -> FocusRequester,
    onLineFocus: (Int) -> Unit, onDown: () -> Unit
) {
    item(key = "source-region") {
        val groups = remember(snapshot.hits, snapshot.selectableNodeIds) {
            snapshot.hits.filter {
                it.relevance == SourceRelevance.CONFIRMED && it.node.id in snapshot.selectableNodeIds
            }.groupBy { it.node.id }
        }
        var expanded by remember(snapshot.revision) { mutableStateOf(false) }
        val current = selectedNode?.takeIf(groups::containsKey)
            ?: snapshot.readyRoutes.firstOrNull { it.nodeId in groups }?.nodeId
        Column(
            Modifier.testTag("source-region").drawWithContent {
                drawContent()
                SourceSearchDiagnostics.feedbackDraw(SourceSearchDiagnostics.currentStart())
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box {
                    TvButton(
                        (current?.let(groups::get)?.firstOrNull()?.node?.name ?: "节点") + " ▾",
                        modifier = Modifier.testTag(if (groups.isEmpty()) "source-node-wait" else "source-node-menu"),
                        reduced = reduced
                    ) {
                        if (groups.isNotEmpty()) expanded = true
                        else if (!running) resume(null)
                    }
                    DropdownMenu(expanded = expanded && groups.isNotEmpty(), onDismissRequest = { expanded = false }) {
                        groups.forEach { (id, hits) ->
                            DropdownMenuItem(
                                modifier = Modifier.testTag("source-node-$id"),
                                text = { Text(hits.first().node.name) },
                                onClick = {
                                    onNode(id)
                                    expanded = false
                                    val route = snapshot.readyRoutes.firstOrNull { it.nodeId == id }
                                    val hit = route?.let { r -> hits.firstOrNull { it.key == r.hitKey } }
                                    if (hit != null && route != null) select(hit, route.lineId)
                                }
                            )
                        }
                        if (!running) DropdownMenuItem(
                            modifier = Modifier.testTag("source-node-search-again"),
                            text = { Text("重新搜索") },
                            onClick = { expanded = false; resume(null) }
                        )
                    }
                }
                if (running) CircularProgressIndicator(
                    Modifier.size(18.dp).testTag("source-spinner"), strokeWidth = 2.dp
                )
            }
            val available = snapshot.readyRoutes.toSet()
            val routes = snapshot.routes.filter { it.nodeId == current }
                .withIndex().filter { it.value in available }
            LazyRow(
                modifier = Modifier.testTag("source-lines")
                    .drawWithContent {
                        drawContent()
                        if (routes.isNotEmpty()) DetailPlaybackMetrics.timeline.linesDrawn()
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(routes, key = {
                    "${it.value.hitKey.length}:${it.value.hitKey}|${it.value.lineId.length}:${it.value.lineId}"
                }) { indexed ->
                    val route = indexed.value
                    val hit = snapshot.hits.firstOrNull { it.key == route.hitKey }
                    if (hit != null) TvButton(
                        "线路 ${indexed.index + 1}",
                        modifier = Modifier
                            .testTag("source-line-${route.hitKey}-${route.lineId}")
                            .focusRequester(lineFocus(indexed.index))
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    onDown()
                                    true
                                } else false
                            },
                        reduced = reduced,
                        onFocus = { onLineFocus(indexed.index) }
                    ) { select(hit, route.lineId) }
                }
            }
        }
    }
}
