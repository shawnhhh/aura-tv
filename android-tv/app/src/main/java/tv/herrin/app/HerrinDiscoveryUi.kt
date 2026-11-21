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

/** No node is offered until all of its confirmed details have finished. */
internal fun LazyListScope.herrinDiscoveryItems(
    snapshot: HerrinSourceDiscovery.Snapshot,
    running: Boolean, reduced: Boolean,
    select: (HerrinSourceDiscovery.Hit, String?) -> Unit,
    resume: (String?) -> Unit, selectedNode: String?, onNode: (String) -> Unit,
    lineFocus: (Int) -> FocusRequester,
    onLineFocus: (Int) -> Unit, onDown: () -> Unit
) {
    item(key = "source-region") {
        val groups = snapshot.hits.filter {
            it.relevance == SourceRelevance.CONFIRMED &&
                it.node.id in snapshot.selectableNodeIds
        }.groupBy { it.node.id }
        var expanded by remember { mutableStateOf(false) }
        val current = selectedNode?.takeIf { id -> id in groups || snapshot.routes.any { it.nodeId == id } || snapshot.hits.any {
            it.node.id == id && it.key in snapshot.failedDetails
        } }
            ?: snapshot.readyRoutes.firstOrNull { it.nodeId in groups }?.nodeId
        // Keep an explicitly failed selection for retry; a name-only hit cannot steal focus.
        LaunchedEffect(current) {
            if (current != null && current != selectedNode) onNode(current)
        }
        Column(
            Modifier.testTag("source-region").drawWithContent {
                drawContent()
                SourceSearchDiagnostics.feedbackDraw(SourceSearchDiagnostics.currentStart())
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Selector replaces the old 片源 heading; spinner stays immediately beside it.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.isNotEmpty()) {
                    Box {
                        TvButton(
                            (snapshot.hits.firstOrNull { it.node.id == current }?.node?.name ?: "选择节点") + " ▾",
                            modifier = Modifier.testTag("source-node-menu"),
                            reduced = reduced
                        ) { expanded = true }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            groups.forEach { (id, hits) ->
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("source-node-$id"),
                                    text = { Text(hits.first().node.name) },
                                    onClick = {
                                        onNode(id)
                                        expanded = false
                                        val route = snapshot.readyRoutes.firstOrNull { it.nodeId == id }
                                        val hit = route?.let { r ->
                                            snapshot.hits.firstOrNull { it.key == r.hitKey }
                                        } ?: hits.firstOrNull { it.episodes.isNotEmpty() }
                                        if (hit != null) select(hit, route?.lineId)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(if (running) "正在准备节点线路与集数…"
                        else "暂无已就绪的影视节点",
                        modifier = Modifier.testTag("source-node-wait"))
                }
                if (running) CircularProgressIndicator(
                    Modifier.size(18.dp).testTag("source-spinner"), strokeWidth = 2.dp
                )
            }
            snapshot.hits.filter { it.key in snapshot.failedDetails }.distinctBy { it.node.id }.forEach { hit ->
                TvButton("${hit.node.name} · 重试", reduced = reduced,
                    modifier = Modifier.testTag("source-retry-${hit.node.id}")) { resume(hit.node.id) }
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
            if (!running && routes.isEmpty()) {
                Text("暂无可用线路")
                TvButton("重试", modifier = Modifier.focusRequester(lineFocus(0)),
                    reduced = reduced, onClick = { resume(null) })
            }
        }
    }
}
