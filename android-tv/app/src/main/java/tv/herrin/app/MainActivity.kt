package tv.herrin.app

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    // Native focus switches immediately. Only the *visual transform* animates,
    // using an interruptible spring in a GPU layer (no relayout or D-pad delay).
    val focusedScale = if (quiet) 1.028f else 1.042f
    val scale by animateFloatAsState(
        targetValue = if (focused && !reduced) focusedScale else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.87f, stiffness = 1100f),
        label = "tv-pill-focus-scale"
    )
    val liftPx = with(LocalDensity.current) { 2.dp.toPx() }
    // A selected episode/line is persistent state, not a second white focus pill.
    val fill = when {
        focused -> Color.White
        selected -> Color(0xFF344B68)
        quiet -> Color.Transparent
        else -> Color(0x99343840)
    }
    Box(modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = -liftPx * ((scale - 1f) / (focusedScale - 1f)).coerceIn(0f, 1f)
        }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .border(if (focused) 2.dp else if (selected) 1.dp else 0.dp,
            if (focused) Color.White else if (selected) Color(0xFF7C9BBC) else Color.Transparent, shape)
        .clip(shape).background(fill)
        .clickable(onClick = onClick).padding(horizontal = if (quiet) 13.dp else 16.dp, vertical = if (quiet) 8.dp else 11.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (focused) Color(0xFF111318) else Color.White, fontSize = 14.sp)
    }
}

/**
 * A continuous, non-focusable motion track follows the *real measured* tab positions.
 * The white button focus remains instantaneous and unique; only this subtle underline
 * glides when users reverse D-pad direction or hold a repeat key.
 */
