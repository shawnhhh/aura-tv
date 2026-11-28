package tv.herrin.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit correction for a personal card, opened by the remote Menu key. */
@Composable
internal fun PersonalMatchDialog(film: Film, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val api = remember { PersonalLibraryApi(context) }
    val scope = rememberCoroutineScope()
    var query by remember(film.id) { mutableStateOf(film.title) }
    var results by remember { mutableStateOf(JSONObject()) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        results = JSONObject(); failed = false
        if(query.isBlank()) return@LaunchedEffect
        delay(500)
        try { results = withContext(Dispatchers.IO) { api.handle("/personal/search", JSONObject().put("query",query)) } }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { failed = true; HerrinPlaybackDiagnosticJournal.record("PERSONAL", "MANUAL_SEARCH_FAILED") }
    }
    LaunchedEffect(failed) { if(failed) { delay(2400); failed = false } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择影片资料") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(failed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("失败") }
                TvInputField(query, { query = it.take(200) }, singleLine = true,
                    label = { Text("片名") }, modifier = Modifier.testTag("personal-match-query"))
                val matches = results.optJSONArray("matches")
                if(matches != null) for(i in 0 until matches.length()) {
                    val choice = matches.getJSONObject(i)
                    TvButton(choice.getString("title") + " · " + choice.optString("year")) {
                        if(!saving) { saving = true; scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    api.handle("/personal/match", JSONObject(choice.toString())
                                        .put("id",film.id.removePrefix("personal-index:")))
                                }
                                onDismiss()
                            } catch(cancel: CancellationException) { throw cancel }
                            catch(_: Exception) { failed = true }
                            finally { saving = false }
                        } }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
