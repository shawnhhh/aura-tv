package tv.herrin.app

import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Enabled explicitly by adb setprop log.tag.HerrinRemote DEBUG in diagnostics. */
internal object RemoteTrace {
    private const val TAG = "HerrinRemote"
    fun event(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Observe through the public Window.Callback contract without calling AndroidX restricted APIs.
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                RemoteTrace.event("ACTIVITY before code=${event.keyCode} action=${event.action} windowFocus=${hasWindowFocus()} viewFocus=${currentFocus?.javaClass?.simpleName}")
                val handled = originalCallback.dispatchKeyEvent(event)
                RemoteTrace.event("ACTIVITY after code=${event.keyCode} action=${event.action} handled=$handled")
                return handled
            }
        }
        enableEdgeToEdge()
        preferSmoothTvUi()
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
                     selected: Boolean = false, quiet: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    // Focus must react on the very first D-pad frame. A selected episode/line is a
    // persistent value, not a second white focus pill left behind by navigation.
    val fill = when {
        focused -> Color.White
        selected -> Color(0xFF344B68)
        quiet -> Color.Transparent
        else -> Color(0x99343840)
    }
    Box(modifier
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .border(if (focused) 2.dp else if (selected) 1.dp else 0.dp,
            if (focused) Color.White else if (selected) Color(0xFF7C9BBC) else Color.Transparent, shape)
        .clip(shape).background(fill)
        .clickable(onClick = onClick).padding(horizontal = if (quiet) 13.dp else 16.dp, vertical = if (quiet) 8.dp else 11.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (focused) Color(0xFF111318) else Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Herrin() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val inputMethod = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    var searchInputFocused by remember { mutableStateOf(false) }
    var configInputFocused by remember { mutableStateOf(false) }
    val prefs = remember { Preferences(context) }
    val watchHistory = remember(context) { PlaybackHistory(context) }
    val demoCatalog: CatalogSource = remember { DemoCatalog() }
    val demoFilms = remember { demoCatalog.titles() }
    val providerRepo = remember(context) { RemoteCatalogRepository(context) }
    var providerFilms by remember { mutableStateOf<List<Film>?>(null) }
    var providerStatus by remember { mutableStateOf("当前使用离线演示目录") }
    var configUrlDraft by rememberSaveable { mutableStateOf(prefs.catalogUrl) }
    var mediaUrlDraft by rememberSaveable { mutableStateOf(prefs.testMediaUrl) }
    var configStatus by rememberSaveable { mutableStateOf("可配置自己有权访问的 HTTPS 目录或测试流") }
    var catalogRefresh by remember { mutableIntStateOf(0) }
    val films = providerFilms ?: demoFilms
    LaunchedEffect(catalogRefresh) {
        val endpoint = prefs.catalogUrl
        if (endpoint.isBlank()) {
            providerFilms = null
            providerStatus = "当前使用离线演示目录"
        } else {
            providerStatus = "正在同步授权目录…"
            when (val result = providerRepo.load(endpoint)) {
                is CatalogLoadResult.Ready -> {
                    providerFilms = result.catalog.films
                    providerStatus = if (result.catalog.fromCache)
                        "离线缓存目录 · ${result.catalog.films.size} 部"
                        else "已更新授权目录 · ${result.catalog.films.size} 部"
                }
                is CatalogLoadResult.Error -> {
                    providerFilms = null
                    providerStatus = result.reason
                }
            }
        }
    }
    var category by rememberSaveable { mutableStateOf("首页") }
    var screen by rememberSaveable { mutableStateOf("browse") }
    var filmId by rememberSaveable { mutableStateOf(films.first().id) }
    var focusId by rememberSaveable { mutableStateOf("nav-首页") }
    var pendingToolbarFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var returnScreen by rememberSaveable { mutableStateOf("browse") }
    var query by rememberSaveable { mutableStateOf("") }
    var episode by rememberSaveable { mutableIntStateOf(1) }
    var line by rememberSaveable { mutableStateOf("演示线路 A") }
    var favorites by remember { mutableStateOf(prefs.ids()) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    val systemReduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reduceMotion = reduced || systemReduced
    var splash by rememberSaveable { mutableStateOf(true) }
    val railStates = (listOf("首页", "电影", "剧集", "动漫", "直播", "我的", "search", "recommend")).associateWith { rememberLazyListState() }
    val film = films.firstOrNull { it.id == filmId } ?: films.first()
    val catalogScope = prefs.catalogUrl.ifBlank { "herrin-offline-demo" }
    val currentEpisode = film.playbackEpisodes.firstOrNull { it.number == episode }
    val currentLines = currentEpisode?.lines.orEmpty()
    val resume = watchHistory.load(catalogScope, film.id, episode,
        if (currentLines.isEmpty()) "independent-test" else line)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun details(f: Film) {
        RemoteTrace.event("DETAILS invoked film=${f.id} from=$screen")
        filmId = f.id
        episode = f.playbackEpisodes.firstOrNull()?.number ?: 1
        line = f.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
        returnScreen = screen
        screen = "detail"
    }
    // Hiding an IME window alone does not release the TV IME's served input connection.
    // Wait for Compose's text-focus cancellation, then rebuild the native connection without an editor.
    LaunchedEffect(screen, focusId, searchInputFocused, configInputFocused) {
        val leavingSearchEditor = screen == "search" && !searchInputFocused &&
            focusId.startsWith("card-")
        val leavingSettingsEditor = screen == "settings" && !configInputFocused
        if (leavingSearchEditor || leavingSettingsEditor) {
            keyboard?.hide()
            withFrameNanos { }
            inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
            // Restarting the input connection while a TV settings button has focus
            // reopens some television IMEs and swallows its first D-pad CENTER key.
            // The search result path needs restartInput for its older IME handoff fix.
            if (leavingSearchEditor) inputMethod.restartInput(view)
            RemoteTrace.event("IME handoff search=$leavingSearchEditor settings=$leavingSettingsEditor acceptingText=${inputMethod.isAcceptingText} nativeFocus=${view.hasFocus()}")
        }
    }
    fun back() {
        when (screen) {
            "player" -> screen = "detail"
            "detail" -> screen = returnScreen
            "search", "settings" -> {
                val target = if (screen == "settings") "settings" else "search"
                pendingToolbarFocus = target
                focusId = target
                screen = "browse"
            }
            else -> { category = "首页"; focusId = "nav-首页" }
        }
    }
    BackHandler(splash || screen != "browse" || category != "首页") {
        if (splash) splash = false else back()
    }
    LaunchedEffect(screen, splash, category) {
        RemoteTrace.event("ROUTE screen=$screen category=$category focusId=$focusId")
        if (!splash) {
            // Request only attached controls; returning to a rail first composes its remembered scroll position.
            withFrameNanos { }
            val id = when (screen) {
                "browse" -> pendingToolbarFocus ?: focusId
                "search" -> focusId
                else -> "screen-first"
            }
            // A lazy search result attaches after its parent. Its item effect restores focus below.
            if (screen == "search" && id.startsWith("card-") && films.any { (it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true)) && "card-${it.id}" == id })
                return@LaunchedEffect
            runCatching { requester(id).requestFocus() }.onSuccess {
                pendingToolbarFocus = null
                RemoteTrace.event("RESTORE target=$id completed")
            }.onFailure {
                if (screen == "browse") runCatching { requester("nav-$category").requestFocus() }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        // Defer decoding and drawing the large hero until after splash: loading the
        // artwork underneath an opaque splash causes avoidable startup frame stalls.
        if (!splash) {
            FilmArtwork(film, Modifier.fillMaxSize())
            // Cinematic image stays full-bleed. Scrims protect legibility without flattening the artwork.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0.0f to Color(0xF905080D), 0.32f to Color(0xD9060A11),
                0.66f to Color(0x4B060911), 1.0f to Color(0x13060911)
            )))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.0f to Color(0x8A05070B), 0.18f to Color.Transparent,
                0.53f to Color(0x1B05070B), 1.0f to Color(0xF606080B)
            )))
        }
        if (splash) {
            Splash(reduceMotion) { splash = false }
        } else when (screen) {
            "browse", "search" -> {
                val isSearch = screen == "search"
                val filtered = if (isSearch) films.filter {
                    it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true)
                } else films.filter {
                    category == "首页" || it.category == category || category == "我的" && it.id in favorites
                }
                // History drives the first shelf only for films with actual saved playback.
                // On a fresh installation the same rail shows browseable demo suggestions.
                val watched = if (!isSearch && category == "首页") watchHistory.recent(catalogScope, filtered)
                    else emptyList()
                val railFilms = if (watched.isNotEmpty()) watched else filtered
                val railState = railStates.getValue(if (isSearch) "search" else category)
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(34.dp).border(1.dp, Color(0xA5EFF2F8), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center) {
                                Text("H", fontSize = 21.sp, fontWeight = FontWeight.Light, color = Color.White)
                            }
                            Text("HERRIN", letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        }
                        Row(Modifier.background(Color(0xE910131A), RoundedCornerShape(50.dp))
                            .border(1.dp, Color(0x393B414B), RoundedCornerShape(50.dp)).padding(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("首页", "电影", "剧集", "动漫", "直播", "我的").forEach { cat ->
                                TvButton(cat, Modifier.testTag("nav-$cat").focusRequester(requester("nav-$cat")), reduceMotion, category == cat && !isSearch,
                                    quiet = true, onFocus = { focusId = "nav-$cat" }) { category = cat; screen = "browse" }
                            }
                        }
                        TvButton("搜索", Modifier.testTag("search").focusRequester(requester("search")), reduceMotion, quiet = true, onFocus = { focusId = "search" }) { screen = "search" }
                        TvButton("设置", Modifier.testTag("settings").focusRequester(requester("settings")), reduceMotion, quiet = true, onFocus = { focusId = "settings" }) { screen = "settings" }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (isSearch) {
                        Text("搜索 · 离线演示片库", fontSize = 24.sp)
                        OutlinedTextField(query, { query = it }, Modifier.testTag("search-input").onFocusChanged { searchInputFocused = it.isFocused; RemoteTrace.event("INPUT focus=${it.isFocused}") }.fillMaxWidth().padding(vertical = 8.dp),
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(18.dp).height(2.dp).background(Color.White))
                            Text(if (providerFilms == null) "HERRIN  ·  ORIGINAL PREMIERE" else "HERRIN  ·  USER CATALOG", fontSize = 12.sp, letterSpacing = 2.sp,
                                fontWeight = FontWeight.Medium, color = Color(0xFFE5EAF2))
                        }
                        Spacer(Modifier.height(11.dp))
                        Text(film.title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp, color = Color.White, lineHeight = 49.sp)
                        Text(film.subtitle, fontSize = 13.sp, letterSpacing = 3.sp,
                            color = Color(0xFFE4E7EF))
                        Spacer(Modifier.height(12.dp))
                        Text("${film.year}   ·   ${film.category}   ·   ${if (providerFilms == null) "ORIGINAL UI DEMO" else "USER-SUPPLIED CATALOG"}",
                            fontSize = 13.sp, color = Color(0xFFF3F5F8))
                        Text(film.synopsis, Modifier.widthIn(max = 445.dp).padding(vertical = 6.dp),
                            fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2,
                            color = Color(0xFFE0E4EA))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("▶ 播放演示", selected = true, reduced = reduceMotion, onFocus = { focusId = "play" }, modifier = Modifier.testTag("play").focusRequester(requester("play"))) {
                                returnScreen = "browse"
                                episode = film.playbackEpisodes.firstOrNull()?.number ?: 1
                                line = film.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
                                screen = "player"
                            }
                            TvButton("影片详情", reduced = reduceMotion, onFocus = { focusId = "details" }, modifier = Modifier.testTag("details").focusRequester(requester("details"))) { details(film) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "我的" -> "我的收藏"; category == "首页" -> "继续观看"; else -> "精选${category} · 演示片库" },
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text(if (!isSearch && category == "首页") "精选首页 · 演示片库" else "DISCOVER   /   HERRIN", fontSize = 11.sp, letterSpacing = 1.5.sp,
                            color = Color(0xFFB6BBC5))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (category == "直播" && !isSearch) Text("直播尚未接入 · M2 将支持用户配置的合法直播源", Modifier.padding(24.dp))
                    else if (filtered.isEmpty()) Text(if (isSearch) "没有找到匹配影片" else "暂无收藏，进入影片详情添加", Modifier.padding(24.dp))
                    else LazyRow(state = railState, contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(railFilms, key = { it.id }) { item ->
                            // Runs after this lazy item's focus target is attached, including Back from detail.
                            LaunchedEffect(item.id) {
                                if (isSearch && focusId == "card-${item.id}") {
                                    requester("card-${item.id}").requestFocus()
                                    RemoteTrace.event("RESTORE search card=${item.id} attached")
                                }
                            }
                            Poster(item, Modifier.testTag("card-${item.id}").focusRequester(requester("card-${item.id}")), reduceMotion,
                                onFocus = { filmId = item.id; focusId = "card-${item.id}" }, onClick = { details(item) },
                                progress = if (!isSearch && category == "首页") {
                                    item.playbackEpisodes.asSequence().flatMap { part ->
                                        part.lines.asSequence().mapNotNull { option ->
                                            watchHistory.load(catalogScope, item.id, part.number, option.id)
                                        }
                                    }.maxByOrNull { it.updatedAtMs }?.fraction
                                } else null)
                        }
                    }
                    if (!isSearch && category == "首页") {
                        Spacer(Modifier.height(11.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("热门推荐", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("HERRIN PICKS", fontSize = 11.sp, letterSpacing = 1.6.sp, color = Color(0xFFB6BBC5))
                        }
                        Spacer(Modifier.height(5.dp))
                        LazyRow(state = railStates.getValue("recommend"), contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(films.asReversed(), key = { it.id }) { item ->
                                Poster(item, Modifier.testTag("recommend-${item.id}")
                                    .focusRequester(requester("recommend-${item.id}")), reduceMotion,
                                    onFocus = { filmId = item.id; focusId = "recommend-${item.id}" },
                                    onClick = { details(item) })
                            }
                        }
                    }
                }
            }
            "detail", "player", "settings" -> {
                val licensed = if (screen == "player" && film.playbackEpisodes.isNotEmpty())
                    LicensedPlaybackResolver.resolve(film, episode, line) else null
                val licensedMedia = (licensed as? PlaybackResult.Ready)?.media
                val testMedia = if (screen == "player") AuthorizedUrls.https(prefs.testMediaUrl) else null
                val chosenMedia = licensedMedia ?: testMedia?.let { PlaybackMedia(it) }
                if (chosenMedia != null) {
                    val historyLine = if (licensedMedia != null) line else "independent-test"
                    val previous = watchHistory.load(catalogScope, film.id, episode, historyLine)
                    AuthorizedPlaybackPanel(
                        media = chosenMedia,
                        startPositionMs = previous?.positionMs ?: 0L,
                        onPlaybackProgress = { position, duration, finished ->
                            watchHistory.update(catalogScope, film.id, episode, historyLine,
                                position, duration, finished)
                        },
                        onBack = { screen = "detail" }
                    )
                } else LazyColumn(Modifier.testTag("settings-list").fillMaxSize().background(Color(0xAA080B10)), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { TvButton("‹ 返回", Modifier.testTag("screen-first").focusRequester(requester("screen-first")), reduceMotion) { back() } }
                    if (screen == "settings") {
                        item { Text("设置", fontSize = 36.sp) }
                        item { TvButton("减少动态效果：${if (reduced) "开" else "关"}", reduced = reduceMotion) { reduced = !reduced; prefs.reducedMotion = reduced } }
                        item { Text("M2 · 授权目录与播放器", fontSize = 22.sp) }
                        item { OutlinedTextField(configUrlDraft, { configUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-catalog-url").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("获授权的 HTTPS 目录 JSON 地址（可留空）") },
                            singleLine = true) }
                        item { OutlinedTextField(mediaUrlDraft, { mediaUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-media-url").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("有权播放的 HTTPS 测试流（可留空）") },
                            singleLine = true) }
                        item { TvButton("保存媒体配置", reduced = reduceMotion,
                            onFocus = { focusId = "settings-save"; keyboard?.hide() }) {
                            val catalogLink = configUrlDraft.trim()
                            val mediaLink = mediaUrlDraft.trim()
                            if ((catalogLink.isNotEmpty() && AuthorizedUrls.https(catalogLink) == null) ||
                                (mediaLink.isNotEmpty() && AuthorizedUrls.https(mediaLink) == null)) {
                                configStatus = "保存失败：链接必须是合法 HTTPS 地址"
                            } else {
                                prefs.catalogUrl = catalogLink
                                prefs.testMediaUrl = mediaLink
                                providerFilms = null
                                catalogRefresh++
                                configStatus = "配置已保存；测试流只会在手动进入演示播放页时使用"
                            }
                        } }
                        item { TvButton("重新同步目录", reduced = reduceMotion,
                            onFocus = { focusId = "settings-refresh"; keyboard?.hide() }) { catalogRefresh++ } }
                        item { Text(providerStatus, fontSize = 15.sp, color = Color(0xFFCFD7E4)) }
                        item { Text(configStatus, fontSize = 13.sp, color = Color(0xFFBDC8D6)) }
                        item { Text("请仅填写你有权使用的目录和媒体地址。此应用不会自动获取第三方影视片源；网络失败时仍可使用离线演示内容。", lineHeight = 22.sp) }

                        item { Text("HERRIN M2.2 Alpha · 原生 Android TV\n内置影片仍是原创虚构演示。收藏仅保存在本机。\n可从用户提供的 HTTPS 地址读取影片元数据、加载海报与播放测试流；没有内置未授权片源。\nFongMi 接口与直播功能尚未接入。\n方向键移动 · 确定选择 · 返回回到上一页", lineHeight = 28.sp) }
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
                                TvButton(if (film.playbackEpisodes.isEmpty()) "▶ 播放演示" else "▶ 播放授权片源",
                                    selected = true, reduced = reduceMotion) { screen = "player" }
                                TvButton(if (film.id in favorites) "♥ 已收藏 · 取消" else "♡ 添加收藏", reduced = reduceMotion) { prefs.toggle(film.id); favorites = prefs.ids() }
                            } }
                            if (resume != null) {
                                item { Text("继续播放 · 已观看 ${resume.positionMs / 1000} 秒 / ${resume.durationMs / 1000} 秒",
                                    fontSize = 17.sp, color = Color(0xFFD4E6FD)) }
                                item { TvButton("从头播放", reduced = reduceMotion) {
                                    watchHistory.clear(catalogScope, film.id, episode,
                                        if (currentLines.isEmpty()) "independent-test" else line)
                                    screen = "player"
                                } }
                            }
                            item { Text(if (film.playbackEpisodes.isEmpty()) "演示选集" else "授权选集",
                                fontSize = 20.sp) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (film.playbackEpisodes.isEmpty()) {
                                    (1..film.episodes).forEach { n ->
                                        TvButton("第 $n 集", modifier = Modifier.testTag("episode-$n"), reduced = reduceMotion,
                                            selected = episode == n) { episode = n }
                                    }
                                } else {
                                    film.playbackEpisodes.forEach { entry ->
                                        TvButton("第 ${entry.number} 集 · ${entry.title}",
                                            modifier = Modifier.testTag("episode-${entry.number}"),
                                            reduced = reduceMotion, selected = episode == entry.number) {
                                            episode = entry.number
                                            line = entry.lines.first().id
                                        }
                                    }
                                }
                            } }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (currentLines.isEmpty()) {
                                    listOf("演示线路 A", "演示线路 B").forEach { name ->
                                        TvButton(name, modifier = Modifier.testTag("line-$name"),
                                            reduced = reduceMotion, selected = line == name) { line = name }
                                    }
                                } else {
                                    currentLines.forEach { option ->
                                        TvButton(option.title, modifier = Modifier.testTag("line-${option.id}"), reduced = reduceMotion,
                                            selected = line == option.id) { line = option.id }
                                    }
                                }
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(film: Film, modifier: Modifier, reduced: Boolean, onFocus: () -> Unit, onClick: () -> Unit, progress: Float? = null) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.075f else 1f,
        tween(if (reduced) 0 else 190), label = "poster")
    val shape = RoundedCornerShape(13.dp)
    Box(modifier.width(217.dp).height(116.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged {
            focused = it.isFocused
            RemoteTrace.event("POSTER ${film.id} focus=$focused")
            if (it.isFocused) onFocus()
        }
        .border(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x665E6A78), shape)
        .clip(shape).background(Color(0xFF101821))
        .onPreviewKeyEvent { event ->
            // Keep native Compose clickable as the single source of key activation.
            RemoteTrace.event("POSTER ${film.id} key=${event.nativeKeyEvent.keyCode} type=${event.type} focus=$focused")
            false
        }
        .clickable { RemoteTrace.event("POSTER ${film.id} CLICK clickable"); onClick() }) {
        FilmArtwork(film, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.0f to Color.Transparent, 0.45f to Color(0x1C06090E), 1f to Color(0xE9000409)
        )))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(film.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, maxLines = 1)
            Text("${film.year}   ·   ${film.category}", fontSize = 10.sp,
                letterSpacing = 1.sp, color = Color(0xFFD3D8E1))
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                .background(Color(0xCC495160))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Color(0xFFDCE8FF)))
            }
        }
        if (focused) {
            Text("H", Modifier.align(Alignment.TopEnd)
                .padding(10.dp).background(Color(0xBD10141A), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
internal fun Splash(reduced: Boolean, done: () -> Unit) {
    val collapse = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(reduced) {
        focus.requestFocus()
        if (reduced) { done(); return@LaunchedEffect }
        delay(450)
        collapse.animateTo(5f, tween(durationMillis = 1800, easing = LinearEasing))
        delay(650)
        done()
    }
    val collapsed by remember { derivedStateOf { collapse.value >= 5f } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080B10))
        .testTag("splash").focusRequester(focus)
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            if (event.type == KeyEventType.KeyDown &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == android.view.KeyEvent.KEYCODE_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                done()
                true
            } else false
        }.focusable(), contentAlignment = Alignment.Center) {
        // One GPU-layer-only collapse animation; no per-letter width animation or
        // repeated Row remeasurement. H travels to the true screen center.
        val cell = minOf(94.dp, (maxWidth - 80.dp) / 6)
        val stepPx = with(LocalDensity.current) { cell.toPx() }
        "HERRIN".forEachIndexed { index, letter ->
            if (index == 0 || !collapsed) {
                Text(letter.toString(), modifier = Modifier.width(cell).graphicsLayer {
                    val progress = collapse.value
                    translationX = (index - (5f - progress) / 2f) * stepPx
                    alpha = if (index == 0) 1f else (6f - index - progress).coerceIn(0f, 1f)
                }, textAlign = TextAlign.Center, fontSize = (cell.value * 0.91f).sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}