@Composable
private fun TvNavTabs(
    category: String,
    isSearch: Boolean,
    focusId: String,
    reducedMotion: Boolean,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val categories = remember { listOf("首页", "电影", "剧集", "动漫", "直播", "我的") }
    val measured = remember { mutableStateMapOf<String, NavPillBounds>() }
    val focusedTab = focusId.removePrefix("nav-")
        .takeIf { !isSearch && focusId.startsWith("nav-") && it in categories }
    var lastFocusedTab by remember { mutableStateOf(categories.first()) }
    LaunchedEffect(focusedTab) {
        if (focusedTab != null) lastFocusedTab = focusedTab
    }
    val trackTarget = navTrackBounds(measured[focusedTab ?: lastFocusedTab] ?: NavPillBounds(0f, 0f))
    // No layout remeasurement on animation frames: only this tiny Canvas redraws.
    val motionSpec = if (reducedMotion) snap<Float>() else spring(dampingRatio = 0.90f, stiffness = 580f)
    val trackX by animateFloatAsState(trackTarget.leftPx, motionSpec, label = "nav-glide-x")
    val trackWidth by animateFloatAsState(trackTarget.widthPx, motionSpec, label = "nav-glide-width")
    val trackAlpha by animateFloatAsState(
        if (focusedTab != null && measured.containsKey(focusedTab)) 1f else 0f,
        if (reducedMotion) snap() else tween(durationMillis = 100),
        label = "nav-glide-visibility"
    )
    Column(
        Modifier.background(Color(0xE910131A), RoundedCornerShape(50.dp))
            .border(1.dp, Color(0x393B414B), RoundedCornerShape(50.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            categories.forEach { cat ->
                TvButton(
                    cat,
                    Modifier.testTag("nav-$cat")
                        .onGloballyPositioned { coordinates ->
                            val now = NavPillBounds(
                                coordinates.positionInParent().x, coordinates.size.width.toFloat()
                            )
                            if (measured[cat] != now) measured[cat] = now
                        }
                        .focusRequester(requester("nav-$cat")),
                    reducedMotion, category == cat && !isSearch,
                    quiet = true, onFocus = { onFocus("nav-$cat") }
                ) { onSelect(cat) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(4.dp).testTag("nav-motion-track")) {
            if (trackAlpha > 0f && trackWidth > 0f) {
                val glowHeight = 3.dp.toPx()
                drawRoundRect(
                    Color(0xFF94B6E5).copy(alpha = 0.56f * trackAlpha),
                    topLeft = Offset(trackX, 0f),
                    size = Size(trackWidth, glowHeight),
                    cornerRadius = CornerRadius(glowHeight / 2f)
                )
                drawRoundRect(
                    Color.White.copy(alpha = trackAlpha),
                    topLeft = Offset(trackX + trackWidth * 0.15f, 0f),
                    size = Size(trackWidth * 0.70f, 1.6.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }
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
    var liveSearchEditorFocused by remember { mutableStateOf(false) }
    var pendingLiveSearchFocus by remember { mutableStateOf(false) }
    val prefs = remember { Preferences(context) }
    val profileStore = remember(context) { CatalogProfiles(context) }
    val watchHistory = remember(context) { PlaybackHistory(context) }
    val demoCatalog: CatalogSource = remember { DemoCatalog() }
    val demoFilms = remember { demoCatalog.titles() }
    val providerRepo = remember(context) { RemoteCatalogRepository(context) }
    val posterCache = remember(context) { PosterCacheMaintenance(context) }
    var deviceStatus by remember { mutableStateOf("选择「查看设备状态」读取当前输出刷新率") }
    var posterCacheStatus by remember { mutableStateOf("海报缓存不会影响收藏或观看历史") }
    var confirmCacheClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmCacheClear) {
        if (confirmCacheClear) {
            delay(15_000)
            confirmCacheClear = false
        }
    }
    val liveRepo = remember { RemoteLivePlaylistRepository() }
    val epgRepo = remember { RemoteLiveEpgRepository() }
    val liveFavorites = remember(context) { LiveFavorites(context) }
    var liveChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var showFavoriteChannelsOnly by rememberSaveable { mutableStateOf(false) }
    var selectedLiveGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var liveSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var liveSearchQuery by rememberSaveable { mutableStateOf("") }
    val liveChannelRailState = rememberLazyListState()
    var liveFavoriteEpoch by remember { mutableIntStateOf(0) }
    var liveStatus by remember { mutableStateOf("请在设置中配置自己的授权 HTTPS M3U 直播列表") }
    var epgProgrammes by remember { mutableStateOf<List<LiveProgramme>>(emptyList()) }
    var epgStatus by remember { mutableStateOf("未配置 XMLTV 节目单") }
    var highlightedLiveUrl by remember { mutableStateOf<String?>(null) }
    var epgRefresh by remember { mutableIntStateOf(0) }
    var liveClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var liveRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(epgRefresh) {
        val endpoint = prefs.liveEpgUrl
        if (endpoint.isBlank()) {
            epgProgrammes = emptyList()
            epgStatus = "未配置 XMLTV 节目单"
        } else {
            epgStatus = "正在读取授权节目单…"
            when (val loaded = epgRepo.load(endpoint)) {
                is LiveEpgResult.Ready -> {
                    epgProgrammes = loaded.programmes
                    epgStatus = "已读取 ${loaded.programmes.size} 条节目安排"
                }
                is LiveEpgResult.Error -> epgStatus = if (epgProgrammes.isEmpty()) loaded.reason
                    else "${loaded.reason}；保留上次读取的节目单"
            }
        }
    }
    LaunchedEffect(liveRefresh) {
        val endpoint = prefs.livePlaylistUrl
        if (endpoint.isBlank()) {
            liveChannels = emptyList()
            liveStatus = "请在设置中配置自己的授权 HTTPS M3U 直播列表"
        } else {
            liveStatus = "正在加载授权直播列表…"
            when (val loaded = liveRepo.load(endpoint)) {
                is LivePlaylistResult.Ready -> {
                    liveChannels = loaded.channels
                    liveStatus = "已加载 ${loaded.channels.size} 个授权直播频道"
                }
                is LivePlaylistResult.Error -> liveStatus = if (liveChannels.isEmpty()) loaded.reason
                    else "${loaded.reason}；保留上次读取的频道列表"
            }
        }
    }
    var providerFilms by remember { mutableStateOf<List<Film>?>(null) }
    var providerStatus by remember { mutableStateOf("当前使用离线演示目录") }
    var savedProfiles by remember { mutableStateOf(profileStore.list()) }
    var activeProfileId by remember { mutableStateOf(profileStore.activeId()) }
    var sourceNameDraft by rememberSaveable { mutableStateOf("") }
    var configUrlDraft by rememberSaveable { mutableStateOf(prefs.catalogUrl) }
    var liveUrlDraft by rememberSaveable { mutableStateOf(prefs.livePlaylistUrl) }
    var epgUrlDraft by rememberSaveable { mutableStateOf(prefs.liveEpgUrl) }
    var importProfilesDraft by rememberSaveable { mutableStateOf("") }
    val tvBoxRepository = remember { TvBoxConfigRepository() }
    var tvBoxUrlDraft by rememberSaveable { mutableStateOf(prefs.tvBoxWarehouseUrl) }
    // Consent applies only to this app process; it is never persisted or granted on startup.
    var tvBoxAllowHttp by remember { mutableStateOf(false) }
    var tvBoxRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var tvBoxLoadEpoch by remember { mutableIntStateOf(0) }
    var tvBoxStatus by remember { mutableStateOf("TVBox 多仓仅读取配置元数据，需手动输入并点击读取") }
    var tvBoxWarehouses by remember { mutableStateOf<List<TvBoxWarehouse>>(emptyList()) }
    var tvBoxSingle by remember { mutableStateOf<TvBoxConfig.Single?>(null) }
    var tvBoxHerrinCatalog by remember { mutableStateOf<TvBoxConfig.HerrinCatalog?>(null) }
    var tvBoxSelectedName by remember { mutableStateOf("") }
    LaunchedEffect(tvBoxLoadEpoch) {
        val (address, isRoot) = tvBoxRequest ?: return@LaunchedEffect
        tvBoxStatus = "正在读取 TVBox 配置…"
        tvBoxSingle = null
        tvBoxHerrinCatalog = null
        when (val result = tvBoxRepository.load(address, tvBoxAllowHttp)) {
            is TvBoxConfig.Warehouses -> {
                tvBoxWarehouses = result.entries
                tvBoxStatus = "已识别 ${result.entries.size} 个仓库${if (result.ignored > 0) "；跳过 ${result.ignored} 个无效／重复条目" else ""}。选中下方仓库可预览。"
            }
            is TvBoxConfig.Single -> {
                if (isRoot) tvBoxWarehouses = emptyList()
                tvBoxSingle = result
                tvBoxStatus = "已识别单仓：${result.sites.size} 个站点，${result.lives.size} 组直播配置；仅展示元数据。"
            }
            is TvBoxConfig.HerrinCatalog -> {
                if (isRoot) tvBoxWarehouses = emptyList()
                tvBoxHerrinCatalog = result
                tvBoxStatus = "检测到 HERRIN 格式（${result.count} 个条目），可添加到现有 HTTPS 目录管理。"
            }
            is TvBoxConfig.Error -> {
                if (isRoot) tvBoxWarehouses = emptyList()
                tvBoxStatus = result.reason
            }
        }
        if (isRoot && tvBoxStatus.startsWith("已识别")) prefs.tvBoxWarehouseUrl = address
    }
    var pendingDeleteSourceId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingDeleteSourceId) {
        if (pendingDeleteSourceId != null) {
            delay(15_000)
            pendingDeleteSourceId = null
        }
    }
    var mediaUrlDraft by rememberSaveable { mutableStateOf(prefs.testMediaUrl) }
    var configStatus by rememberSaveable { mutableStateOf("可配置自己有权访问的 HTTPS 目录或测试流") }
    var catalogRefresh by remember { mutableIntStateOf(0) }
    val films = providerFilms ?: demoFilms
    fun readDeviceStatus(): String {
        val activity = context as? ComponentActivity ?: return "当前环境不支持读取屏幕刷新率"
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val mode = display.mode
        val current = UiDisplayMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        val supported = display.supportedModes.map {
            UiDisplayMode(it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        return deviceStatusSummary(current, preferredUiRefreshRate(current, supported),
            profileStore.list().size, prefs.livePlaylistUrl.isNotBlank())
    }
    LaunchedEffect(catalogRefresh) {
        val endpoint = prefs.catalogUrl
        // Never leave the previous source's films visible while the next URL loads.
        providerFilms = null
        if (endpoint.isBlank()) {
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
    LaunchedEffect(category, screen) {
        if (category == "直播" && screen == "browse") {
            while (true) {
                liveClock = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    var filmId by rememberSaveable { mutableStateOf(films.first().id) }
    var focusId by rememberSaveable { mutableStateOf("nav-首页") }
    var pendingToolbarFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var returnScreen by rememberSaveable { mutableStateOf("browse") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("全部") }
    var searchNewestFirst by rememberSaveable { mutableStateOf(false) }
    var episode by rememberSaveable { mutableIntStateOf(1) }
    var line by rememberSaveable { mutableStateOf("演示线路 A") }
    var activeLiveChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var activeLiveIndex by remember { mutableIntStateOf(-1) }
    val catalogScope = prefs.catalogUrl.ifBlank { "herrin-offline-demo" }
    var favorites by remember(catalogScope) { mutableStateOf(prefs.idsFor(catalogScope)) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    val systemReduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reduceMotion = reduced || systemReduced
    var splash by rememberSaveable { mutableStateOf(true) }
    val railStates = (listOf("首页", "电影", "剧集", "动漫", "直播", "我的", "search", "recommend")).associateWith { rememberLazyListState() }
    val film = films.firstOrNull { it.id == filmId } ?: films.first()
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
            "live-player" -> { focusId = "live-$activeLiveIndex"; screen = "browse" }
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
    fun finishLiveSearchInput() {
        keyboard?.hide()
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
        pendingLiveSearchFocus = true
    }
    BackHandler(splash || screen != "browse" || category != "首页") {
        when {
            splash -> splash = false
            screen == "browse" && category == "直播" &&
                liveSearchExpanded && liveSearchEditorFocused -> finishLiveSearchInput()
            else -> back()
        }
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
            "live-player" -> {
                val selected = activeLiveChannel
                if (selected != null) {
                    AuthorizedPlaybackPanel(
                        media = PlaybackMedia(selected.url),
                        isLive = true,
                        onBack = { focusId = "live-$activeLiveIndex"; screen = "browse" }
                    )
                } else {
                    Text("当前未选中直播频道", Modifier.padding(50.dp))
                }
            }
            "browse", "search" -> {
                val isSearch = screen == "search"
                val filtered = if (isSearch) SearchDiscovery.results(
                    films, query, searchCategory, searchNewestFirst
                ) else films.filter {
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
                        TvNavTabs(category, isSearch, focusId, reduceMotion, { id -> requester(id) },
                            onFocus = { focusId = it }) { selected ->
                            category = selected
                            screen = "browse"
                        }
                        TvButton("搜索", Modifier.testTag("search").focusRequester(requester("search")), reduceMotion, quiet = true, onFocus = { focusId = "search" }) { screen = "search" }
                        TvButton("设置", Modifier.testTag("settings").focusRequester(requester("settings")), reduceMotion, quiet = true, onFocus = { focusId = "settings" }) { screen = "settings" }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (isSearch) {
                        Text(if (providerFilms == null) "搜索 · 离线演示片库" else "搜索 · 当前授权目录", fontSize = 24.sp)
                        OutlinedTextField(query, { query = it }, Modifier.testTag("search-input").onFocusChanged { searchInputFocused = it.isFocused; RemoteTrace.event("INPUT focus=${it.isFocused}") }.fillMaxWidth().padding(vertical = 8.dp),
                            label = { Text("输入片名 / 英文名（支持系统键盘）") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchDiscovery.categories.forEach { option ->
                                TvButton(option,
                                    modifier = Modifier.testTag("search-filter-$option")
                                        .focusRequester(requester("search-filter-$option")),
                                    selected = searchCategory == option, reduced = reduceMotion, quiet = true,
                                    onFocus = { focusId = "search-filter-$option" }
                                ) { searchCategory = option }
                            }
                            TvButton(if (searchNewestFirst) "排序：最新优先" else "排序：目录顺序",
                                modifier = Modifier.testTag("search-sort").focusRequester(requester("search-sort")),
                                selected = searchNewestFirst, reduced = reduceMotion, quiet = true,
                                onFocus = { focusId = "search-sort" }
                            ) { searchNewestFirst = !searchNewestFirst }
                        }
                        Text("当前筛选：$searchCategory · ${filtered.size} 个结果",
                            modifier = Modifier.testTag("search-filter-status"),
                            fontSize = 12.sp, color = Color(0xFFC4CDDC))
                        // An on-screen keyboard also permits search without a hardware keyboard or IME.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("轨道", "潮汐", "群山", "星夜", "清空").forEach { word ->
                                TvButton(word, reduced = reduceMotion) { query = if (word == "清空") "" else word }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    } else if (category == "直播") {
                        // A live TV rail must not inherit the movie hero's tall
                        // spacer, poster synopsis and demo-play actions: those
                        // pushed the final channel row into TV overscan at 1080p.
                        Spacer(Modifier.height(24.dp))
                        Text("直播中心", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                        Text("你授权的频道 · HTTPS M3U · 可选 XMLTV / .xml.gz 节目预览",
                            fontSize = 13.sp, color = Color(0xFFBDC8D6))
                        Spacer(Modifier.height(18.dp))
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
                        Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "直播" -> "直播频道"; category == "我的" -> "我的收藏"; category == "首页" -> "继续观看"; else -> "精选${category} · 演示片库" },
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text(if (!isSearch && category == "首页") "精选首页 · 演示片库" else "DISCOVER   /   HERRIN", fontSize = 11.sp, letterSpacing = 1.5.sp,
                            color = Color(0xFFB6BBC5))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (category == "直播" && !isSearch) {
                        val currentGuide = remember(epgProgrammes, liveClock) {
                            AuthorizedXmlTv.snapshots(epgProgrammes, liveClock)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(liveStatus, Modifier.testTag("live-status"), color = Color(0xFFCFD7E4))
                            Text(epgStatus, Modifier.testTag("live-epg-status"),
                                color = Color(0xFFADBBD0), fontSize = 12.sp)
                            if (liveChannels.isEmpty()) {
                                TvButton("设置授权直播列表", modifier = Modifier.testTag("live-config"),
                                    reduced = reduceMotion, onFocus = { focusId = "live-config" }) {
                                    screen = "settings"
                                }
                                if (prefs.livePlaylistUrl.isNotBlank()) {
                                    TvButton("重试加载直播与节目单",
                                        modifier = Modifier.testTag("live-retry")
                                            .focusRequester(requester("live-retry")),
                                        reduced = reduceMotion, onFocus = { focusId = "live-retry" }) {
                                        liveRefresh++
                                        epgRefresh++
                                    }
                                }
                            } else {
                                val groups = remember(liveChannels) {
                                    LiveChannelDiscovery.groups(liveChannels)
                                }
                                val resolvedGroup = remember(liveChannels, selectedLiveGroup) {
                                    LiveChannelDiscovery.selectedGroupOrAll(liveChannels, selectedLiveGroup)
                                }
                                if (groups.size > 1) {
                                    LazyRow(
                                        modifier = Modifier.testTag("live-group-rail"),
                                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
                                    ) {
                                        item(key = "all-groups") {
                                            TvButton("全部分组", modifier = Modifier.testTag("live-group-all")
                                                .focusRequester(requester("live-group-all")),
                                                reduced = reduceMotion, selected = resolvedGroup == null,
                                                onFocus = { focusId = "live-group-all" }) {
                                                selectedLiveGroup = null
                                            }
                                        }
                                        itemsIndexed(groups, key = { _, group -> "group:$group" }) { index, group ->
                                            TvButton(if (group.isBlank()) "未分组" else group,
                                                modifier = Modifier.testTag("live-group-$index")
                                                    .focusRequester(requester("live-group-$index")),
                                                reduced = reduceMotion, selected = resolvedGroup == group,
                                                onFocus = { focusId = "live-group-$index" }) {
                                                selectedLiveGroup = group
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TvButton(if (showFavoriteChannelsOnly) "全部频道" else "★ 只看收藏",
                                        modifier = Modifier.testTag("live-favorites-filter")
                                            .focusRequester(requester("live-favorites-filter")),
                                        reduced = reduceMotion, selected = showFavoriteChannelsOnly,
                                        onFocus = { focusId = "live-favorites-filter" }
                                    ) { showFavoriteChannelsOnly = !showFavoriteChannelsOnly }
                                    TvButton(if (liveSearchExpanded) "收起查找" else "⌕ 查找频道",
                                        modifier = Modifier.testTag("live-search-toggle")
                                            .focusRequester(requester("live-search-toggle")),
                                        reduced = reduceMotion, selected = liveSearchExpanded,
                                        onFocus = { focusId = "live-search-toggle" }) {
                                        liveSearchExpanded = !liveSearchExpanded
                                        if (!liveSearchExpanded) {
                                            liveSearchQuery = ""
                                            pendingLiveSearchFocus = false
                                            keyboard?.hide()
                                        }
                                    }
                                    TvButton("刷新频道与节目单", modifier = Modifier.testTag("live-refresh")
                                        .focusRequester(requester("live-refresh")),
                                        reduced = reduceMotion, quiet = true,
                                        onFocus = { focusId = "live-refresh" }) {
                                        liveRefresh++
                                        epgRefresh++
                                    }
                                }
                                if (liveSearchExpanded) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(
                                            liveSearchQuery, { liveSearchQuery = it.take(40) },
                                            modifier = Modifier.width(390.dp)
                                                .testTag("live-search-input")
                                                .onFocusChanged {
                                                    liveSearchEditorFocused = it.isFocused
                                                    if (it.isFocused) focusId = "live-search-input"
                                                },
                                            label = { Text("输入频道名称或 tvg-id") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = {
                                                finishLiveSearchInput()
                                            })
                                        )
                                        TvButton("清空关键词", modifier = Modifier.testTag("live-search-clear")
                                            .focusRequester(requester("live-search-clear")),
                                            reduced = reduceMotion, quiet = true,
                                            onFocus = { focusId = "live-search-clear"; keyboard?.hide() }) {
                                            liveSearchQuery = ""
                                        }
                                    }
                                }
                                val visibleChannels = remember(
                                    liveChannels, resolvedGroup, showFavoriteChannelsOnly,
                                    liveSearchQuery, prefs.livePlaylistUrl, liveFavoriteEpoch
                                ) {
                                    LiveChannelDiscovery.visible(liveChannels, resolvedGroup,
                                        showFavoriteChannelsOnly, query = liveSearchQuery) { channel ->
                                        liveFavorites.contains(prefs.livePlaylistUrl, channel.url)
                                    }
                                }
                                LaunchedEffect(liveSearchQuery, resolvedGroup,
                                    showFavoriteChannelsOnly, liveChannels) {
                                    // An old rail offset can hide a newly filtered first result.
                                    liveChannelRailState.scrollToItem(0)
                                }
                                Text("${if (liveSearchQuery.isBlank()) "显示" else "匹配"} ${visibleChannels.size} / ${liveChannels.size} 个频道",
                                    modifier = Modifier.testTag("live-filter-status"),
                                    color = Color(0xFFADBBD0), fontSize = 12.sp)
                                if (visibleChannels.isEmpty()) {
                                    Text(when {
                                        liveSearchQuery.isNotBlank() ->
                                            "未找到匹配频道，请清空关键词或调整分组、收藏筛选"
                                        showFavoriteChannelsOnly ->
                                            "当前分组暂无收藏频道，可切换「全部频道」或其他分组"
                                        else -> "当前分组暂无频道，请选择其他分组"
                                    },
                                        color = Color(0xFFCFD7E4))
                                }
                                LazyRow(state = liveChannelRailState,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(10.dp)) {
                                    itemsIndexed(visibleChannels, key = { _, item -> item.url }) { index, channel ->
                                        val nowProgramme = currentGuide[channel.tvgId.ifBlank { channel.name }]?.now
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            TvButton(
                                                buildString {
                                                    if (channel.group.isNotBlank()) append("${channel.group} · ")
                                                    append(channel.name)
                                                    if (nowProgramme != null) append("  ·  正在播出：${nowProgramme.title}")
                                                },
                                                modifier = Modifier.testTag("live-$index")
                                                    .focusRequester(requester("live-$index")),
                                                reduced = reduceMotion,
                                                onFocus = {
                                                    focusId = "live-$index"
                                                    highlightedLiveUrl = channel.url
                                                }
                                            ) {
                                                activeLiveChannel = channel
                                                activeLiveIndex = index
                                                screen = "live-player"
                                            }
                                            TvButton(
                                                if (liveFavorites.contains(prefs.livePlaylistUrl, channel.url))
                                                    "★ 已收藏" else "☆ 收藏",
                                                modifier = Modifier.testTag("live-favorite-$index")
                                                    .focusRequester(requester("live-favorite-$index")),
                                                reduced = reduceMotion, quiet = true,
                                                onFocus = { focusId = "live-favorite-$index" }
                                            ) {
                                                liveFavorites.toggle(prefs.livePlaylistUrl, channel.url)
                                                liveFavoriteEpoch++
                                            }
                                        }
                                    }
                                }
                                LaunchedEffect(pendingLiveSearchFocus, visibleChannels.size,
                                    liveSearchExpanded) {
                                    if (pendingLiveSearchFocus && liveSearchExpanded) {
                                        // The TV IME must release its editor before a native
                                        // channel pill can receive the first remote key.
                                        liveChannelRailState.scrollToItem(0)
                                        withFrameNanos { }
                                        val target = if (visibleChannels.isEmpty())
                                            "live-search-clear" else "live-0"
                                        var focused = runCatching {
                                            requester(target).requestFocus()
                                        }.isSuccess
                                        if (!focused) {
                                            withFrameNanos { }
                                            focused = runCatching {
                                                requester(target).requestFocus()
                                            }.isSuccess
                                        }
                                        if (focused) {
                                            focusId = target
                                            pendingLiveSearchFocus = false
                                        }
                                    }
                                }
                                val preview = visibleChannels.firstOrNull { it.url == highlightedLiveUrl }
                                    ?: visibleChannels.firstOrNull()
                                val guide = preview?.let { currentGuide[it.tvgId.ifBlank { it.name }] }
                                if (preview != null && guide != null) {
                                    Column(Modifier.fillMaxWidth().testTag("live-guide-preview"),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("${preview.name} · 节目预览", fontSize = 14.sp,
                                            color = Color(0xFFCFD7E4))
                                        if (guide.now != null) {
                                            Text("当前：${guide.now.title}", fontSize = 15.sp,
                                                color = Color.White, maxLines = 1)
                                            LinearProgressIndicator(
                                                progress = { guide.progress ?: 0f },
                                                modifier = Modifier.width(300.dp).height(3.dp)
                                                    .testTag("live-guide-progress"))
                                        }
                                        if (guide.next != null) {
                                            Text(if (guide.now == null) "即将播出：${guide.next.title}"
                                                else "接下来：${guide.next.title}",
                                                fontSize = 13.sp, color = Color(0xFFADBBD0), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                        item { Text("目录源管理 · 最多 10 个授权 HTTPS 目录", fontSize = 22.sp) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp),
                                contentPadding = PaddingValues(horizontal = 3.dp)) {
                                item {
                                    TvButton("离线演示", modifier = Modifier.testTag("source-demo"),
                                        selected = activeProfileId == null, reduced = reduceMotion) {
                                        if (profileStore.activate(null)) {
                                            pendingDeleteSourceId = null
                                            activeProfileId = null
                                            configUrlDraft = ""
                                            sourceNameDraft = ""
                                            providerFilms = null
                                            catalogRefresh++
                                            configStatus = "已切换至离线演示目录"
                                        }
                                    }
                                }
                                items(savedProfiles, key = { it.id }) { profile ->
                                    TvButton(profile.name, modifier = Modifier.testTag("source-${profile.id}"),
                                        selected = activeProfileId == profile.id, reduced = reduceMotion) {
                                        if (profileStore.activate(profile.id)) {
                                            pendingDeleteSourceId = null
                                            activeProfileId = profile.id
                                            configUrlDraft = profile.url
                                            sourceNameDraft = profile.name
                                            providerFilms = null
                                            catalogRefresh++
                                            configStatus = "已切换至目录：${profile.name}"
                                        }
                                    }
                                }
                            }
                        }
                        item { OutlinedTextField(sourceNameDraft, { sourceNameDraft = it },
                            Modifier.fillMaxWidth().testTag("source-name").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("新目录名称（可留空）") }, singleLine = true) }
                        item { OutlinedTextField(configUrlDraft, { configUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-catalog-url").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("新增／当前授权目录的 HTTPS JSON 地址（可留空）") },
                            singleLine = true) }
                        item { OutlinedTextField(mediaUrlDraft, { mediaUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-media-url").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("有权播放的 HTTPS 测试流（可留空）") },
                            singleLine = true) }
                        item { Text("授权 IPTV 直播", fontSize = 21.sp) }
                        item { OutlinedTextField(liveUrlDraft, { liveUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-live-url")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("自己的 HTTPS M3U 直播列表地址（可留空）") },
                            singleLine = true) }
                        item { TvButton("保存直播列表", modifier = Modifier.testTag("live-save"),
                            reduced = reduceMotion, onFocus = { focusId = "live-save"; keyboard?.hide() }) {
                            val typed = liveUrlDraft.trim()
                            if (typed.isNotEmpty() && AuthorizedUrls.https(typed) == null) {
                                configStatus = "直播列表保存失败：必须是合法 HTTPS 地址"
                            } else {
                                prefs.livePlaylistUrl = typed
                                liveChannels = emptyList()
                                selectedLiveGroup = null
                                showFavoriteChannelsOnly = false
                                liveSearchExpanded = false
                                liveSearchQuery = ""
                                pendingLiveSearchFocus = false
                                highlightedLiveUrl = null
                                liveRefresh++
                                configStatus = "直播列表配置已保存；只读取你主动提供的频道"
                            }
                        } }
                        item { OutlinedTextField(epgUrlDraft, { epgUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("config-epg-url")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("可选 HTTPS XMLTV（.xml / .xml.gz）节目单地址") }, singleLine = true) }
                        item { TvButton("保存 XMLTV 节目单", modifier = Modifier.testTag("epg-save"),
                            reduced = reduceMotion, onFocus = { focusId = "epg-save"; keyboard?.hide() }) {
                            val typed = epgUrlDraft.trim()
                            if (typed.isNotBlank() && AuthorizedUrls.https(typed) == null) {
                                configStatus = "节目单保存失败：必须是合法 HTTPS 地址"
                            } else {
                                prefs.liveEpgUrl = typed
                                epgProgrammes = emptyList()
                                epgRefresh++
                                configStatus = "节目单配置已保存；只读取你主动提供的 XMLTV"
                            }
                        } }
                        item { Text("TVBox 多仓 / 单仓配置", fontSize = 22.sp) }
                        item { Text("支持 urls 多仓入口与 sites / lives 单仓预览。输入自己的配置地址后手动读取；不会执行第三方 Spider、JAR、JS 或解析站点内容。", color = Color(0xFFBDC8D6), lineHeight = 23.sp) }
                        item { OutlinedTextField(tvBoxUrlDraft, { tvBoxUrlDraft = it },
                            Modifier.fillMaxWidth().testTag("tvbox-warehouse-url")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("TVBox 多仓／单仓 HTTP(S) JSON 地址") }, singleLine = true) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("本次允许 HTTP：${if (tvBoxAllowHttp) "开" else "关"}",
                                modifier = Modifier.testTag("tvbox-http-consent"), reduced = reduceMotion,
                                selected = tvBoxAllowHttp) {
                                tvBoxAllowHttp = !tvBoxAllowHttp
                                tvBoxStatus = if (tvBoxAllowHttp) "已允许本次手动读取 HTTP 配置（不加密）；影视目录及播放仍只允许 HTTPS"
                                else "已关闭 HTTP 配置读取授权"
                            }
                            TvButton("读取配置", modifier = Modifier.testTag("tvbox-warehouse-load"),
                                reduced = reduceMotion, onFocus = { keyboard?.hide() }) {
                                tvBoxSelectedName = ""
                                tvBoxRequest = tvBoxUrlDraft.trim() to true
                                tvBoxLoadEpoch++
                            }
                        } }
                        item { Text(tvBoxStatus, modifier = Modifier.testTag("tvbox-status"),
                            color = Color(0xFFCFD7E4), lineHeight = 23.sp) }
                        if (tvBoxWarehouses.isNotEmpty()) {
                            item { Text("仓库列表 · 遥控器选择并预览", fontSize = 18.sp) }
                            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(5.dp)) {
                                itemsIndexed(tvBoxWarehouses) { index, warehouse ->
                                    TvButton(warehouse.name, modifier = Modifier.testTag("tvbox-entry-$index"),
                                        reduced = reduceMotion, selected = tvBoxSelectedName == warehouse.name) {
                                        tvBoxSelectedName = warehouse.name
                                        tvBoxRequest = warehouse.url to false
                                        tvBoxLoadEpoch++
                                    }
                                }
                            } }
                        }
                        if (tvBoxSingle != null) {
                            val preview = tvBoxSingle!!
                            item { Text("${if (tvBoxSelectedName.isBlank()) "当前单仓" else tvBoxSelectedName} · 站点预览", fontSize = 18.sp) }
                            item { Text(preview.sites.take(15).joinToString("  ·  ") { it.name }.ifBlank { "未提供站点条目" },
                                modifier = Modifier.testTag("tvbox-site-preview"), lineHeight = 24.sp) }
                            if (preview.hasExternalEngine) {
                                item { Text("注意：此仓包含需要 TVBox 专用解析器的站点；HERRIN 当前仅显示名称，不能直接搜索或播放。", color = Color(0xFFE2C38B)) }
                            }
                            val playlists = preview.lives.filter { it.playlistUrl != null }.take(10)
                            if (playlists.isNotEmpty()) {
                                item { Text("可尝试使用的授权 HTTPS M3U 直播列表", fontSize = 18.sp) }
                                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    itemsIndexed(playlists) { index, live ->
                                        TvButton("导入 ${live.name}", modifier = Modifier.testTag("tvbox-live-$index"),
                                            reduced = reduceMotion) {
                                            val link = live.playlistUrl ?: return@TvButton
                                            liveUrlDraft = link
                                            prefs.livePlaylistUrl = link
                                            liveChannels = emptyList()
                                            selectedLiveGroup = null
                                            liveRefresh++
                                            tvBoxStatus = "已导入 ${live.name} 的 HTTPS M3U 地址；请到直播页检查实际解析结果"
                                        }
                                    }
                                } }
                            }
                        }
                        if (tvBoxHerrinCatalog != null) {
                            item { TvButton("填入 HERRIN HTTPS 目录栏", modifier = Modifier.testTag("tvbox-herrin-use"),
                                reduced = reduceMotion) {
                                val link = tvBoxHerrinCatalog?.address.orEmpty()
                                if (AuthorizedUrls.https(link) != null) {
                                    configUrlDraft = link
                                    tvBoxStatus = "已填入目录栏；请按「保存媒体配置」以完成导入"
                                } else tvBoxStatus = "HERRIN 目录仅允许 HTTPS，不能从 HTTP 导入"
                            } }
                        }
                        item { TvButton("保存媒体配置", reduced = reduceMotion,
                            onFocus = { focusId = "settings-save"; keyboard?.hide() }) {
                            val catalogLink = configUrlDraft.trim()
                            val mediaLink = mediaUrlDraft.trim()
                            if (mediaLink.isNotEmpty() && AuthorizedUrls.https(mediaLink) == null) {
                                configStatus = "保存失败：链接必须是合法 HTTPS 地址"
                            } else {
                                val result = if (catalogLink.isBlank()) {
                                    if (profileStore.activate(null)) null else SaveProfileResult.Error("目录切换失败")
                                } else profileStore.addAndActivate(sourceNameDraft, catalogLink)
                                if (result is SaveProfileResult.Error) {
                                    configStatus = "保存失败：${result.reason}"
                                } else {
                                    prefs.testMediaUrl = mediaLink
                                    savedProfiles = profileStore.list()
                                    activeProfileId = profileStore.activeId()
                                    pendingDeleteSourceId = null
                                    providerFilms = null
                                    catalogRefresh++
                                    configStatus = "配置已保存；测试流只会在手动进入演示播放页时使用"
                                }
                            }
                        } }
                        if (activeProfileId != null) {
                            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvButton("修改当前目录", modifier = Modifier.testTag("source-edit"),
                                    reduced = reduceMotion, onFocus = { focusId = "source-edit"; keyboard?.hide() }) {
                                    val selectedId = activeProfileId
                                    if (selectedId != null) {
                                        when (val result = profileStore.update(selectedId, sourceNameDraft, configUrlDraft)) {
                                            is SaveProfileResult.Saved -> {
                                                savedProfiles = profileStore.list()
                                                pendingDeleteSourceId = null
                                                providerFilms = null
                                                catalogRefresh++
                                                configStatus = "已修改目录：${result.profile.name}"
                                            }
                                            is SaveProfileResult.Error -> configStatus = "修改失败：${result.reason}"
                                        }
                                    }
                                }
                                TvButton(
                                    if (pendingDeleteSourceId == activeProfileId) "再次确定删除此目录" else "删除当前目录",
                                    modifier = Modifier.testTag("source-delete"),
                                    reduced = reduceMotion, onFocus = { focusId = "source-delete"; keyboard?.hide() }
                                ) {
                                    val selectedId = activeProfileId
                                    if (selectedId != null) {
                                        if (pendingDeleteSourceId != selectedId) {
                                            pendingDeleteSourceId = selectedId
                                            configStatus = "请再次按确定删除当前目录；本地收藏与播放历史不会被清除"
                                        } else if (profileStore.delete(selectedId)) {
                                            pendingDeleteSourceId = null
                                            savedProfiles = profileStore.list()
                                            activeProfileId = null
                                            configUrlDraft = ""
                                            sourceNameDraft = ""
                                            providerFilms = null
                                            catalogRefresh++
                                            configStatus = "目录已删除，已切换至离线演示"
                                        }
                                    }
                                }
                            } }
                        }
                        item { TvButton("复制目录列表 JSON", modifier = Modifier.testTag("source-export"),
                            reduced = reduceMotion, onFocus = { focusId = "source-export"; keyboard?.hide() }) {
                            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            manager.setPrimaryClip(ClipData.newPlainText("HERRIN 目录备份", profileStore.exportJson()))
                            configStatus = "目录 JSON 已复制到剪贴板；如地址包含访问令牌，请勿公开分享"
                        } }
                        item { OutlinedTextField(importProfilesDraft, { importProfilesDraft = it },
                            Modifier.fillMaxWidth().testTag("import-catalogs-json").onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("粘贴 HERRIN 目录 JSON（仅合并，不覆盖）") }, minLines = 2, maxLines = 5) }
                        item { TvButton("导入目录列表 JSON", modifier = Modifier.testTag("source-import"),
                            reduced = reduceMotion, onFocus = { focusId = "source-import"; keyboard?.hide() }) {
                            when (val result = profileStore.importJson(importProfilesDraft.trim())) {
                                is ImportProfilesResult.Imported -> {
                                    savedProfiles = profileStore.list()
                                    importProfilesDraft = ""
                                    configStatus = "导入完成：新增 ${result.added} 个，跳过重复 ${result.skipped} 个；原选中目录保持不变"
                                }
                                is ImportProfilesResult.Error -> configStatus = result.reason
                            }
                        } }
                        item { TvButton("重新同步目录", reduced = reduceMotion,
                            onFocus = { focusId = "settings-refresh"; keyboard?.hide() }) { catalogRefresh++ } }
                        item { Text("设备状态与维护", fontSize = 21.sp) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("查看设备状态", modifier = Modifier.testTag("diagnostic-refresh"),
                                reduced = reduceMotion) { deviceStatus = readDeviceStatus() }
                            TvButton("复制诊断摘要", modifier = Modifier.testTag("diagnostic-copy"),
                                reduced = reduceMotion) {
                                deviceStatus = readDeviceStatus()
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                manager.setPrimaryClip(ClipData.newPlainText("HERRIN 设备诊断", deviceStatus))
                                configStatus = "诊断摘要已复制；不包含目录地址或播放链接"
                            }
                        } }
                        item { Text(deviceStatus, modifier = Modifier.testTag("device-status"),
                            color = Color(0xFFCFD7E4), lineHeight = 22.sp) }
                        item { TvButton(if (confirmCacheClear) "再次确定清理海报缓存" else "清理海报缓存",
                            modifier = Modifier.testTag("poster-cache-clear"), reduced = reduceMotion) {
                            if (!confirmCacheClear) {
                                confirmCacheClear = true
                                posterCacheStatus = "请在 15 秒内再次确定；只清理授权海报缓存文件"
                            } else {
                                val cleared = posterCache.clearFiles()
                                confirmCacheClear = false
                                posterCacheStatus = "已清理 $cleared 个缓存文件；收藏、播放历史和目录均已保留"
                            }
                        } }
                        item { Text(posterCacheStatus, modifier = Modifier.testTag("poster-cache-status"),
                            color = Color(0xFFBDC8D6)) }
                        item { Text(providerStatus, fontSize = 15.sp, color = Color(0xFFCFD7E4)) }
                        item { Text(configStatus, fontSize = 13.sp, color = Color(0xFFBDC8D6)) }
                        item { Text("仅保存你有权访问的 HTTPS 目录。可切换、修改、二次确认删除、复制 JSON 备份或从 JSON 合并导入，最多 10 个。更换 URL 会变更收藏和续播作用域；删除目录不会删除其历史记录。不会抓取第三方影视站。", lineHeight = 22.sp) }

                        item { Text("HERRIN M4.1 Alpha · 原生 Android TV\n内置影片仍是原创虚构演示，收藏和观看记录只保存在本机。\n可使用自己授权的 HTTPS 目录、媒体与 M3U 直播列表；支持频道查找、分组、收藏筛选、手动刷新及可选 XMLTV / .xml.gz 节目单。\nTVBox 多仓与单仓可安全预览；Spider/JAR/JS 解析、第三方影视搜索播放仍未接入。\n方向键移动 · 确定选择 · 返回回到上一页", lineHeight = 28.sp) }
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
                                TvButton(if (film.id in favorites) "♥ 已收藏 · 取消" else "♡ 添加收藏", reduced = reduceMotion) {
                                    prefs.toggleFor(catalogScope, film.id)
                                    favorites = prefs.idsFor(catalogScope)
                                }
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
