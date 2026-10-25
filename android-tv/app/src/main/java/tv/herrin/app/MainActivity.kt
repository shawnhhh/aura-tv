package tv.herrin.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080B10), contentColor = MaterialTheme.colorScheme.onBackground) { Herrin() }
            }
        }
    }
}

/** Native focusable controls work with D-pad/Enter; no pointer-only actions. */
@Composable
private fun TvButton(label: String, modifier: Modifier = Modifier, reduced: Boolean = false,
                     selected: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.055f else 1f, tween(if (reduced) 0 else 160), label = "focus")
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape).background(if (focused || selected) Color.White else Color(0x99343840))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (focused || selected) Color(0xFF111318) else Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Herrin() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember { Preferences(context) }
    val catalog: CatalogSource = remember { DemoCatalog() }
    val films = remember { catalog.titles() }
    var category by rememberSaveable { mutableStateOf("首页") }
    var screen by rememberSaveable { mutableStateOf("browse") }
    var filmId by rememberSaveable { mutableStateOf(films.first().id) }
    var focusId by rememberSaveable { mutableStateOf("nav-首页") }
    var returnScreen by rememberSaveable { mutableStateOf("browse") }
    var query by rememberSaveable { mutableStateOf("") }
    var episode by rememberSaveable { mutableIntStateOf(1) }
    var line by rememberSaveable { mutableStateOf("演示线路 A") }
    var favorites by remember { mutableStateOf(prefs.ids()) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    val systemReduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reduceMotion = reduced || systemReduced
    var splash by rememberSaveable { mutableStateOf(true) }
    val railStates = (listOf("首页", "电影", "剧集", "动漫", "直播", "我的", "search")).associateWith { rememberLazyListState() }
    val film = films.firstOrNull { it.id == filmId } ?: films.first()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun details(f: Film) { filmId = f.id; episode = 1; returnScreen = screen; screen = "detail" }
    fun back() {
        when (screen) {
            "player" -> screen = "detail"
            "detail" -> screen = returnScreen
            "search", "settings" -> { focusId = if (screen == "settings") "settings" else "search"; screen = "browse" }
            else -> { category = "首页"; focusId = "nav-首页" }
        }
    }
    BackHandler(splash || screen != "browse" || category != "首页") {
        if (splash) splash = false else back()
    }
    LaunchedEffect(screen, splash, category) {
        if (!splash) {
            // Request only attached controls; returning to a rail first composes its remembered scroll position.
            withFrameNanos { }
            val id = when (screen) { "browse", "search" -> focusId; else -> "screen-first" }
            runCatching { requester(id).requestFocus() }.onFailure {
                if (screen == "browse") runCatching { requester("nav-$category").requestFocus() }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        Image(painterResource(film.artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE05090E), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x44050A10), Color.Transparent, Color(0xFF080B10)))))
        if (splash) {
            Splash(reduceMotion) { splash = false }
        } else when (screen) {
            "browse", "search" -> {
                val isSearch = screen == "search"
                val filtered = if (isSearch) catalog.search(query) else films.filter {
                    category == "首页" || it.category == category || category == "我的" && it.id in favorites
                }
                // List states live outside screen composition and survive opening details.
                val railState = railStates.getValue(if (isSearch) "search" else category)
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("HERRIN", letterSpacing = 4.sp, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Row(Modifier.background(Color(0xCC13161C), RoundedCornerShape(40.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("首页", "电影", "剧集", "动漫", "直播", "我的").forEach { cat ->
                                TvButton(cat, Modifier.testTag("nav-$cat").focusRequester(requester("nav-$cat")), reduceMotion, category == cat && !isSearch,
                                    onFocus = { focusId = "nav-$cat" }) { category = cat; screen = "browse" }
                            }
                        }
                        TvButton("搜索", Modifier.testTag("search").focusRequester(requester("search")), reduceMotion, onFocus = { focusId = "search" }) { screen = "search" }
                        TvButton("设置", Modifier.testTag("settings").focusRequester(requester("settings")), reduceMotion, onFocus = { focusId = "settings" }) { screen = "settings" }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (isSearch) {
                        Text("搜索 · 离线演示片库", fontSize = 24.sp)
                        OutlinedTextField(query, { query = it }, Modifier.testTag("search-input").fillMaxWidth().padding(vertical = 8.dp),
                            label = { Text("输入片名 / 英文名（支持系统键盘）") }, singleLine = true)
                        // An on-screen keyboard also permits search without a hardware keyboard or IME.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("轨道", "潮汐", "群山", "星夜", "清空").forEach { word ->
                                TvButton(word, reduced = reduceMotion) { query = if (word == "清空") "" else word }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                        Text("HERRIN ORIGINALS  /  原创虚构演示", fontSize = 12.sp, letterSpacing = 2.sp, color = Color(0xFFD3D6DB))
                        Text(film.title, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("${film.subtitle}    ·    ${film.year}    ·    ${film.category}", fontSize = 13.sp)
                        Text(film.synopsis, Modifier.widthIn(max = 470.dp).padding(vertical = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, color = Color(0xFFCFD1D7))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("▶ 播放演示", reduced = reduceMotion, onFocus = { focusId = "play" }, modifier = Modifier.testTag("play").focusRequester(requester("play"))) {
                                returnScreen = "browse"; episode = 1; screen = "player"
                            }
                            TvButton("影片详情", reduced = reduceMotion, onFocus = { focusId = "details" }, modifier = Modifier.testTag("details").focusRequester(requester("details"))) { details(film) }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "我的" -> "我的收藏"; else -> "精选${category} · 演示片库" }, fontWeight = FontWeight.Bold)
                    if (category == "直播" && !isSearch) Text("直播尚未接入 · M2 将支持用户配置的合法直播源", Modifier.padding(24.dp))
                    else if (filtered.isEmpty()) Text(if (isSearch) "没有找到匹配影片" else "暂无收藏，进入影片详情添加", Modifier.padding(24.dp))
                    else LazyRow(state = railState, contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            Poster(item, Modifier.testTag("card-${item.id}").focusRequester(requester("card-${item.id}")), reduceMotion,
                                onFocus = { keyboard?.hide(); filmId = item.id; focusId = "card-${item.id}" }, onClick = { details(item) })
                        }
                    }
                }
            }
            "detail", "player", "settings" -> {
                LazyColumn(Modifier.fillMaxSize().background(Color(0xAA080B10)), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { TvButton("‹ 返回", Modifier.testTag("screen-first").focusRequester(requester("screen-first")), reduceMotion) { back() } }
                    if (screen == "settings") {
                        item { Text("设置", fontSize = 36.sp) }
                        item { TvButton("减少动态效果：${if (reduced) "开" else "关"}", reduced = reduceMotion) { reduced = !reduced; prefs.reducedMotion = reduced } }
                        item { Text("HERRIN 0.1.0 · M1 原生 Android TV 演示\n所有片名与画面为原创虚构示例。收藏仅保存在本机。\n影视接口、直播源及 FongMi 播放内核尚未接入。\n方向键移动 · 确定选择 · 返回回到上一页", lineHeight = 28.sp) }
                    } else {
                        item { Text(if (screen == "player") "播放演示" else film.title, fontSize = 38.sp, fontWeight = FontWeight.Bold) }
                        item { Text("${film.year}  ·  ${film.category}  ·  原创虚构样片\n${film.synopsis}", lineHeight = 26.sp) }
                        if (screen == "player") {
                            item { var status by remember { mutableStateOf("正在准备演示…") }
                                LaunchedEffect(film.id, episode, line) {
                                    status = when(val result = DemoPlaybackSource().resolve(PlaybackRequest(film.id, episode, line))) {
                                        is PlaybackResult.Unavailable -> result.reason
                                        is PlaybackResult.Ready -> "适配器尚未安装"
                                    }
                                }
                                Text(status, fontSize = 20.sp)
                            }
                            item { TvButton("返回详情", reduced = reduceMotion) { screen = "detail" } }
                        } else {
                            item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TvButton("▶ 播放演示", reduced = reduceMotion) { screen = "player" }
                                TvButton(if (film.id in favorites) "♥ 已收藏 · 取消" else "♡ 添加收藏", reduced = reduceMotion) { prefs.toggle(film.id); favorites = prefs.ids() }
                            } }
                            item { Text("演示选集", fontSize = 20.sp) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                (1..film.episodes).forEach { n -> TvButton("第 $n 集", reduced = reduceMotion, selected = episode == n) { episode = n } }
                            } }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf("演示线路 A", "演示线路 B").forEach { name -> TvButton(name, reduced = reduceMotion, selected = line == name) { line = name } }
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(film: Film, modifier: Modifier, reduced: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(if (reduced) 0 else 160), label = "poster")
    Column(modifier.width(204.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x554C5668), RoundedCornerShape(10.dp))
        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF151A22))
        .onPreviewKeyEvent { event ->
            // TV IMEs may consume the initial confirm down event while leaving text input.
            // Activate on confirm release whenever this poster owns focus, including that transition.
            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                if (event.type == KeyEventType.KeyUp) onClick()
                true
            } else false
        }.clickable(onClick = onClick)) {
        Image(painterResource(film.artwork), film.title, Modifier.fillMaxWidth().height(90.dp), contentScale = ContentScale.Crop)
        Text(film.title, Modifier.padding(9.dp), fontSize = 14.sp)
    }
}

@Composable
internal fun Splash(reduced: Boolean, done: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(reduced) {
        focus.requestFocus()
        if (reduced) { done(); return@LaunchedEffect }
        delay(500)
        repeat(5) { phase++; delay(330) }
        delay(650)
        done()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            "HERRIN".forEachIndexed { index, c ->
                val visible = index == 0 || index < 6 - phase
                val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "letter")
                val width by animateFloatAsState(if (visible) 48f else 0f, tween(300), label = "collapse")
                Box(Modifier.width(width.dp).alpha(alpha), contentAlignment = Alignment.Center) {
                    Text(c.toString(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        TvButton("跳过 · 确定", Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).focusRequester(focus), reduced) { done() }
    }
}
