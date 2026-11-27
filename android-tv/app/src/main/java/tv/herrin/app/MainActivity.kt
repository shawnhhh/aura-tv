package tv.herrin.app

import android.os.Bundle
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.herrin.app.nativeplayback.FongMiSessionAuthorization

/** Only one branded wordmark fade; no fixed multi-second launch choreography. */
internal object LaunchMotion {
    const val WORDMARK_HOLD_MS = 2250L
    const val FADE_MS = 340
    const val BREATH_MS = 2400L
    // Reveal 2250ms, then brighten 850ms and retain a brief visible peak
    // before the curtain may fade. Avoid cutting off the new native breathing.
    const val TARGET_ANIMATION_BUDGET_MS = WORDMARK_HOLD_MS + 850L + 300L + FADE_MS
}

/** Elapsed uptime from Activity launch to first interactive home.
 * Measure cold starts on TCL separately; Compose timing excludes process spawn. */
internal object StartupTiming {
    private var startedAt = 0L
    @Volatile var lastComposeReadyMs = -1L; private set
    @Volatile private var first = -1L
    @Volatile private var visible = -1L
    @Volatile private var complete = -1L
    @Volatile private var processToActivity = -1L
    @Volatile private var homeStarted = -1L
    @Volatile private var artworkStarted = -1L
    @Volatile private var networkStarted = -1L
    @Volatile private var brandHandoff = -1L
    private fun phase(name:String,value:Long){Log.i("HerrinStartupPhase","phase=$name activity_to_phase_ms=$value")}
    fun homeStart(){if(homeStarted<0){homeStarted=SystemClock.uptimeMillis()-startedAt;phase("home_init",homeStarted)}}
    fun artworkStart(){if(artworkStarted<0){artworkStarted=SystemClock.uptimeMillis()-startedAt;phase("artwork",artworkStarted)}}
    fun networkStart(){if(networkStarted<0){networkStarted=SystemClock.uptimeMillis()-startedAt;phase("network",networkStarted)}}
    fun brandExit(){if(brandHandoff<0){brandHandoff=SystemClock.uptimeMillis()-startedAt;phase("system_brand_exit",brandHandoff)}}
    fun wordmarkIsComplete()=complete>=0
    fun start() {
        startedAt = SystemClock.uptimeMillis()
        processToActivity = startedAt - android.os.Process.getStartUptimeMillis()
        first = -1; visible = -1; complete = -1; lastComposeReadyMs = -1
        homeStarted=-1;artworkStarted=-1;networkStarted=-1;brandHandoff=-1
    }
    fun firstDraw() { if (first < 0) {first = SystemClock.uptimeMillis() - startedAt;phase("first_cpu_draw",first)} }
    fun wordmarkVisible() { if (visible < 0) {visible = SystemClock.uptimeMillis() - startedAt;phase("wordmark_visible",visible)} }
    fun wordmarkComplete() { if (complete < 0) {complete = SystemClock.uptimeMillis() - startedAt;phase("wordmark_complete",complete)} }
    fun finish() {
        if (lastComposeReadyMs >= 0) return
        lastComposeReadyMs = SystemClock.uptimeMillis() - startedAt
        Log.i("HerrinStartup", summary())
    }
    fun summary() = "process_to_activity_ms=$processToActivity activity_to_first_draw_ms=$first " +
        "first_draw_to_wordmark_ms=${if (first >= 0 && visible >= 0) visible-first else -1} " +
        "wordmark_complete_ms=$complete activity_to_home_ms=$lastComposeReadyMs " +
        "home_init_ms=$homeStarted artwork_start_ms=$artworkStarted network_start_ms=$networkStarted system_brand_exit_ms=$brandHandoff"
}

/** Enabled explicitly by adb setprop log.tag.HerrinRemote DEBUG in diagnostics. */
internal object RemoteTrace {
    private const val TAG = "HerrinRemote"
    fun event(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }
    // Release builds may receive dozens of D-pad events per second. Avoid
    // allocating strings/focus diagnostics unless adb explicitly enables it.
    fun event(message: () -> String) {
        // Lint validates matching tags, and the message is still lazy.
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
    }
}

class MainActivity : ComponentActivity() {
    private var frameDiagnostics: UiFrameDiagnostics? = null
    private var presentation:StartupPresentation?=null
    // This is cleared by the real V13/home handoff, never by network prefetch.
    internal fun canPresentSourceWindow(): Boolean = presentation?.showing == false
    internal fun frameSummary(): String = frameDiagnostics?.summary() ?: "渲染指标未采集"
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) {
            presentation?.surfaceReady = true
            val requested=presentation?.restoreBrowseFocus?.invoke()==true
            RemoteTrace.event { "WINDOW_FOCUS_READY browse_request_issued=$requested" }
        }
    }
    override fun onDestroy() {
        frameDiagnostics?.close()
        frameDiagnostics = null
        super.onDestroy()
        presentation?.restoreBrowseFocus=null
        presentation=null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.start()
        super.onCreate(savedInstanceState)
        val startup=StartupPresentation().apply {
            surfaceReady = false
            // Explicit debug-only diagnostics; no consumer navigation entry.
            diagnostics = intent.action == "tv.herrin.app.DIAGNOSTICS" &&
                applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        }
        presentation=startup
        if(android.os.Build.VERSION.SDK_INT>=31) {
            splashScreen.setOnExitAnimationListener { native ->
                // Do not leave an opaque native layer over an already-running
                // Compose clock while waiting for another pre-draw callback.
                native.remove()
                StartupTiming.brandExit()
                startup.surfaceReady = true
            }
        }
        // Observe through the public Window.Callback contract without calling AndroidX restricted APIs.
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount >= 0)
                    RemoteFocusLatencyProbe.keyDown(event.keyCode)
                RemoteTrace.event { "ACTIVITY before code=${event.keyCode} action=${event.action} windowFocus=${hasWindowFocus()} viewFocus=${currentFocus?.javaClass?.simpleName}" }
                val handled = originalCallback.dispatchKeyEvent(event)
                RemoteTrace.event { "ACTIVITY after code=${event.keyCode} action=${event.action} handled=$handled" }
                return handled
            }
        }
        enableEdgeToEdge()
        // Display policy is applied by the home scene after the first branded frame.
        frameDiagnostics = UiFrameDiagnostics(window) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = MaterialTheme.colorScheme.onBackground) { StartupHost(startup) { state -> Herrin(state) } }
                SpiderAuthorizationHost()
            }
        }
    }
}

/** Native focusable controls work with D-pad/Enter; no pointer-only actions. */
@Composable
internal fun TvButton(label: String, modifier: Modifier = Modifier, reduced: Boolean = false,
                     selected: Boolean = false, quiet: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    // Native focus switches immediately. Only the *visual transform* animates,
    // using an interruptible spring in a GPU layer (no relayout or D-pad delay).
    val focusedScale = if (quiet) 1.028f else 1.042f
    val scale by animateFloatAsState(
        targetValue = if (focused && !reduced) focusedScale else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.89f, stiffness = 760f),
        label = "tv-pill-focus-scale"
    )
    val liftPx = with(LocalDensity.current) { 2.dp.toPx() }
    // A selected episode/line is persistent state, not a second white focus pill.
    val targetFill = when {
        focused -> Color.White
        selected -> Color(0xFF344B68)
        quiet -> Color.Transparent
        else -> Color(0x99343840)
    }
    // Primary D-pad selection must become visibly white in the SAME frame.
    // Previous 190ms animated background/text colors left a perceptible lag
    // even after native focus had already moved.
    val fill = targetFill
    val contentColor = if (focused) Color(0xFF111318) else Color.White
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
        Text(label, color = contentColor, fontSize = 14.sp)
    }
}

/** Compact cinematic tabs: one visual language for Chinese labels and icon controls.
 * D-pad focus remains immediate; only GPU scale is animated. */
@Composable
internal fun TvNavTab(
    label: String, modifier: Modifier = Modifier, reduced: Boolean = false,
    selected: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(
        if (focused && !reduced) 1.018f else 1f,
        if (reduced) snap() else tween(120),
        label = "nav-tab-focus"
    )
    val fill = when {
        focused -> Color(0xFFF4F4F4)
        selected -> Color(0xFF3A3D43)
        else -> Color.Transparent
    }
    val textColor = if (focused) Color(0xFF151515) else if (selected) Color(0xFFF5F5F5) else Color(0xFFBFC3CA)
    Box(modifier.graphicsLayer { scaleX = focusScale; scaleY = focusScale }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clip(RoundedCornerShape(22.dp))
        .background(fill)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center) {
        // Top250's Latin letters and numerals appear optically larger than CJK glyphs
        // at equal sp. Set it one step smaller for balanced visual height.
        Text(label, color = textColor,
            fontSize = if (label == "Top250") 12.sp else 13.sp,
            fontWeight = FontWeight.Medium)
    }
}

/** Search lives in the centered tab rail; settings remains independently on the far right.
 * Both glyphs share subdued stroke weights with the new typographic tabs. */
@Composable
private fun TvHeaderIconButton(
    gear: Boolean, modifier: Modifier = Modifier, reduced: Boolean = false,
    selected: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val focusScale by animateFloatAsState(
        if (focused && !reduced) 1.025f else 1f,
        if (reduced) snap() else tween(120),
        label = "header-icon-focus"
    )
    val foreground = if (focused && !gear) Color(0xFF151515) else Color(0xFFF5F5F5)
    val label = if (gear) "设置" else "搜索"
    Box(modifier.size(if (gear) 35.dp else 33.dp)
        .graphicsLayer { scaleX = focusScale; scaleY = focusScale }
        .semantics { contentDescription = label }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clip(shape)
        .background(if (focused && gear) Color.White.copy(alpha = 0.16f) else if (focused) Color(0xFFF4F4F4)
            else if (selected && !gear) Color(0xFF3A3D43) else Color.Transparent)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (gear) 18.dp else 17.dp)) {
            val d = size.minDimension
            val centerAt = Offset(d / 2f, d / 2f)
            if (!gear) {
                drawCircle(foreground, radius = d * 0.28f,
                    center = Offset(d * 0.40f, d * 0.40f),
                    style = Stroke(width = d * 0.09f))
                drawLine(foreground,
                    Offset(d * 0.61f, d * 0.61f),
                    Offset(d * 0.88f, d * 0.88f),
                    strokeWidth = d * 0.11f, cap = StrokeCap.Round)
            } else {
                val outline = androidx.compose.ui.graphics.Path()
                for (index in 0..160) {
                    val angle = index * 2.0 * kotlin.math.PI / 160.0
                    val radius = d * (0.365 + 0.055 * kotlin.math.cos(angle * 8))
                    val x = centerAt.x + (kotlin.math.cos(angle) * radius).toFloat()
                    val y = centerAt.y + (kotlin.math.sin(angle) * radius).toFloat()
                    if (index == 0) outline.moveTo(x,y) else outline.lineTo(x,y)
                }
                outline.close()
                drawPath(outline, foreground, style = Stroke(width = d * 0.075f, join = StrokeJoin.Round))
                drawCircle(foreground, radius = d * 0.13f, center = centerAt,
                    style = Stroke(width = d * 0.075f))
            }
        }
    }
}

/** Nine text tabs plus the search icon form one *centered* navigation group. */
@Composable
private fun TvNavTabs(
    category: String,
    isSearch: Boolean,
    focusId: String,
    reducedMotion: Boolean,
    downTarget: FocusRequester?,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSearch: () -> Unit
) {
    val categories = remember {
        listOf("首页", "电影", "剧集", "动漫", "综艺", "纪录片", "Top 250", "直播", "我的")
    }
    Row(
        Modifier.testTag("nav-shell")
            .clip(RoundedCornerShape(50.dp))
            .background(Color(0xAD121820))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(50.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { cat ->
            TvNavTab(if (cat == "Top 250") "Top250" else cat,
                Modifier.testTag("nav-$cat")
                    .then(if (downTarget != null) Modifier.focusProperties { down = downTarget }
                        else Modifier)
                    .focusRequester(requester("nav-$cat")),
                reduced = reducedMotion, selected = category == cat && !isSearch,
                onFocus = { onFocus("nav-$cat") }
            ) { onSelect(cat) }
        }
        TvHeaderIconButton(gear = false,
            modifier = Modifier.testTag("search")
                .then(if (downTarget != null) Modifier.focusProperties { down = downTarget }
                    else Modifier)
                .focusRequester(requester("search")),
            reduced = reducedMotion, selected = isSearch,
            onFocus = { onFocus("search") }, onClick = onSearch)
    }
}

/** Lazy chip rows retain native D-pad horizontal scroll and never demand a pointer. */
@Composable
private fun FacetRail(
    caption: String,
    key: String,
    options: List<String>,
    selected: String?,
    reduced: Boolean,
    downTarget: FocusRequester? = null,
    onFocus: (String) -> Unit,
    onSelect: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("$caption · ${options.size} 项", fontSize = 17.sp,
            color = Color(0xFFE8EDF5), fontWeight = FontWeight.Medium)
        LazyRow(Modifier.testTag("facet-rail-$key"),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            item(key = "all") {
                TvButton("全部", Modifier.testTag("facet-$key-all")
                    .then(if (downTarget != null) Modifier.focusProperties { down = downTarget }
                        else Modifier), reduced,
                    selected = selected == null, quiet = true,
                    onFocus = { onFocus("facet-$key-all") }) { onSelect(null) }
            }
            itemsIndexed(options, key = { _, name -> name }) { index, name ->
                TvButton(name, Modifier.testTag("facet-$key-$index")
                    .then(if (downTarget != null) Modifier.focusProperties { down = downTarget }
                        else Modifier), reduced,
                    selected = selected == name, quiet = true,
                    onFocus = { onFocus("facet-$key-$index") }) { onSelect(name) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun Herrin(startup: StartupPresentation) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val inputModes=LocalInputModeManager.current
    val inputMethod = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    var searchInputFocused by remember { mutableStateOf(false) }
    var configInputFocused by remember { mutableStateOf(false) }
    var liveSearchEditorFocused by remember { mutableStateOf(false) }
    var pendingLiveSearchFocus by remember { mutableStateOf(false) }
    val prefs = remember(startup.preferences) { requireNotNull(startup.preferences) }
    val uiScope = rememberCoroutineScope()
    var lanConfigOpen by remember { mutableStateOf(false) }
    val phoneTokenReload = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var exitConfirmation by remember { mutableStateOf(false) }
    var lastExitDismissedAt by remember { zeroLongState() }
    val personalImdbRatings = remember(context) { HerrinImdbRatings(context) }
    var imdbRatingsEpoch by remember { zeroIntState() }
    var imdbImportStatus by remember { mutableStateOf("IMDb 数据需用户自行导入；不随开源 APK 分发") }
    val imdbImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) uiScope.launch {
            imdbImportStatus = "正在本机导入 IMDb ratings TSV.gz；不上传文件…"
            val result = runCatching { personalImdbRatings.importUserFile(uri) }
            imdbImportStatus = result.fold(
                onSuccess = { count ->
                    imdbRatingsEpoch++
                    "已导入 " + count + " 条本地 IMDb 评分（仅保存在本设备）"
                },
                onFailure = { "IMDb 数据导入失败，请确认文件为 title.ratings.tsv.gz" }
            )
        }
    }

    val profileStore = remember(startup.preferences) { requireNotNull(startup.profiles) }
    val watchHistory = remember(context) { PlaybackHistory(context) }
    val demoCatalog: CatalogSource = remember { DemoCatalog() }
    val demoFilms = remember { demoCatalog.titles() }
    val providerRepo = remember(context) { RemoteCatalogRepository(context) }
    val posterCache = remember(context) { PosterCacheMaintenance(context) }
    var deviceStatus by remember { mutableStateOf("选择「查看设备状态」读取当前输出刷新率") }
    var imageCdnStatus by remember { mutableStateOf("图片 CDN 尚未探测，与 API 状态独立") }
    var posterCacheStatus by remember { mutableStateOf("海报缓存不会影响收藏或观看历史") }
    var confirmCacheClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmCacheClear) {
        if (confirmCacheClear) {
            delay(15_000)
            confirmCacheClear = false
        }
    }
    val liveRepo = remember { RemoteLivePlaylistRepository() }
    val tvBoxLiveRepo = remember { TvBoxLiveRepository() }
    val tvBoxLiveStore = remember(context) { TvBoxLiveStore(context) }
    val epgRepo = remember { RemoteLiveEpgRepository() }
    val liveFavorites = remember(context) { LiveFavorites(context) }
    var liveChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var showFavoriteChannelsOnly by rememberSaveable { mutableStateOf(false) }
    var selectedLiveGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var liveSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var liveSearchQuery by rememberSaveable { mutableStateOf("") }
    val liveChannelRailState = rememberLazyListState()
    var liveFavoriteEpoch by remember { zeroIntState() }
    var liveStatus by remember { mutableStateOf("请在设置中配置自己的授权 HTTPS M3U 直播列表") }
    var epgProgrammes by remember { mutableStateOf<List<LiveProgramme>>(emptyList()) }
    var epgStatus by remember { mutableStateOf("未配置 XMLTV 节目单") }
    var highlightedLiveUrl by remember { mutableStateOf<String?>(null) }
    var epgRefresh by remember { zeroIntState() }
    var liveClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var liveRefresh by remember { zeroIntState() }
    val extraLiveCache = remember { mutableMapOf<String, List<LiveChannel>>() }
    val liveFavoriteScope = if (prefs.livePlaylistUrl.isNotBlank()) prefs.livePlaylistUrl else "tvbox-selected-live"
    LaunchedEffect(epgRefresh, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
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
        for (entry in withContext(Dispatchers.IO) { AdditionalServiceConfigs(context).enabled("live") }) {
            if (entry.epg.isNotBlank()) {
                val result = epgRepo.load(entry.epg)
                if (result is LiveEpgResult.Ready) epgProgrammes = (epgProgrammes + result.programmes).distinct()
            }
        }
    }
    LaunchedEffect(liveRefresh, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        val endpoint = prefs.livePlaylistUrl
        val previousExtraUrls = extraLiveCache.values.flatten().mapTo(hashSetOf()) { it.url }
        liveChannels = liveChannels.filterNot { it.url in previousExtraUrls }
        // Direct M3U always has priority. TVBox is a per-channel backup,
        // never allowed to silently shadow a working user-supplied playlist.
        // A cached TVBox playlist is not authorization to activate it.
        // Only a playlist the user explicitly applied in current preferences
        // may participate as a live source or backup after relaunch/reset.
        val tvBoxBackup = withContext(Dispatchers.IO) { if (prefs.tvBoxLiveActive) tvBoxLiveStore.load() else emptyList() }
        if (endpoint.isBlank()) {
            liveChannels = tvBoxBackup
            liveStatus = if (tvBoxBackup.isEmpty())
                "请在设置中配置自己的授权 M3U 或 TVBox 直播源"
            else "已加载 ${tvBoxBackup.size} 条 TVBox 备用直播线路"
        } else {
            liveStatus = "正在优先读取独立直播源…"
            when (val loaded = liveRepo.load(endpoint)) {
                is LivePlaylistResult.Ready -> {
                    liveChannels = HerrinLivePriority.combine(loaded.channels, tvBoxBackup)
                    liveStatus = "独立直播源 ${loaded.channels.size} 条优先；TVBox 备用 ${tvBoxBackup.size} 条"
                }
                is LivePlaylistResult.Error -> {
                    liveChannels = tvBoxBackup.ifEmpty { liveChannels }
                    liveStatus = if (tvBoxBackup.isNotEmpty())
                        "独立直播源读取失败，已使用 ${tvBoxBackup.size} 条 TVBox 备用线路"
                    else if (liveChannels.isEmpty()) loaded.reason
                    else "${loaded.reason}；保留上次频道列表"
                }
            }
        }
        val extra = withContext(Dispatchers.IO) { AdditionalServiceConfigs(context).enabled("live") }
        extraLiveCache.keys.retainAll(extra.map { it.id }.toSet())
        for (entry in extra) {
            val result = liveRepo.load(entry.url)
            if (result is LivePlaylistResult.Ready) extraLiveCache[entry.id] = result.channels
        }
        val additional = extraLiveCache.values.flatten()
        if (additional.isNotEmpty()) {
            liveChannels = HerrinLivePriority.combine(liveChannels, additional)
            liveStatus += "；附加直播源 ${extra.size} 个"
        }
    }
    var personalSourceConfigEpoch by remember { zeroIntState() }
    val smbSessions=remember(context,personalSourceConfigEpoch){HerrinSmbSessions()}
    DisposableEffect(smbSessions){onDispose{smbSessions.close()}}

    val tmdbState=remember { TmdbScreenState() }
    val personalIndexRevision by PersonalLibrary.revisions.collectAsState()
    PersonalLibraryStartupImport(enabled = !startup.showing)
    val personalBrowseFilms = rememberPersonalBrowseFilms(personalIndexRevision, personalSourceConfigEpoch)

    SourceSearchLifecycle(tmdbState)
    var providerFilms by remember { mutableStateOf<List<Film>?>(null) }
    var providerStatus by remember { mutableStateOf("当前使用离线演示目录") }
    var filmId by rememberSaveable { mutableStateOf(demoFilms.first().id) }
    val phoneSourceImport = remember(context) { LanSourceImport(context) }
    var phoneSourceId by remember { mutableStateOf<String?>(null) }
    fun finishPhoneSource(expectedId: String?): Boolean {
        if (expectedId == null) return phoneSourceId == null
        if (expectedId != phoneSourceId)
            return phoneSourceId == null && phoneSourceImport.isActive(expectedId)
        if (!phoneSourceImport.activate(expectedId)) return false
        phoneSourceId = null
        return true
    }
    fun failPhoneSource(code: String) {
        phoneSourceId?.let { phoneSourceImport.update(it, "FAILED", code) }
        phoneSourceId = null
    }
    var configuredTvBoxUrl by remember { mutableStateOf(prefs.tvBoxWarehouseUrl) }
    // Default settings are deliberately just two source inputs. Legacy diagnostics
    // and import tools remain behind an explicit advanced page.
    var settingsTvBoxOnly by rememberSaveable { mutableStateOf(true) }
    var playbackPicker by remember { mutableStateOf<String?>(null) }
    var savedProfiles by remember { mutableStateOf(startup.initialProfiles) }
    var activeProfileId by remember { mutableStateOf(startup.initialProfileId) }
    var sourceNameDraft by rememberSaveable { mutableStateOf("") }
    // Treat the private TMDB token as a password: never display a saved token,
    // log it, or put the value into Android saved-instance-state bundles.
    var tmdbTokenDraft by remember { mutableStateOf("") }
    // Never put secret fragments in rememberSaveable / Android saved-state.
    var tmdbTokenSplitMode by remember { mutableStateOf(false) }
    var tmdbTokenPart1 by remember { mutableStateOf("") }
    var tmdbTokenPart2 by remember { mutableStateOf("") }
    var tmdbTokenPart3 by remember { mutableStateOf("") }
    fun candidateTmdbToken(): String? = TmdbTokenEntry.assemble(
        tmdbTokenDraft, listOf(tmdbTokenPart1, tmdbTokenPart2, tmdbTokenPart3),
        tmdbTokenSplitMode
    )
    fun clearTmdbInput() {
        tmdbTokenDraft = ""
        tmdbTokenPart1 = ""
        tmdbTokenPart2 = ""
        tmdbTokenPart3 = ""
    }
    fun toggleTmdbSplitInput() {
        if (tmdbTokenSplitMode) {
            tmdbTokenDraft = tmdbTokenPart1 + tmdbTokenPart2 + tmdbTokenPart3
            tmdbTokenPart1 = ""
            tmdbTokenPart2 = ""
            tmdbTokenPart3 = ""
        } else {
            val parts = TmdbTokenEntry.split(tmdbTokenDraft)
            tmdbTokenPart1 = parts[0]
            tmdbTokenPart2 = parts[1]
            tmdbTokenPart3 = parts[2]
            tmdbTokenDraft = ""
        }
        tmdbTokenSplitMode = !tmdbTokenSplitMode
    }
    var tmdbSettingsStatus by remember { mutableStateOf(if (prefs.tmdbReadToken.isBlank())
        "尚未配置；仅使用影视源自带的海报" else "已配置 TMDB；会优先匹配高清背景图") }
    var configUrlDraft by rememberSaveable { mutableStateOf(prefs.catalogUrl) }
    var liveUrlDraft by rememberSaveable { mutableStateOf(prefs.livePlaylistUrl) }
    var epgUrlDraft by rememberSaveable { mutableStateOf(prefs.liveEpgUrl) }
    var importProfilesDraft by rememberSaveable { mutableStateOf("") }
    val tvBoxRepository = remember(context) { TvBoxConfigRepository(context, onImportHttpStatus = {
        HerrinPlaybackDiagnosticJournal.record("CONFIG", "WAREHOUSE_HTTP_RESPONSE", "STATUS=$it")
    }) }
    val tvBoxVodRepo = remember { TvBoxVodRepository() }
    val spiderBridge = remember(context) { TvBoxSpiderBridge(context) }
    val siteChoiceEpoch = remember { java.util.concurrent.atomic.AtomicLong() }
    val siteActivationJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    // Join an in-flight first-node CMS load instead of issuing a duplicate request.
    var autoCmsRequest by remember { mutableStateOf<TvBoxSite?>(null) }
    DisposableEffect(spiderBridge) { onDispose { spiderBridge.close() } }
    // Applying a source is the explicit user action that permits its Spider.
    // On subsequent launches, auto-run only the exact previously accepted JAR+MD5.
    var tvBoxRunSpiderAfterApply by remember { mutableStateOf(false) }
    var tvBoxColdStartRequest by remember { mutableStateOf(false) }
    var spiderActive by remember { mutableStateOf(false) }
    var spiderStatus by remember { mutableStateOf("尚未选择 Spider 站点") }
    var playerRetryEpoch by remember { zeroIntState() }
    var spiderResolved by remember { mutableStateOf<PlaybackResult?>(null) }
    var spiderResolvedFor by remember { mutableStateOf<HerrinAutomaticPlayback.Attempt?>(null) }
    var tvBoxVodScope by remember { mutableStateOf<String?>(null) }
    var activeCmsSite by remember { mutableStateOf<TvBoxSite?>(null) }
    var cmsDetailStatus by remember { mutableStateOf("") }
    var detailLoadEpoch by remember { zeroIntState() }
    // Only the selected CMS title may resolve its missing episodes inside
    // the regular player loading surface. No catalog-wide eager scans.
    var cmsPlaybackPendingId by remember { mutableStateOf<String?>(null) }
    var cmsPlaybackFailedId by remember { mutableStateOf<String?>(null) }
    var tvBoxUrlDraft by rememberSaveable { mutableStateOf(prefs.tvBoxWarehouseDisplayUrl) }
    // Large pasted backups should never be marshalled into an Activity saved-state Bundle.
    var tvBoxJsonDraft by remember { mutableStateOf("") }
    var tvBoxLiveUrlDraft by rememberSaveable { mutableStateOf(prefs.tvBoxLiveSourceUrl) }
    // HTTP metadata/playlist access is enabled by default; plugin execution is
    // limited to a user-applied source or a previously pinned JAR fingerprint.
    var tvBoxAllowHttp by remember { mutableStateOf(true) }
    SideEffect { spiderBridge.configureKernelWarehouse(configuredTvBoxUrl, tvBoxAllowHttp) }
    val kernelPages = remember(context) { HerrinKernelRuntime.find()?.pages }
    LaunchedEffect(kernelPages?.revision) {
        if (kernelPages != null) {
            providerFilms = providerFilms?.map { item ->
                kernelPages.latest(item)?.let { FilmMetadataEnrichment.mergeSourceDetails(item, it) } ?: item
            }
            tmdbState.tmdbResolved.toMap().forEach { (id, metadata) ->
                kernelPages.latest(metadata)?.let { fresh ->
                    val source = fresh.spiderOrigin?.site
                    if (source == spiderBridge.approvedActiveSiteKey()) {
                        tmdbState.tmdbResolved[id] = FilmMetadataEnrichment.mergeSourceDetails(metadata, fresh)
                        if (tmdbState.tmdbPlaybackRoutes.sourceFilm(id) != fresh)
                            tmdbState.tmdbPlaybackRoutes.spider(id, requireNotNull(source), fresh)
                    }
                }
            }
        }
    }
    var tvBoxRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var tvBoxImportJsonRequest by remember { mutableStateOf<String?>(null) }
    var tvBoxLoadEpoch by remember { zeroIntState() }
    var tvBoxStatus by remember { mutableStateOf("TVBox 多仓仅读取配置元数据，需手动输入并点击读取") }
    var vodImportFeedback by remember { mutableStateOf("") }
    var liveImportFeedback by remember { mutableStateOf("") }
    var tvBoxWarehouses by remember { mutableStateOf<List<TvBoxWarehouse>>(emptyList()) }
    var tvBoxSingle by remember { mutableStateOf<TvBoxConfig.Single?>(null) }
    var tvBoxShowAllSites by remember { mutableStateOf(false) }
    var tvBoxHerrinCatalog by remember { mutableStateOf<TvBoxConfig.HerrinCatalog?>(null) }
    var tvBoxSelectedName by remember { mutableStateOf("") }
    LaunchedEffect(personalSourceConfigEpoch,tvBoxSingle,tvBoxWarehouses){
        tmdbState.tmdbPlaybackRoutes.clear();tmdbState.tmdbPersonalPlayback.clear()
        tmdbState.selectedDiscoveryNode.clear()
        tmdbState.requestedDiscoveryHits.clear()
        tmdbState.loadedDiscoveryHits.clear()
        tmdbState.autoLayoutSelected.clear()
        tmdbState.discoveredSources.clear()
        for(id in tmdbState.tmdbResolved.keys.toList())tmdbState.tmdbResolved[id]?.let{old->
            tmdbState.tmdbResolved[id]=old.copy(playbackEpisodes=emptyList(),cmsVodId=null,spiderVodId=null)
        }
    }
    LaunchedEffect(Unit) {
        val pending = phoneSourceImport.current()?.takeIf { it.state in setOf("SAVED", "AWAITING_SITE") }
        if (pending != null) {
            phoneSourceId = pending.id
            tvBoxUrlDraft = pending.displayUrl
            snapshotFlow { startup.networkReady }.first { it }
            if (phoneSourceImport.current()?.id != pending.id) return@LaunchedEffect
            tvBoxRequest = pending.url to true
            tvBoxLoadEpoch++
        } else if (configuredTvBoxUrl.isNotBlank()) {
            val saved = withContext(Dispatchers.IO) {
                TvBoxCatalogSnapshot(context).load(
                    configuredTvBoxUrl, prefs.tvBoxLastWorkingSiteId)
            }
            if (saved.isNotEmpty()) {
                providerFilms = saved
                filmId = saved.first().id
                providerStatus = "已恢复上次片库 · 正在连接来源以获取实时选集"
            }
            snapshotFlow { startup.networkReady }.first { it }
            tvBoxColdStartRequest = true
            tvBoxRequest = configuredTvBoxUrl to true
            tvBoxLoadEpoch++
        }
    }
    LaunchedEffect(tvBoxLoadEpoch, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        val (address, isRoot) = tvBoxRequest ?: return@LaunchedEffect
        val nodeSelectionTicket = siteChoiceEpoch.incrementAndGet()
        siteActivationJob[0]?.cancel()
        autoCmsRequest = null
        val pendingImportId = phoneSourceId
        val phoneIdAtStart = phoneSourceImport.current()?.id
        val runSpiderAfterApply = tvBoxRunSpiderAfterApply
        val preferCache = tvBoxColdStartRequest
        tvBoxColdStartRequest = false
        tvBoxRunSpiderAfterApply = false
        tvBoxStatus = "正在读取 TVBox 配置…"
        vodImportFeedback = "读取中…"
        val imported = tvBoxImportJsonRequest
        tvBoxImportJsonRequest = null
        val loadedConfig = if (imported != null)
            tvBoxRepository.importJson(imported, address, tvBoxAllowHttp)
            else tvBoxRepository.loadUserWarehouse(address, preferCached = preferCache,
                allowCachedFallback = preferCache)
        if (nodeSelectionTicket != siteChoiceEpoch.get() ||
            phoneSourceImport.current()?.id != phoneIdAtStart || phoneSourceId != pendingImportId) return@LaunchedEffect
        if (loadedConfig is TvBoxConfig.Error) {
            HerrinPlaybackDiagnosticJournal.record("CONFIG", "WAREHOUSE_LOAD_FAILED")
            tvBoxStatus = loadedConfig.reason
            vodImportFeedback = "配置读取失败"
            val failureCode = LanSourceImport.failure(loadedConfig.reason)
            HerrinPlaybackDiagnosticJournal.record("CONFIG", failureCode)
            failPhoneSource(failureCode)
            return@LaunchedEffect
        }
        val parsed = LanWarehouseCommitPolicy.isParsedTvBox(loadedConfig)
        if (pendingImportId != null) {
            if (!parsed) { failPhoneSource("SOURCE_BODY_INVALID"); return@LaunchedEffect }
            val saved = runCatching { finishPhoneSource(pendingImportId) }.getOrDefault(false)
            if (!saved) { failPhoneSource("SOURCE_SAVE_FAILED"); return@LaunchedEffect }
            configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
            tvBoxUrlDraft = prefs.tvBoxWarehouseDisplayUrl
            providerFilms = null
            vodImportFeedback = "影视仓配置已保存"
        } else if (isRoot && parsed && !preferCache) {
            if (!phoneSourceImport.saveFromTv(address, phoneIdAtStart)) {
                HerrinPlaybackDiagnosticJournal.record("CONFIG", "SOURCE_SAVE_FAILED")
                vodImportFeedback = "配置保存失败"
                return@LaunchedEffect
            }
            configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
            tvBoxUrlDraft = prefs.tvBoxWarehouseDisplayUrl
            providerFilms = null
        }
        HerrinPlaybackDiagnosticJournal.record("CONFIG", "WAREHOUSE_LOAD_PARSED")
        spiderActive = false
        activeCmsSite = null
        spiderBridge.close()
        // Candidate metadata must never reuse the previous single warehouse's
        // sites when the new root is a multi-warehouse list.
        tvBoxSingle = null
        tvBoxShowAllSites = false
        tvBoxHerrinCatalog = null
        when (val result = loadedConfig) {
            is TvBoxConfig.Warehouses -> {
                tvBoxWarehouses = result.entries
                if (isRoot && result.entries.isNotEmpty()) {
                    val first = result.entries.first()
                    tvBoxSelectedName = first.name
                    tvBoxRunSpiderAfterApply = runSpiderAfterApply
                    tvBoxColdStartRequest = preferCache
                    tvBoxRequest = first.url to false
                    tvBoxLoadEpoch++
                }
                tvBoxStatus = "${if (result.fromCache) "已使用本地缓存；" else ""}已识别 ${result.entries.size} 个仓库${if (result.ignored > 0) "；跳过 ${result.ignored} 个无效／重复条目" else ""}。选中下方仓库可预览。"
                if (settingsTvBoxOnly) {
                    // Multi-warehouse import is metadata-only. TVBox users
                    // choose a warehouse; downloading up to five nested
                    // configs here was a serial startup/network bottleneck.
                    tvBoxStatus += "；已导入仓库列表，请打开「选择影视点」按需选择仓库"
                }
            }
            is TvBoxConfig.Single -> {
                if (isRoot) tvBoxWarehouses = emptyList()
                tvBoxSingle = result
                tvBoxStatus = "${if (result.fromCache) "已使用本地缓存；" else ""}${if (result.wrappedInImage) "已解开图片内嵌配置；" else ""}已识别单仓：${result.sites.size} 个站点，${result.lives.size} 组直播配置；可尝试自动加载 Java Spider，其他运行时可能不兼容。"
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
        // Cache-first on startup, refresh in the background for the next
        // launch. Never replace the active browsing state mid-remote action.
        if (((loadedConfig is TvBoxConfig.Single && loadedConfig.fromCache) ||
                (loadedConfig is TvBoxConfig.Warehouses && loadedConfig.fromCache))) {
            launch(Dispatchers.IO) { tvBoxRepository.loadUserWarehouse(address) }
        }
        if (tvBoxSingle != null) {
            spiderBridge.configureKernelWarehouse(address, tvBoxAllowHttp)
            // Import must activate an actual catalog, not just display 49 names
            // under Settings while the home page stays on the demo collection.
            val config = tvBoxSingle!!
            val savedSite = prefs.tvBoxLastWorkingSiteId.takeIf {
                prefs.tvBoxLastWorkingRootUrl == prefs.tvBoxWarehouseUrl
            }.orEmpty()
            // TVBox-style import must not probe all third-party plugins.
            // Restore at most the previously selected site on startup.
            val declared = TvBoxAutoSites.initialSelection(config, savedSite)
            val initialSite = declared?.takeIf {
                it.apiUrl != null || TvBoxSpiderPolicy.mayRun(runSpiderAfterApply, prefs.tvBoxWarehouseUrl,
                    it.jar ?: config.spiderJar ?: it.scriptApi.orEmpty(),
                    prefs.tvBoxTrustedSpiderRootUrl, prefs.tvBoxTrustedSpiderJarSpec)
            } ?: config.sites.firstOrNull { it.apiUrl != null } ?: declared
            if (initialSite != null) prefs.preferSite(prefs.tvBoxWarehouseUrl.ifBlank { address }, initialSite, config.spiderJar)
            val candidates = listOfNotNull(initialSite)
            // Selecting metadata does not grant Spider execution: the existing
            // exact-root/JAR approval below still decides whether it may run.
            var chosen: Pair<TvBoxSite, List<Film>>? = null
            var chosenSpider = false
            var spiderFailure = ""
            var spiderCrashed = false
            var scanBudgetExceeded = false
            val scanStarted = SystemClock.elapsedRealtime()
            var cmsElapsed = 0L
            val hasRunnableSpider = candidates.any { site ->
                val jar = site.jar ?: config.spiderJar
                (site.spiderApi != null || site.scriptApi != null) &&
                    (jar != null || site.scriptApi != null) && TvBoxSpiderPolicy.mayRun(
                    runSpiderAfterApply, prefs.tvBoxWarehouseUrl, jar ?: site.scriptApi.orEmpty(),
                    prefs.tvBoxTrustedSpiderRootUrl, prefs.tvBoxTrustedSpiderJarSpec)
            }
            for ((candidateIndex, site) in candidates.withIndex()) {
                if (nodeSelectionTicket != siteChoiceEpoch.get()) break
                val elapsed = SystemClock.elapsedRealtime() - scanStarted
                if (elapsed >= TvBoxAutoScanPolicy.TOTAL_BUDGET_MS) {
                    scanBudgetExceeded = true
                    break
                }
                val timeout = TvBoxAutoScanPolicy.timeoutMs(
                    elapsed, cmsElapsed, hasRunnableSpider, spider = site.spiderApi != null || site.scriptApi != null)
                    ?: continue
                if (site.apiUrl != null) {
                    vodImportFeedback = "加载中…"
                    tvBoxStatus = "正在试读 ${site.name}（${candidateIndex + 1}/${candidates.size}）…"
                    val cmsStart = SystemClock.elapsedRealtime()
                    autoCmsRequest = site
                    val loaded = try {
                        withTimeoutOrNull(timeout) { tvBoxVodRepo.load(site, allowHttp = true) }
                    } finally {
                        if (autoCmsRequest == site) autoCmsRequest = null
                    }
                    cmsElapsed += SystemClock.elapsedRealtime() - cmsStart
                    when (loaded) {
                        is TvBoxVodResult.Ready -> {
                            chosen = site to loaded.films
                            break
                        }
                        is TvBoxVodResult.Error -> HerrinPlaybackDiagnosticJournal.record("CONFIG",
                            "DEFAULT_NODE_CATALOG_FAILED", "HTTP=${loaded.httpCode ?: 0}")
                        null -> HerrinPlaybackDiagnosticJournal.record("CONFIG", "DEFAULT_NODE_CATALOG_TIMEOUT")
                    }
                } else if (site.spiderApi != null || site.scriptApi != null) {
                    val jarSpec = site.jar ?: config.spiderJar ?: site.scriptApi ?: continue
                    // No silent execution of a changed or newly introduced JAR on startup.
                    if (!TvBoxSpiderPolicy.mayRun(runSpiderAfterApply, prefs.tvBoxWarehouseUrl,
                            jarSpec, prefs.tvBoxTrustedSpiderRootUrl,
                            prefs.tvBoxTrustedSpiderJarSpec)) continue
                    vodImportFeedback = "加载中…"
                    tvBoxStatus = "正在尝试 Spider ${site.name}（${candidateIndex + 1}/${candidates.size}）…"
                    val result = withTimeoutOrNull(timeout) { spiderBridge.browse(site, config) }
                    when (result) {
                        is SpiderResult.Failure -> {
                            spiderFailure = result.message
                            Log.w("HerrinCatVod", "站点 ${site.key}: ${result.message}")
                            if (TvBoxAutoScanPolicy.isSpiderProcessCrash(result.message)) {
                                spiderCrashed = true
                                break
                            }
                        }
                        is SpiderResult.Ready -> {
                            chosen = site to result.value
                            chosenSpider = true
                            if (nodeSelectionTicket == siteChoiceEpoch.get()) {
                                prefs.tvBoxTrustedSpiderRootUrl = prefs.tvBoxWarehouseUrl
                                prefs.tvBoxTrustedSpiderJarSpec = jarSpec
                            }
                            break
                        }
                        null -> spiderFailure = "Spider ${site.name} 响应超时"
                    }
                }
            }
            val currentNodeSelection = nodeSelectionTicket == siteChoiceEpoch.get() &&
                phoneSourceImport.current()?.id == phoneIdAtStart
            if (chosen != null) {
                if (!finishPhoneSource(pendingImportId)) return@LaunchedEffect
                configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
                if (currentNodeSelection) {
                    prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                    prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(chosen.first, config.spiderJar)
                }
            }
            // A valid phone-imported warehouse is not a failed configuration
            // merely because a CMS returns no films or a Spider awaits an
            // explicit TV-side execution approval. Activate the CONFIG below,
            // independently of whether a particular node can play.
            if (chosen == null && !spiderCrashed &&
                SystemClock.elapsedRealtime() - scanStarted >= TvBoxAutoScanPolicy.TOTAL_BUDGET_MS)
                scanBudgetExceeded = true
            if (chosen != null && currentNodeSelection) {
                if (!chosenSpider) spiderBridge.close()
                spiderActive = chosenSpider
                activeCmsSite = if (chosenSpider) null else chosen.first
                providerFilms = chosen.second
                providerStatus = "TVBox ${chosen.first.name} · ${chosen.second.size} 部"
                tvBoxVodScope = if (chosenSpider) "spider:${chosen.first.key}:${chosen.first.jar ?: config.spiderJar}"
                    else "tvbox:${chosen.first.apiUrl}"
                chosen.second.firstOrNull()?.let { filmId = it.id }
                tvBoxStatus += if (chosenSpider && chosen.second.isEmpty())
                    "；已恢复 ${chosen.first.name}，首页暂未返回影片，可继续搜索或检查来源授权"
                else "；已自动导入 ${chosen.first.name}，首页已切换到影视源内容"
                vodImportFeedback = "成功"
            } else if (currentNodeSelection) {
                if (phoneSourceId == null) {
                    providerFilms = null
                    activeCmsSite = null
                    tvBoxVodScope = "tvbox:pending:$address"
                }
                providerStatus = "已导入 ${config.sites.size} 个影视点；选择影视点后才加载影片目录"
                if (spiderCrashed) {
                    val evidence = withContext(Dispatchers.IO) {
                        listOfNotNull(SpiderStartupTrace.recent(context),SpiderExitDiagnostics.recent(context),
                            SpiderFailureTrace.recent(context)).joinToString("；")
                    }
                    tvBoxStatus = "配置读取成功，Spider 子进程已退出；可选择其他站点。" + evidence.take(1400)
                    vodImportFeedback = when {
                        evidence.contains(SpiderStartupTrace.Stage.WATCHDOG.display) -> "插件响应超时"
                        evidence.contains("崩溃") ||evidence.contains("未捕获异常") -> "插件崩溃"
                        else -> "插件进程退出"
                    }
                } else if (scanBudgetExceeded) {
                    tvBoxStatus = "配置读取成功，但自动检索已达到限时；请打开「选择影视点」手动尝试其他站点。" +
                        (if (spiderFailure.isNotBlank()) "最近失败：${spiderFailure.take(90)}" else "")
                    vodImportFeedback = "检索超时"
                } else if (candidates.isEmpty()) {
                    tvBoxStatus = "配置已导入 ${config.sites.size} 个影视点；无需逐个检测。请按「选择影视点」选择需要浏览的站点。"
                    vodImportFeedback = "已导入配置"
                } else {
                    tvBoxStatus += "；上次选定的影视点没有返回可用目录" +
                        (if (spiderFailure.isNotBlank()) "（${spiderFailure.take(80)}）" else "")
                    vodImportFeedback = "未获取到影片"
                }
            }
            // Always build a backup channel pool from user-applied TVBox
            // warehouses, EVEN when an independent M3U is configured. The
            // independent playlist remains first in HerrinLivePriority.combine.
            if (prefs.tvBoxLiveSourceUrl.isBlank()) {
                var backups = tvBoxLiveStore.load()
                var added = false
                for (live in tvBoxSingle!!.lives.take(4)) {
                    val loaded = if (live.channels.isNotEmpty()) {
                        val channels = live.channels.filter { TvBoxUrl.normalize(it.url, true) != null }
                        if (channels.isEmpty()) TvBoxLiveResult.Error("频道地址无效")
                        else TvBoxLiveResult.Ready(channels)
                    } else if (live.sourceUrl != null) tvBoxLiveRepo.load(live.sourceUrl, true)
                    else continue
                    if (loaded is TvBoxLiveResult.Ready) {
                        val merged = HerrinLivePriority.combine(backups, loaded.channels).take(250)
                        if (merged.size > backups.size) {
                            backups = merged
                            added = true
                        }
                    }
                }
                if (added && tvBoxLiveStore.save(backups, true)) {
                    if (prefs.livePlaylistUrl.isBlank()) prefs.tvBoxLiveActive = true
                    liveRefresh++
                    tvBoxStatus += "；已备份 ${backups.size} 条 TVBox 直播线路（独立直播源优先）"
                }
            }
        }
        // Multi-warehouse lists are valid; lack of a Single is not failure.
        if (tvBoxSingle == null && tvBoxWarehouses.isEmpty() &&
            loadedConfig !is TvBoxConfig.HerrinCatalog) vodImportFeedback = "导入失败"
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
    var catalogRefresh by remember { zeroIntState() }
    val tmdbPrimary = prefs.tmdbReadToken.isNotBlank()
    val extraNasConfigured = remember(personalSourceConfigEpoch) { AdditionalServiceConfigs(context).enabled("nas").isNotEmpty() }
    val personalConfigured = remember(personalSourceConfigEpoch) {
        context.getSharedPreferences("herrin", 0).getString("personal_connection_ids", "[]") != "[]" || HerrinSmbConfigStore(context).configured()
    }
    val sourceConfigured = personalConfigured || extraNasConfigured || tmdbPrimary || configuredTvBoxUrl.isNotBlank() || tmdbState.tmdbBrowseIndex.isNotEmpty() ||
        prefs.nasWebDavUrl.isNotBlank()
    var recentIndexEpoch by remember { zeroIntState() }
    var recentIndexFilms by remember { mutableStateOf<List<Film>>(emptyList()) }
    LaunchedEffect(recentIndexEpoch) { recentIndexFilms = withContext(Dispatchers.IO) { watchHistory.indexedFilms() } }
    val resolvedSnapshot = tmdbState.tmdbResolved.toMap()
    val catalogFilms = remember(tmdbPrimary, tmdbState.tmdbBrowseIndex, tmdbState.tmdbTop250Films, resolvedSnapshot, providerFilms, sourceConfigured) { if (tmdbPrimary) {
        (tmdbState.tmdbBrowseIndex + tmdbState.tmdbTop250Films).distinctBy(Film::id)
            .map { resolvedSnapshot[it.id] ?: it }
    } else providerFilms ?: if (sourceConfigured) emptyList() else demoFilms }
    val films = remember(catalogFilms, recentIndexFilms, resolvedSnapshot) {
        (catalogFilms + recentIndexFilms.map { resolvedSnapshot[it.id] ?: it }).distinctBy(Film::id)
    }
    // Source settings can change independently of the TMDB title. Do not keep
    // old playable URLs or credential maps after NAS/cloud/TVBox is reconfigured.
    LaunchedEffect(personalSourceConfigEpoch, tvBoxSingle, tvBoxWarehouses, tvBoxAllowHttp) {
        tmdbState.tmdbResolved.keys.toList().forEach { id ->
            tmdbState.tmdbResolved[id]?.let { resolved ->
                tmdbState.tmdbResolved[id] = resolved.copy(
                    playbackEpisodes = emptyList(), cmsVodId = null, spiderVodId = null)
            }
        }
        tmdbState.tmdbPersonalPlayback.clear()
        tmdbState.tmdbPlaybackRoutes.clear()
        tmdbState.tmdbFallbackIndex.clear()
        tmdbState.sourceDiscoveries.clear()
        tmdbState.discoveredSources.clear()
        tmdbState.autoLayoutSelected.clear()
        tmdbState.selectedDiscoveryNode.clear()
        tmdbState.requestedDiscoveryHits.clear(); tmdbState.loadedDiscoveryHits.clear()
        tmdbState.discoveryLoadingFilm = null
        HerrinDiscoveryWarehouses.clear()
        tmdbState.discoverySelection = null
        tmdbState.automaticPlayback.clear()
        tmdbState.automaticSearch = null
        tmdbState.automaticResume = null
        tmdbState.tmdbSpiderBatchOffsets.clear()
        tmdbState.tmdbLookupEpoch++
    }
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
            profileStore.list().size, prefs.livePlaylistUrl.isNotBlank()) +
            "\n系统模式：${supported.joinToString()}\n窗口请求：${activity.window.attributes.preferredRefreshRate}Hz" +
            "\nTMDB API：${TmdbEndpoints.current}（图片 CDN 独立）" +
            "\n" + (activity as? MainActivity)?.frameSummary().orEmpty() + MainExitDiagnostics.recent(context) + "\n" + CrashJournal.previous + "\n" + StartupTiming.summary() + "\n"+SourceSearchDiagnostics.summary() + "\n" + HerrinPlaybackDiagnosticJournal.export()
    }
    LaunchedEffect(catalogRefresh, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        // Once a TVBox URL has been applied, the legacy/demo catalog must not
        // race with TVBox import and silently put synthetic films back on Home.
        if (sourceConfigured) return@LaunchedEffect
        spiderActive = false
        val endpoint = prefs.catalogUrl
        // Never leave the previous source's films visible while the next URL loads.
        if (!sourceConfigured) providerFilms = null
        if (endpoint.isBlank()) {
            if (!sourceConfigured) providerStatus = "当前使用离线演示目录"
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
    // Debounced, private visual-only home snapshot. Never persist playable URIs.
    LaunchedEffect(providerFilms, tvBoxVodScope) {
        val entries = providerFilms.orEmpty()
        if (entries.isNotEmpty() && configuredTvBoxUrl.isNotBlank() &&
            tvBoxVodScope?.startsWith("spider:") == true ||
            entries.isNotEmpty() && configuredTvBoxUrl.isNotBlank() &&
            tvBoxVodScope?.startsWith("tvbox:") == true &&
            tvBoxVodScope?.startsWith("tvbox:pending:") != true) {
            delay(850)
            withContext(Dispatchers.IO) {
                TvBoxCatalogSnapshot(context).save(configuredTvBoxUrl,
                    prefs.tvBoxLastWorkingSiteId, entries)
            }
        }
    }
    var category by rememberSaveable { mutableStateOf("首页") }
    var screen by rememberSaveable { mutableStateOf("browse") }
    var personalOpenNotice by remember(screen, category) { mutableStateOf(false) }
    LaunchedEffect(personalOpenNotice) {
        if (personalOpenNotice) { delay(2400); personalOpenNotice = false }
    }
    LaunchedEffect(startup.diagnostics) {
        if (startup.diagnostics) { settingsTvBoxOnly = false; screen = "settings" }
    }
    val playbackScene = screen == "player" || screen == "live-player"
    LaunchedEffect(playbackScene, startup.showing) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        // Only scene boundaries change hints; episode/line changes never toggle the display mode.
        if (playbackScene) activity.releaseUiRefreshPreference()
        else if(!startup.showing) activity.preferSmoothTvUi()
    }
    // Media rekeys (refresh, episode and line) keep their active source. Only
    // leaving the player releases its Spider resources and next play rehydrates.
    val kernelPlaybackSceneLease = spiderBridge.kernelPlaybackLease()
    DisposableEffect(screen == "player", spiderActive, kernelPlaybackSceneLease) {
        val ownsPlayback = screen == "player" && spiderActive
        // Retire only the session that originally entered this playback scene.
        // A later site/warehouse load can coexist with a delayed old disposal.
        onDispose { if (ownsPlayback) spiderBridge.releasePlayback(kernelPlaybackSceneLease) }
    }

    LaunchedEffect(screen, filmId) {
        // A Back press during a slow CMS request must not leave a stale
        // pending lookup that can later attach to an unrelated navigation.
        if (screen != "player") {
            cmsPlaybackPendingId = null
            cmsPlaybackFailedId = null
        }
    }
    var facetsReturnScreen by rememberSaveable { mutableStateOf("browse") }
    var facetActor by rememberSaveable { mutableStateOf<String?>(null) }
    var facetDirector by rememberSaveable { mutableStateOf<String?>(null) }
    var facetRegion by rememberSaveable { mutableStateOf<String?>(null) }
    var facetYear by rememberSaveable { mutableStateOf<String?>(null) }
    var facetCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var facetRating by rememberSaveable { mutableStateOf<String?>(null) }
    val facetSelection = FilmFacetSelection(facetActor, facetDirector, facetRegion, facetYear,
        facetCategory, facetRating)
    LaunchedEffect(category, screen) {
        if (category == "直播" && screen == "browse") {
            while (true) {
                liveClock = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    var focusId by rememberSaveable { mutableStateOf("nav-首页") }
    var pendingToolbarFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var returnScreen by rememberSaveable { mutableStateOf("browse") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("全部") }
    var searchNewestFirst by rememberSaveable { mutableStateOf(false) }
    // Search has its own quick filter; the independent 我的 tab now shows
    // favorite films and resumeable playback from the same active catalog.
    var searchFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showSearchAdvancedFilters by rememberSaveable { mutableStateOf(false) }
    var episode by rememberSaveable { mutableIntStateOf(1) }
    var line by rememberSaveable { mutableStateOf("演示线路 A") }
    LaunchedEffect(kernelPages?.selectedCoordinate) {
        val coordinate = kernelPages?.selectedCoordinate
        if (coordinate != null && screen == "player") {
            episode = coordinate.first
            line = coordinate.second
        }
    }
    val playbackDiagnosticId = remember(screen, filmId, episode, line, playerRetryEpoch) { PlaybackDiagnosticSession.newId() }
    var detailSelectedSeason by rememberSaveable { zeroIntState() }
    var restoreSeasonFocus by remember { mutableStateOf(false) }
    var restoreGroupFocus by remember { mutableStateOf(false) }
    var groupFocusedCoordinates by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var bypassGroupFilmId by remember { mutableStateOf<String?>(null) }
    val detailSeasonLines = remember { mutableStateMapOf<Int, String>() }
    val detailSeasonEpisodeNumbers = remember { mutableStateMapOf<Int, Int>() }
    var activeLiveChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var activeLiveIndex by remember { mutableIntStateOf(-1) }
    var pendingLiveLines by remember { mutableStateOf<LiveChannelChoice?>(null) }
    LaunchedEffect(liveChannels, category, screen) {
        if (category == "直播" && screen == "browse" && liveChannels.isNotEmpty()) {
            if (activeLiveChannel?.url !in liveChannels.map { it.url })
                activeLiveChannel = liveChannels.first()
            screen = "live-player"
        }
    }
    val catalogScope = if (tmdbPrimary) "tmdb:discovery"
        else tvBoxVodScope ?: prefs.catalogUrl.ifBlank { "herrin-offline-demo" }
    // Only the source's playback identity survives enrichment. Every network
    // result modifies display fields in-place and never alters episode links,
    // favorites IDs or history keys.
    val metadataResolver = remember(context, catalogScope) {
        ArtworkMetadataResolver(java.io.File(context.cacheDir, "tmdb-artwork"), fetcher = MetadataFetchers.current, catalogScope = catalogScope)
    }
    // Reactive token: saving a key starts matching without relaunching the app.
    var metadataToken by remember { mutableStateOf(prefs.tmdbReadToken) }
    var personalMetadataToken by remember { mutableStateOf(metadataToken) }
    LaunchedEffect(metadataToken) {
        if (personalMetadataToken == metadataToken) return@LaunchedEffect
        personalMetadataToken = metadataToken
        if (metadataToken.isBlank()) return@LaunchedEffect
        // A newly configured token should enrich the retained index now,
        // without rescanning WebDAV or cancelling the current directory job.
        while (isActive) {
            if (!PersonalLibrary.awaitCurrentScan()) return@LaunchedEffect
            if (withContext(Dispatchers.IO) { PersonalLibrary(context).refreshMetadata() }) return@LaunchedEffect
            yield()
        }
    }
    var metadataRescanEpoch by remember { zeroIntState() }
    var tmdbConnectivity by remember { mutableStateOf(TmdbProbeState.NOT_CONFIGURED) }
    var tmdbProbeMessage by remember { mutableStateOf(TmdbProbeState.NOT_CONFIGURED.text) }
    // Must not be tied to metadataRescanEpoch: a successful probe increments
    // that epoch, which otherwise causes an endless configuration/search loop.
    var tmdbProbeEpoch by remember { zeroIntState() }
    LaunchedEffect(metadataToken, tmdbProbeEpoch, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        tmdbConnectivity = if (metadataToken.isBlank()) TmdbProbeState.NOT_CONFIGURED
            else TmdbProbeState.CHECKING
        tmdbProbeMessage = tmdbConnectivity.text
        CrashJournal.stage("PROBE")
        val report = if (metadataToken.isBlank())
            TmdbProbeReport(TmdbProbeState.NOT_CONFIGURED)
        else withContext(Dispatchers.IO) { TmdbConnectivityProbe.checkDetailed(metadataToken, MetadataFetchers.current) }
        tmdbConnectivity = report.state
        tmdbProbeMessage = report.text
        if (report.state == TmdbProbeState.CONNECTED) {
            metadataResolver.clearAuthCooldown(metadataToken)
            // Only a successful configuration AND search probe starts scanning.
            metadataRescanEpoch++
        }
    }
    val tmdbBrowseRepository = remember(context, MetadataFetchers.current, metadataToken) {
        TmdbBrowseRepository(MetadataFetchers.current, java.io.File(context.filesDir, "tmdb-browse-v2"))
    }
    val tmdbDiscovery = remember(context, metadataToken, tmdbBrowseRepository) {
        HerrinTmdbDiscovery(fetcher = MetadataFetchers.current, cacheDir = java.io.File(context.filesDir, "tmdb-home-pages"),
            browseClassification = { type, id, genres -> tmdbBrowseRepository.cachedClassification(type, id, genres, metadataToken) })
    }
    val tmdbBrowseState = remember(metadataToken, tmdbBrowseRepository) { TmdbBrowseState(tmdbBrowseRepository, metadataToken) }
    LaunchedEffect(tmdbBrowseState) {
        snapshotFlow { tmdbBrowseState.allFilms() }.collect { tmdbState.tmdbBrowseIndex = it }
    }

    val selectedSearchKey = if (screen == "search" && metadataToken.isBlank() && spiderActive)
        spiderBridge.approvedActiveSiteKey() else null
    val selectedSearchSite = if (selectedSearchKey != null)
        tvBoxSingle?.sites?.firstOrNull { it.spiderApi != null && it.key == selectedSearchKey }
        else null
    LaunchedEffect(screen, query, metadataToken, tmdbState.globalSearch.page, tmdbState.globalSearch.retry,
        selectedSearchSite, if (selectedSearchSite != null) tvBoxVodScope else null,
        selectedSearchSite != null && tmdbState.sourceForeground) {
        if (screen == "search") {
            if (query.trim().length >= 2) {
                val route = when {
                    metadataToken.isNotBlank() -> "SEARCH_ROUTE_TMDB"
                    !spiderActive -> "SEARCH_ROUTE_NO_SELECTED_SOURCE"
                    selectedSearchKey == null -> "SEARCH_ROUTE_NO_SITE_DESCRIPTOR"
                    selectedSearchSite == null -> "SEARCH_ROUTE_SITE_NOT_IN_CONFIG"
                    !tmdbState.sourceForeground -> "SEARCH_ROUTE_BACKGROUND"
                    else -> "SEARCH_ROUTE_SELECTED_SPIDER"
                }
                HerrinPlaybackDiagnosticJournal.record("SOURCE", route,
                    "COUNT=${query.trim().length.coerceAtMost(99999)}")
            }
            if (selectedSearchSite != null && !tmdbState.sourceForeground) return@LaunchedEffect
            val consent = spiderBridge.configurationTicket()
            val root = prefs.tvBoxWarehouseUrl
            val runtime = selectedSearchSite?.jar ?: tvBoxSingle?.spiderJar.orEmpty()
            val key = SourceHealthIndex.digest(if (selectedSearchSite == null) query + "|" + metadataToken
                else listOf(query, root, runtime, selectedSearchSite.key, consent,
                    tmdbState.globalSearch.retry).joinToString("|"))
            if (key != tmdbState.globalSearch.requestKey) {
                tmdbState.globalSearch.requestKey = key
                tmdbState.globalSearch.page = 1
                tmdbState.globalSearch.totalPages = 0
                tmdbState.globalSearch.status = if (selectedSearchSite == null) "正在查询 TMDB…"
                    else "正在搜索 ${selectedSearchSite.name}…"
            }
            if (tmdbState.globalSearch.page == 1) {
                tmdbState.globalSearch.films = emptyList()
                delay(350)
            }
            if (selectedSearchSite == null) tmdbState.globalSearch.load(tmdbDiscovery, query, metadataToken)
            else {
                tmdbState.globalSearch.loadSelectedSpider(query, selectedSearchSite.name) {
                    if (!TvBoxSpiderPolicy.mayRun(false, root, runtime,
                            prefs.tvBoxTrustedSpiderRootUrl, prefs.tvBoxTrustedSpiderJarSpec))
                        SpiderResult.Failure("SOURCE_SESSION_EXPIRED")
                    else withContext(Dispatchers.IO + SpiderSearchAuthority(consent)) {
                        spiderBridge.search(selectedSearchSite, query)
                    }
                }
                tmdbState.globalSearch.selectedSourceFailure?.let {
                    HerrinPlaybackDiagnosticJournal.record("SOURCE", it)
                }
            }
        }
    }
    LaunchedEffect(metadataToken) {
        CrashJournal.stage("CACHE")
        tmdbState.cacheHydrated=false
        tmdbState.tmdbBrowseIndex = emptyList()
        tmdbState.tmdbTop250Films = emptyList()
        if (metadataToken.isNotBlank()) {
            val saved = withContext(Dispatchers.IO) { TvBoxCatalogSnapshot(context, Long.MAX_VALUE).load("tmdb-library", "selected") }
            saved.filter { tmdbDiscovery.identity(it) != null }.forEach { tmdbState.tmdbResolved.putIfAbsent(it.id, it) }
            // Restore the bounded local index atomically before cached navigation is enabled.
            tmdbBrowseState.hydrate(TmdbBrowsePage.HOME)
            tmdbState.tmdbBrowseIndex = tmdbBrowseState.allFilms()
            tmdbState.cacheHydrated=true
            tmdbState.tmdbTop250Films = withContext(Dispatchers.IO) { tmdbDiscovery.cachedTop250(metadataToken) }
        }
        // Empty is a valid offline result only after the bounded local read;
        // before that it cannot signal that the cached homepage is ready.
        tmdbState.cacheHydrated=true
    }
    LaunchedEffect(category, metadataToken) {
        TmdbBrowsePage.from(category)?.let { tmdbBrowseState.hydrate(it) }
    }
    // Fetch only the selected page's independent rails, after cache hydration and first paint.
    LaunchedEffect(category, metadataToken, tmdbConnectivity, startup.networkReady, tmdbProbeEpoch, tmdbState.cacheHydrated) {
        val page = TmdbBrowsePage.from(category) ?: return@LaunchedEffect
        if (!startup.networkReady || !tmdbState.cacheHydrated ||
            tmdbConnectivity != TmdbProbeState.CONNECTED || metadataToken.isBlank()) return@LaunchedEffect
        tmdbState.tmdbDiscoveryStatus = "正在更新 TMDB 栏目，已有缓存保持可浏览"
        // Category entry and cancelled/restarted effects reuse fresh pages.
        // Explicit rail retry still forces a refresh through TmdbBrowseState.retry.
        tmdbBrowseState.refresh(page)
        tmdbState.tmdbDiscoveryStatus = "TMDB 栏目已更新；播放时优先检索 SMB／WebDAV"
    }
    LaunchedEffect(category, metadataToken, tmdbConnectivity, tmdbState.tmdbTop250RetryEpoch, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        if (category != "Top 250") return@LaunchedEffect
        if (metadataToken.isBlank()) {
            tmdbState.tmdbTop250Status = "未配置 TMDB 访问令牌，请在设置中配置后重试"
            return@LaunchedEffect
        }
        if (tmdbConnectivity != TmdbProbeState.CONNECTED) {
            tmdbState.tmdbTop250Status = tmdbProbeMessage
            return@LaunchedEffect
        }
        if (tmdbState.tmdbTop250Films.size >= 250) return@LaunchedEffect
        tmdbState.tmdbTop250Status = "正在载入 TMDB 高分榜，请稍候…"
        val shelf = try {
            tmdbDiscovery.loadTop250(metadataToken) { page ->
                if (page.films.isNotEmpty()) {
                    tmdbState.tmdbTop250Films = page.films
                    tmdbState.tmdbTop250Status = "已读取 ${page.films.size}/250 部高分电影，正在继续加载…"
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (_: java.io.IOException) { null }
        if (shelf == null) {
            tmdbState.tmdbTop250Status = "TMDB 高分榜请求失败；检查电视网络、DNS 或访问限制后重试"
        } else {
            if (shelf.films.isNotEmpty()) tmdbState.tmdbTop250Films = shelf.films
            tmdbState.tmdbTop250Status = when {
                shelf.isComplete -> "已加载 TMDB 高分电影 250 部（非 IMDb 官方榜单）"
                shelf.films.isNotEmpty() -> "已加载 ${shelf.films.size} 部；其余请求失败，可重试补齐"
                else -> "TMDB Top250 未返回影片；检查 API 网络连通性后重试"
            }
        }
        tmdbState.tmdbDiscoveryStatus = tmdbState.tmdbTop250Status
    }
    LaunchedEffect(screen, filmId, tmdbState.discoverySelectionEpoch, personalSourceConfigEpoch, tvBoxSingle, tmdbState.sourceForeground) {
        val selection = tmdbState.discoverySelection?.takeIf { screen in setOf("detail", "play-intent") && tmdbState.sourceForeground && it.first == filmId }
            ?: return@LaunchedEffect
        tmdbState.discoverySelection = null
        val targetFilmId = selection.first
        val snapshot = tmdbState.discoveredSources[targetFilmId] ?: return@LaunchedEffect
        val hit = snapshot.hits.firstOrNull { it.key == selection.second } ?: return@LaunchedEffect
        if (hit.relevance == SourceRelevance.OTHER) return@LaunchedEffect
        val detailTicket = tmdbState.discoverySelectionEpoch
        tmdbState.requestedDiscoveryHits[targetFilmId] = hit.key
        tmdbState.discoveryLoadingEpoch = detailTicket
        tmdbState.discoveryLoadingFilm = targetFilmId
        val consent = spiderBridge.configurationTicket()
        val owner = SourceDetailOwner(targetFilmId, detailTicket, personalSourceConfigEpoch, consent, snapshot.revision)
        fun publicationAllowed() = owner.accepts(filmId, screen, tmdbState.sourceForeground,
            tmdbState.discoverySelectionEpoch, personalSourceConfigEpoch,
            spiderBridge.configurationTicket(), tmdbState.discoveredSources[targetFilmId]?.revision)
        try {
            tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = hit.node.type == HerrinSourceType.TVBOX_SPIDER
            val metadata = tmdbState.tmdbResolved[targetFilmId] ?: return@LaunchedEffect
            fun continueDirectDiscovery(authFailure: Boolean = false) {
                if (screen != "play-intent" || filmId != targetFilmId) return
                val next = if (authFailure) null else tmdbState.nextDiscoveryHit(targetFilmId, hit.key)
                if (next != null) {
                    tmdbState.discoveryLine = null
                    tmdbState.requestedDiscoveryHits[targetFilmId] = next.key
                    tmdbState.discoverySelection = targetFilmId to next.key
                    tmdbState.discoverySelectionEpoch++
                } else if (authFailure || tmdbState.discoveredSources[targetFilmId]?.hasRemaining != true) {
                    tmdbState.discoveryRecovery = null
                    screen = "detail"
                }
            }
            tmdbState.tmdbSourceStatus = "正在读取 ${hit.node.name} 的真实选集…"
            val detailStarted = System.nanoTime()
            var detailStage = SourceSearchStage.DETAILS
            var detailOutcome = SourceSearchOutcome.TIMEOUT
            val loaded = withContext(Dispatchers.IO + SpiderSearchAuthority(consent)) {
                try {
                    withTimeoutOrNull(15_000) {
                        if (hit.node.type == HerrinSourceType.TVBOX_SPIDER) {
                            tmdbState.discoverySpiderLock.lock()
                            try { hit.node.details(hit.movie) } finally { tmdbState.discoverySpiderLock.unlock() }
                        } else hit.node.details(hit.movie)
                    }
                } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch(failure: SourceCandidateFailure) { detailStage=failure.stage;detailOutcome=failure.outcome;null
                } catch(_: Exception) { detailOutcome=SourceSearchOutcome.ERROR;null }
            }
            if (!publicationAllowed()) return@LaunchedEffect
            DiscoveryProtocolTrace.phase(hit.node.id,detailStage,
                if(loaded?.film?.playbackEpisodes?.isNotEmpty() == true) SourceSearchOutcome.VERIFIED else if(loaded != null) SourceSearchOutcome.NO_EPISODES else detailOutcome,
                (System.nanoTime()-detailStarted)/1_000_000,loaded?.film?.playbackEpisodes?.size ?: 0)
            if(loaded == null || loaded.film.playbackEpisodes.isEmpty()) {
                tmdbState.tmdbSourceStatus = "${hit.node.name} 已返回搜索条目 · ${detailStage.label()} · ${if(loaded != null) SourceSearchOutcome.NO_EPISODES.label() else detailOutcome.label()}；可重试或选择其他影视点"
                tmdbState.invalidateDiscoverySelection(targetFilmId, hit.key)
                tmdbState.sourceDiscoveries[targetFilmId]?.invalidateLayout(snapshot.revision, hit.key)?.let { tmdbState.discoveredSources[targetFilmId] = it }
                if (!publicationAllowed()) return@LaunchedEffect
                tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = false
                tmdbState.discoveryLine = null
                continueDirectDiscovery(detailOutcome in setOf(SourceSearchOutcome.AUTH_REQUIRED, SourceSearchOutcome.RATE_LIMITED))
                HerrinPlaybackDiagnosticJournal.record("SOURCE", "DISCOVERY_DETAIL_FAILED")
                return@LaunchedEffect
            }
            val detailed = loaded.film
            val requestedOriginalLine = tmdbState.discoveryLine
            if (requestedOriginalLine != null && detailed.playbackEpisodes.none { ep -> ep.lines.any { it.id == requestedOriginalLine } }) {
                tmdbState.discoveryLine = null
                tmdbState.invalidateDiscoverySelection(targetFilmId, hit.key)
                tmdbState.sourceDiscoveries[targetFilmId]?.retainEpisodes(snapshot.revision, hit.key, detailed)?.let { tmdbState.discoveredSources[targetFilmId] = it }
                if (!publicationAllowed()) return@LaunchedEffect
                tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = false
                tmdbState.tmdbSourceStatus = "该线路已变化，请重新选择片源"
                continueDirectDiscovery()
                return@LaunchedEffect
            }
            val recovering = tmdbState.discoveryRecovery?.takeIf { it.film == targetFilmId }
            val sameEpisode = recovering?.let { request -> detailed.playbackEpisodes.asSequence().flatMap { ep ->
                ep.lines.asSequence().filter { request.coordinate.accepts(ep, it) }.map { ep.number to it.id }
            }.firstOrNull() }
            if (recovering != null && sameEpisode == null) {
                tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = false
                continueDirectDiscovery()
                return@LaunchedEffect
            }
            tmdbState.selectedDiscoveryNode[targetFilmId] = hit.node.id
            tmdbState.sourceDiscoveries[targetFilmId]?.retainEpisodes(snapshot.revision,hit.key,detailed)?.let {
                tmdbState.discoveredSources[targetFilmId] = it
            }
            if (!publicationAllowed()) return@LaunchedEffect
            tmdbState.tmdbPersonalPlayback.remove(targetFilmId)
            tmdbState.tmdbPlaybackRoutes.forget(targetFilmId)
            when(hit.node.type) {
                HerrinSourceType.TVBOX_CMS -> {
                    activeCmsSite = hit.node.site
                    spiderActive = false
                    tmdbState.tmdbPlaybackRoutes.cms(targetFilmId, requireNotNull(hit.node.site), hit.node.config)
                }
                HerrinSourceType.TVBOX_SPIDER -> {
                    activeCmsSite = null
                    spiderActive = true
                    tmdbState.tmdbPlaybackRoutes.spider(targetFilmId, requireNotNull(hit.node.site).key, detailed)
                }
                else -> {
                    activeCmsSite = null
                    spiderActive = false
                    tmdbState.tmdbPersonalPlayback[targetFilmId] = loaded.personal
                    tmdbState.tmdbPlaybackRoutes.personal(targetFilmId)
                }
            }
            tmdbState.tmdbFallbackIndex.select(targetFilmId, hit.node.id)
            tmdbState.tmdbResolved[targetFilmId] = metadata.copy(cmsVodId = detailed.cmsVodId,
                spiderVodId = detailed.spiderVodId, playbackEpisodes = detailed.playbackEpisodes,
                episodes = detailed.playbackEpisodes.size)
            val requestedLine = tmdbState.discoveryLine
            tmdbState.discoveryLine = null
            val requestedNumber = tmdbState.discoveryRequestedEpisode
            val requestedEpisode = detailed.playbackEpisodes.firstOrNull { ep ->
                (requestedNumber == null || ep.number == requestedNumber) && ep.lines.any { it.id == requestedLine }
            }
            if (requestedNumber != null && requestedEpisode == null) {
                tmdbState.discardDiscoveryPlay(targetFilmId, hit.key, detailTicket)
                HerrinPlaybackDiagnosticJournal.record("SOURCE", "DISCOVERY_EPISODE_CHANGED")
            }
            if (requestedEpisode != null && requestedLine != null) { episode = requestedEpisode.number; line = requestedLine }
            else HerrinPlaybackEntry.firstPlayable(tmdbState.tmdbResolved[targetFilmId])?.let { (number, id) -> episode = number; line = id }
            tmdbState.tmdbSourceStatus = "${hit.node.name} · ${detailed.playbackEpisodes.size} 集 · 选择集数后解析并验证播放"
            if (recovering != null && sameEpisode != null) {
                episode = sameEpisode.first; line = sameEpisode.second
                tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(targetFilmId, episode, line, recovering.position)
                tmdbState.discoveryRecovery = null
            }
            tmdbState.loadedDiscoveryHits[targetFilmId] = hit.key
            tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused =
                screen == "play-intent" && hit.node.type == HerrinSourceType.TVBOX_SPIDER
            HerrinPlaybackDiagnosticJournal.record("SOURCE", "DISCOVERY_DETAIL_READY", "EPISODES=${detailed.playbackEpisodes.size}")
            if (tmdbState.consumeDiscoveryPlay(targetFilmId, hit.key, detailTicket) && screen == "detail") {
                // A click on a fully ready node/line is the play intent. Do not
                // wait for unrelated searches or require a second play button.
                tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = hit.node.type == HerrinSourceType.TVBOX_SPIDER
                screen = "player"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: Exception) {
            tmdbState.invalidateDiscoverySelection(targetFilmId, hit.key)
            tmdbState.sourceDiscoveries[targetFilmId]?.invalidateLayout(snapshot.revision, hit.key)?.let { tmdbState.discoveredSources[targetFilmId] = it }
            tmdbState.sourceDiscoveries[targetFilmId]?.spiderPaused = false
            if (publicationAllowed()) tmdbState.tmdbSourceStatus = "该片源暂不可用，请选择其他线路或重试"
        } finally {
            tmdbState.discardDiscoveryPlay(selection.first, hit.key, detailTicket)
            if (detailTicket == tmdbState.discoveryLoadingEpoch && tmdbState.discoveryLoadingFilm == targetFilmId)
                tmdbState.discoveryLoadingFilm = null
        }
    }
    // A TMDB detail starts a REAL per-title resource lookup. This never uses
    // the scraped poster/title as an executable source or auto-plays a video.
    fun choosePreferredSite(site: TvBoxSite, defaultJar: String?) {
        tmdbState.discoverySelection = null
        tmdbState.discoverySelectionEpoch++
        prefs.preferSite(prefs.tvBoxWarehouseUrl, site, defaultJar)
        tmdbState.tmdbSearchGeneration.incrementAndGet()
        tmdbState.sourceSearchJob?.cancel()
        tmdbState.tmdbPlaybackRoutes.clear()
        tmdbState.tmdbFallbackIndex.clear()
        tmdbState.preferredSiteStatus = "首选影视点：${site.name}"
        tmdbState.tmdbLookupEpoch++
    }
    PersonalLibraryIndexChanges(personalIndexRevision, screen, tmdbState, currentScreen = { screen })
    val lookupFilm = tmdbState.tmdbResolved[filmId] ?: (tmdbState.tmdbBrowseIndex + tmdbState.tmdbTop250Films + recentIndexFilms).firstOrNull { it.id == filmId }
    // Capture the same value as the effect key. Lifecycle observers can publish
    // STARTED during apply, before the old (false-keyed) effect is launched.
    val sourceSearchForeground=tmdbState.sourceForeground
    val detailLookup = screen in setOf("detail", "play-intent", "player") && tmdbState.automaticSearch == null
    LaunchedEffect(screen, filmId) {
        if (screen == "detail" && tmdbState.discoveryLoadingFilm != filmId)
            tmdbState.sourceDiscoveries[filmId]?.spiderPaused = false
    }
    val playbackRecoveryLookup = screen == "player" && tmdbState.automaticSearch != null
    LaunchedEffect(detailLookup, playbackRecoveryLookup, filmId, lookupFilm?.title,lookupFilm?.subtitle,lookupFilm?.year,lookupFilm?.tmdbTvId,tvBoxSingle,
        tvBoxWarehouses, personalSourceConfigEpoch, tmdbState.tmdbLookupEpoch,sourceSearchForeground) {
        val searchTicket=tmdbState.tmdbSearchGeneration.incrementAndGet()
        val consentAtStart=spiderBridge.configurationTicket()
        tmdbState.tmdbSearchRunning=false
        if(!sourceSearchForeground)return@LaunchedEffect
        tmdbState.sourceSearchJob=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val recoveryRequest = tmdbState.automaticSearch?.takeIf { it.film == filmId && screen == "player" }
        if ((!detailLookup && recoveryRequest == null) || !filmId.startsWith("tmdb-")) return@LaunchedEffect
        val tmdbFilm = lookupFilm ?: return@LaunchedEffect
        val tmdbKey = tmdbDiscovery.identity(tmdbFilm) ?: return@LaunchedEffect
        val targetId = tmdbFilm.id
        val searchIdentity = HerrinFilmIdentity(
            tmdbKey.second, tmdbKey.first,
            listOf(tmdbFilm.title, tmdbFilm.subtitle.takeUnless { "集 · " in it }.orEmpty()).filter(String::isNotBlank),
            tmdbFilm.year.toIntOrNull(), season = recoveryRequest?.coordinate?.season,
            episode = recoveryRequest?.coordinate?.episode
        )
        val extended=tmdbState.tmdbExtendedRequest==targetId
        tmdbState.tmdbExtendedRequest=null
        // Same film/config resumes the immutable plan after lifecycle cancellation.
        // Explicit retry alone resets it; get() replaces it on identity/config changes.
        val budgetMs=if(extended)SourceSearchPolicy.EXTENDED_MS else SourceSearchPolicy.FOREGROUND_MS
        val lookupEpoch=tmdbState.tmdbLookupEpoch
        val configEpoch=personalSourceConfigEpoch
        tmdbState.tmdbStatusFilm=targetId
        tmdbState.tmdbSearchStarted=SourceSearchDiagnostics.begin()
        tmdbState.tmdbSearchRunning=true;tmdbState.tmdbStageStarted=tmdbState.tmdbSearchStarted
        tmdbState.tmdbSourceStatus = "正在快速检索 SMB／WebDAV及已授权影视点…"
        HerrinPlaybackDiagnosticJournal.record("LOOKUP", "TMDB_SEARCH_STARTED")
        val currentTvbox = tvBoxSingle
        val trustedSpiderRoot = prefs.tvBoxTrustedSpiderRootUrl
        val trustedSpiderJar = prefs.tvBoxTrustedSpiderJarSpec
        val approvedRoot = prefs.tvBoxWarehouseUrl
        var spiderPageOffset = tmdbState.tmdbSpiderBatchOffsets[targetId] ?: 0
        var previousCompleted=tmdbState.tmdbSpiderBatchCompleted[targetId]?:0
        val importedWarehouses = withContext(Dispatchers.IO) { (tvBoxWarehouses + AdditionalServiceConfigs(context).enabled("sources")
            .map { TvBoxWarehouse(it.name, it.url) }).distinctBy { it.url }.take(10) }
        // Spider is a privileged plugin: only an existing user-applied session
        // may be searched. Never start a new Spider while browsing TMDB.
        val activeSpiderKey = spiderBridge.approvedActiveSiteKey()
        val approvedSpiderSite = if (spiderActive && activeSpiderKey != null)
            currentTvbox?.sites?.firstOrNull { it.key == activeSpiderKey &&
                (it.spiderApi != null || it.scriptApi != null) }
        else null
        val nasUrl = prefs.nasWebDavUrl
        var health:SourceHealthIndex?=null
        var planRevision=""
        suspend fun sourceEvent(event:SourceSearchEvent){
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate){
                if(searchTicket==tmdbState.tmdbSearchGeneration.get() &&filmId==targetId &&tmdbState.tmdbLookupEpoch==lookupEpoch &&personalSourceConfigEpoch==configEpoch &&tvBoxSingle===currentTvbox){
                    SourceSearchDiagnostics.record(event)
                    tmdbState.tmdbSearchPlans.observe(targetId,planRevision,event)
                    val progress=tmdbState.tmdbSearchPlans.progress(targetId,planRevision)
                    if(event.outcome==SourceSearchOutcome.CHECKING)tmdbState.tmdbStageStarted=System.nanoTime()
                    tmdbState.tmdbSourceStatus="${event.stage.label()} · ${event.outcome.label()} · ${progress.text()} · ${event.elapsedMs}ms"
                }
            }
        }
        val searchOutput=try { withTimeoutOrNull(if(detailLookup) Long.MAX_VALUE else budgetMs) { withContext(Dispatchers.IO+SpiderSearchAuthority(consentAtStart)+PlaybackDiagnosticSession.context(playbackDiagnosticId)) {
            val adapters = mutableListOf<HerrinFilmSource>()
            adapters += PersonalLibrary(context).sources(smbSessions)
            val preferredSite = currentTvbox?.sites?.firstOrNull {
                prefs.tvBoxPreferredRootId == SourceHealthIndex.digest(approvedRoot) &&
                    TvBoxAutoSites.identity(it, currentTvbox.spiderJar) == prefs.tvBoxPreferredSiteId
            }
            val preferenceStatus = when {
                prefs.tvBoxPreferredSiteId.isBlank() -> "未设置首选影视点"
                preferredSite == null -> "首选影视点已失效：影视仓或接口身份已变化，请在设置中重新选择"
                preferredSite.apiUrl != null -> "首选影视点：${preferredSite.name}"
                preferredSite.spiderApi == null -> "首选影视点暂不支持自动搜索，请选择 CMS 或已授权 Java Spider"
                !TvBoxSpiderPolicy.mayRun(false, approvedRoot, preferredSite.jar ?: currentTvbox?.spiderJar.orEmpty(), trustedSpiderRoot, trustedSpiderJar) -> "首选 Spider 未获当前配置授权，请先在设置中授权"
                else -> "首选影视点：${preferredSite.name}"
            }
            withContext(Dispatchers.Main.immediate) {
                if (searchTicket == tmdbState.tmdbSearchGeneration.get()) tmdbState.preferredSiteStatus = preferenceStatus
            }
            if (prefs.tvBoxPreferredSiteId.isNotBlank() && preferredSite == null)
                HerrinPlaybackDiagnosticJournal.record("LOOKUP", "PREFERRED_SITE_INVALID")
            val preferredSourceId = preferredSite?.let {
                if (it.apiUrl != null) "cms:" + it.apiUrl else "spider:" + it.key
            }
            val directSites = mutableListOf<TvBoxSite>()
            val discoveryCms = currentTvbox?.sites.orEmpty().filter { it.apiUrl != null }.map { Triple(approvedRoot, it, currentTvbox) }.toMutableList()
            // A selected node remains eligible even beyond the fallback fan-out cap.
            preferredSite?.takeIf { it.apiUrl != null }?.let(directSites::add)
            directSites += currentTvbox?.sites?.filter { it.apiUrl != null }.orEmpty().take(100-directSites.size)
            // Metadata cache first; explicit continuation alone may refresh missing warehouse data.
            val scope=SourceHealthIndex.digest(listOf(approvedRoot,trustedSpiderRoot,trustedSpiderJar,currentTvbox.toString(),prefs.tvBoxPreferredRootId,prefs.tvBoxPreferredSiteId,nasUrl,configEpoch.toString(),personalIndexRevision.toString(),
                HerrinSmbConfigStore(context).publicConfig().toString(),
                AdditionalServiceConfigs(context).enabled("nas").toString(),importedWarehouses.toString(),
                context.getSharedPreferences("herrin_personal_secrets",0).getString("_revision","legacy").orEmpty()).joinToString("|"))
            health=SourceHealthIndex(java.io.File(context.filesDir,"source-health-v1.json"),scope)
            val index=requireNotNull(health)
            planRevision=scope+SourceHealthIndex.digest(searchIdentity.toString())
            val (plan,replaced)=tmdbState.tmdbSearchPlans.get(targetId,planRevision,currentTvbox?.sites.orEmpty(),index::rank)
            if(replaced){spiderPageOffset=0;previousCompleted=0}
            val completedNodes=tmdbState.tmdbSearchPlans.completed(targetId,planRevision)
            // Multiwarehouse configs are loaded as data (no JAR/JS execution).
            // Only user-imported root warehouse URLs are eligible for bounded
            // lookup; cap fan-out so a TV does not stall or scan the internet.
            for (warehouse in importedWarehouses) {
                if (directSites.size >= 100) break
                when (val nested = HerrinDiscoveryWarehouses.cached(warehouse.url) ?: tvBoxRepository.forSearch(warehouse.url, tvBoxAllowHttp, allowNetwork = screen != "detail" && extended)) {
                    is TvBoxConfig.Single -> {
                        val cmsSites = nested.sites.filter { it.apiUrl != null }.take(100-directSites.size)
                        directSites += cmsSites
                        discoveryCms += cmsSites.map { Triple(warehouse.url, it, nested) }
                    }
                    else -> Unit
                }
            }
            directSites.distinctBy { it.apiUrl }.take(100).forEach { site ->
                adapters += HerrinTvBoxCmsSource(site, tvBoxVodRepo, tvBoxAllowHttp)
            }
            // Java nodes use one serialized search batch; an explicitly active script keeps its own authorization.
            approvedSpiderSite?.takeIf{it.scriptApi!=null}?.let{adapters+=HerrinTvBoxSpiderSource(it,spiderBridge)}

            // Selection grants no execution authority. Recheck the pinned JAR/root before activation.
            preferredSite?.takeIf { it.spiderApi != null && approvedRoot == trustedSpiderRoot &&
                TvBoxSpiderPolicy.mayRun(false, approvedRoot, it.jar ?: currentTvbox?.spiderJar.orEmpty(),
                    trustedSpiderRoot, trustedSpiderJar) }?.let { site ->
                adapters.removeAll { it.id == "spider:" + site.key }
                adapters += HerrinTvBoxSpiderSource(site, spiderBridge) {
                    sourceEvent(SourceSearchEvent("spider:" + site.key, SourceSearchStage.ACTIVATE, SourceSearchOutcome.CHECKING))
                    withContext(SpiderRequestBudget(SourceSearchPolicy.ACTIVATION_MS)) {
                        spiderBridge.browse(site, requireNotNull(currentTvbox), searchOnly = true) is SpiderResult.Ready
                    }
                }
            }
            val authorizedBatchSites = plan.sites.filter { site ->
                val runtime=site.jar ?: currentTvbox?.spiderJar.orEmpty()
                site !== preferredSite && site.spiderApi!=null && runtime.isNotBlank() &&
                    TvBoxSpiderPolicy.mayRun(false,approvedRoot,runtime,trustedSpiderRoot,trustedSpiderJar)
            }
            tmdbState.tmdbSearchPlans.register(targetId,planRevision,
                adapters.map{it.id}+authorizedBatchSites.map{"spider:"+it.key})
            if (detailLookup) {
                val authorizedSpiders = currentTvbox?.sites.orEmpty().filter { site ->
                    site.spiderApi != null && TvBoxSpiderPolicy.mayRun(false, approvedRoot,
                        site.jar ?: currentTvbox?.spiderJar.orEmpty(), trustedSpiderRoot, trustedSpiderJar)
                }
                val nodes = HerrinDiscoveryNodes.create(adapters.filterIsInstance<HerrinPersonalSource>(),
                    discoveryCms, authorizedSpiders, approvedRoot, currentTvbox, searchIdentity,
                    tvBoxVodRepo, tvBoxAllowHttp, spiderBridge, consentAtStart)
                val preferredNode = preferredSite?.let { HerrinDiscoveryNodes.id(approvedRoot, it,
                    if(it.spiderApi != null) currentTvbox?.spiderJar else null) }
                val discovery = tmdbState.discoveryFor(targetId)
                discovery.search(planRevision, nodes.sortedByDescending { index.rank(it.id) }, preferredNode,
                    tmdbState.discoverySpiderLock, diagnose = { event ->
                        SourceSearchDiagnostics.record(event)
                        index.record(event.source, event.stage, event.outcome, event.elapsedMs)
                    },
                    expand = if(importedWarehouses.isEmpty()) null else { emit ->
                        HerrinDiscoveryWarehouses.expand(importedWarehouses,tvBoxRepository,tvBoxAllowHttp,
                            create = { origin, owner ->
                                HerrinDiscoveryNodes.create(emptyList(),owner.sites.filter { it.apiUrl != null }.map { Triple(origin,it,owner) },
                                    emptyList(),approvedRoot,currentTvbox,searchIdentity,tvBoxVodRepo,tvBoxAllowHttp,spiderBridge,consentAtStart)
                            }, emit = emit)
                    }) { snapshot ->
                    withContext(Dispatchers.Main.immediate) {
                        if(searchTicket == tmdbState.tmdbSearchGeneration.get() && filmId == targetId && tmdbState.tmdbLookupEpoch == lookupEpoch && personalSourceConfigEpoch == configEpoch && tvBoxSingle === currentTvbox && snapshot.revision == planRevision) {
                            val previous = tmdbState.discoveredSources[targetId]
                            if (previous?.selectableNodeIds.isNullOrEmpty() && snapshot.selectableNodeIds.isNotEmpty()) {
                                HerrinPlaybackDiagnosticJournal.record("LOOKUP", "DISCOVERY_FIRST_NODE",
                                    "MS=${(System.nanoTime() - tmdbState.tmdbSearchStarted) / 1_000_000}")
                            }
                            if (previous != snapshot) tmdbState.discoveredSources[targetId] = snapshot
                            if (snapshot.hits.any { it.relevance == SourceRelevance.CONFIRMED }) DetailPlaybackMetrics.timeline.hit()
                            if (snapshot.hits.any { it.episodes.isNotEmpty() }) DetailPlaybackMetrics.timeline.details()
                            if (screen in setOf("detail", "play-intent") && tmdbState.autoLayoutSelected[targetId] != planRevision &&
                                tmdbState.discoverySelection == null && tmdbState.discoveryLoadingFilm != targetId &&
                                tmdbState.selectedDiscoveryNode[targetId]?.let(snapshot.selectableNodeIds::contains) != true) {
                                snapshot.readyRoutes.firstOrNull { it.nodeId in snapshot.selectableNodeIds }
                                    ?.let { route -> snapshot.hits.firstOrNull { it.key == route.hitKey } }?.let { ready ->
                                    tmdbState.autoLayoutSelected[targetId] = planRevision
                                    tmdbState.selectedDiscoveryNode[targetId] = ready.node.id
                                    // A selectable node has its real episode/line layout already.
                                    // Publish only a media-free view projection immediately.
                                    val metadataForPreview = tmdbState.tmdbResolved[targetId] ?: tmdbFilm
                                    val previewEpisodes = ready.episodes.map { ep ->
                                        PlaybackEpisode(ep.number, ep.title, ep.lines.map { (id, title) ->
                                            PlaybackLine(id, title, "")
                                        })
                                    }
                                    tmdbState.tmdbResolved[targetId] = metadataForPreview.copy(
                                        cmsVodId = ready.movie.film.cmsVodId,
                                        spiderVodId = ready.movie.film.spiderVodId,
                                        playbackEpisodes = previewEpisodes, episodes = previewEpisodes.size)
                                    previewEpisodes.firstOrNull()?.let { first ->
                                        first.lines.firstOrNull()?.let { firstLine ->
                                            episode = first.number; line = firstLine.id
                                        }
                                    }
                                    tmdbState.requestedDiscoveryHits[targetId] = ready.key
                                    tmdbState.discoverySelection = targetId to ready.key
                                    tmdbState.discoverySelectionEpoch++
                                }
                            }
                            if (screen == "play-intent" && tmdbState.rejectedDiscoveryHits.isNotEmpty() &&
                                tmdbState.discoverySelection == null && tmdbState.discoveryLoadingFilm != targetId &&
                                !tmdbState.sourceSelectionReady(targetId)) {
                                tmdbState.nextDiscoveryHit(targetId)?.let { next ->
                                    tmdbState.discoveryLine = null
                                    tmdbState.requestedDiscoveryHits[targetId] = next.key
                                    tmdbState.discoverySelection = targetId to next.key
                                    tmdbState.discoverySelectionEpoch++
                                }
                            }
                            if(tmdbState.resumeSourceFilm == targetId) {
                                snapshot.hits.firstOrNull { it.relevance == SourceRelevance.CONFIRMED && HerrinFilmIdentityMatch.matches(searchIdentity,it.movie) }?.let { hit ->
                                    // A recent-card click is an explicit resume request. Reacquire
                                    // current provider episodes; never replay persisted media URLs.
                                    tmdbState.resumeSourceFilm = null
                                    tmdbState.requestedDiscoveryHits[targetId] = hit.key
                                    tmdbState.discoverySelection = targetId to hit.key
                                    tmdbState.discoverySelectionEpoch++
                                }
                            }
                        }
                    }
                }
                return@withContext null
            }
            val personals = adapters.filterIsInstance<HerrinPersonalSource>().associateBy { it.id }
            val remotes = adapters.associateBy { it.id }
            val reachability = HerrinPersonalMediaProbe(strictHttpFailure=true)
            val personalUris=hashSetOf<String>()
            val personalBytes=HerrinRemoteMediaProbe(authorizeLocal={it in personalUris},strictHttpFailure=true)
            // Only the existing user-approved Spider session may supply a local
            // FongMi media URL. The native proxy authenticates the session and
            // site on a real HTTP health request; unapproved localhost stays blocked.
            val approvedPlaybackSiteKey = approvedSpiderSite?.key
            val remoteReachability = HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                FongMiSessionAuthorization.isApproved(candidate, spiderBridge.approvedActiveSiteKey())
            },strictHttpFailure=true)
            val verifiedRemoteLines = mutableMapOf<String, Pair<Int, String>>()
            val resolved = HerrinVerifiedSourceResolver(
                adapters.filterNot { (it.id != preferredSourceId && it.id in tmdbState.tmdbFallbackIndex.rejected(targetId)) || it.id in completedNodes }
            ).find(
                searchIdentity,
                personalPlayback = { offer ->
                    val result=personals[offer.sourceId]?.playback(offer)
                        ?: PlaybackResult.Unavailable("该个人媒体来源未授权")
                    (result as? PlaybackResult.Ready)?.media?.let{personalUris.add(it.uri)}
                    result
                },
                streaming=true,preferredSourceId=preferredSourceId,health=index,onEvent=::sourceEvent,
                coordinate = recoveryRequest?.coordinate,
                verifyPersonal = { media -> reachability.canRead(media) &&personalBytes.canRead(media) },
                verifyRemote = { offer, detailed ->
                    // A working episode-list or playerContent URL alone is NOT
                    // proof of playable video. Try the selected title's lines,
                    // bounded to six, and inspect actual media response bytes.
                    val verifiedLine = HerrinPlayableSourceSelector.find(
                        detailed, coordinate = recoveryRequest?.coordinate,diagnose=true,
                        onStage={phase->sourceEvent(SourceSearchEvent(offer.sourceId,phase,SourceSearchOutcome.CHECKING))},
                        resolve = { number, lineId ->
                            when (val adapter = remotes[offer.sourceId]) {
                                is HerrinTvBoxSpiderSource ->
                                    withTimeoutOrNull(9_000L) {
                                        spiderBridge.play(detailed, number, lineId,
                                            tvBoxAllowHttp)
                                    } ?: PlaybackResult.Unavailable("Spider 播放解析超时")
                                is HerrinTvBoxCmsSource ->
                                    resolveCmsCandidate(context,adapter,detailed,number,lineId,currentTvbox,tvBoxAllowHttp)
                                else -> PlaybackResult.Unavailable("未授权的影视点")
                            }
                        },
                        verifyMedia = { media -> remoteReachability.canRead(media) })
                    val passed = verifiedLine != null
                    if (passed) {
                        verifiedRemoteLines[offer.sourceId + ":" + offer.film.id] =
                            verifiedLine!!.episode to verifiedLine.line
                    }
                    HerrinPlaybackDiagnosticJournal.record("SOURCE",
                        if (verifiedLine?.mediaBytesVerified == false) "KERNEL_SELECTION_ADMITTED_MEDIA_PENDING"
                        else if (passed) "MEDIA_BYTES_VERIFIED" else "MEDIA_BYTES_REJECTED")
                    passed
                },
                selectRemote = { offer, _ ->
                    verifiedRemoteLines[offer.sourceId + ":" + offer.film.id]
                },
                remoteDetails = { offer ->
                    withTimeoutOrNull(4_000L) {
                        when (val adapter = remotes[offer.sourceId]) {
                            is HerrinTvBoxCmsSource -> when(val result=adapter.details(offer)){
                                is TvBoxVodDetailResult.Ready->result.film
                                is TvBoxVodDetailResult.Error->{result.httpCode?.let{throw SourceHttpFailure(it,result.retryAfterMs)};null}
                            }
                            is HerrinTvBoxSpiderSource ->
                                (adapter.details(offer) as? SpiderResult.Ready)?.value
                            else -> null
                        }
                    }
                }
            )
            // Direct-CMS results are checked first. If none actually exposes
            // a usable episode, scan other Spider nodes only under the exact
            // runtime JAR/root already explicitly approved by the user.
            val batch = if (resolved.lookup.options.isEmpty() &&
                currentTvbox != null && trustedSpiderJar.isNotBlank() &&
                approvedRoot == trustedSpiderRoot) {
                HerrinApprovedSpiderBatch.scanAcrossPages(
                    searchIdentity, authorizedBatchSites,
                    allowed = { site ->
                        val runtime = site.jar ?: currentTvbox.spiderJar ?: site.scriptApi.orEmpty()
                        // An approved Java JAR must not silently approve
                        // unrelated JS/Python/node runtime definitions.
                        site.spiderApi != null && runtime.isNotBlank() &&
                            TvBoxSpiderPolicy.mayRun(false, approvedRoot, runtime,
                                trustedSpiderRoot, trustedSpiderJar) &&
                            "spider:" + site.key !in tmdbState.tmdbFallbackIndex.rejected(targetId)
                    },
                    offset = spiderPageOffset,
                    maxWallClockMs = (budgetMs-(System.nanoTime()-tmdbState.tmdbSearchStarted)/1_000_000-150L).coerceAtLeast(1L),
                    stageBudgetMs=1_200L,
                    onEvent={event->
                        sourceEvent(event)
                        if(event.outcome!=SourceSearchOutcome.CHECKING)index.record(event.source,event.stage,event.outcome,event.elapsedMs,event.retryAfterMs)
                    },
                    // Playback first: stop on the first media-byte-verified
                    // source rather than waiting for a hypothetical 4K claim.
                    stopAtFirstPlayable = true,
                    onProgress = { progress ->
                        withContext(Dispatchers.Main) {
                            if(searchTicket!=tmdbState.tmdbSearchGeneration.get())return@withContext
                            tmdbState.tmdbSpiderBatchOffsets[targetId] = progress.nextOffset
                            tmdbState.tmdbSpiderBatchCompleted[targetId]=previousCompleted+progress.checked
                            val visited = (previousCompleted + progress.checked)
                                .coerceAtMost(progress.eligible)
                            tmdbState.tmdbSourceStatus = if (progress.match != null)
                                "已验证可播放影视点，正在载入选集…"
                            else "正在自动检索授权 Spider 影视点：已检查 " +
                                tmdbState.tmdbSearchPlans.progress(targetId,planRevision).text()
                        }
                    },
                    onStageProgress = { progress ->
                        // Do not hide all of nodes 7..12 behind a six-site page.
                        // Also persist the mid-page cursor on a deadline.
                        withContext(Dispatchers.Main) {
                            if(searchTicket!=tmdbState.tmdbSearchGeneration.get())return@withContext
                            tmdbState.tmdbSpiderBatchOffsets[targetId] = progress.nextOffset
                            tmdbState.tmdbSpiderBatchCompleted[targetId]=previousCompleted+progress.checked
                            val visited = (previousCompleted + progress.checked)
                                .coerceAtMost(progress.eligible)
                            progress.lastOutcome?.let { outcomeCode ->
                                // Fixed host codes only. No third-party URLs or
                                // search terms are written to device diagnostics.
                                HerrinPlaybackDiagnosticJournal.record("LOOKUP",
                                    "SPIDER_NODE_" + outcomeCode.name,
                                    "COUNT=$visited")
                            }
                            val outcome = progress.httpCode?.let{SourceHealthIndex.httpOutcome(it).label()}?:when (progress.lastOutcome) {
                                HerrinApprovedSpiderBatch.Outcome.VERIFIED -> "已验证媒体"
                                HerrinApprovedSpiderBatch.Outcome.TIMEOUT -> "节点超时"
                                HerrinApprovedSpiderBatch.Outcome.ACTIVATE_FAILED -> "插件激活失败"
                                HerrinApprovedSpiderBatch.Outcome.NO_MATCH -> "没有匹配影片"
                                HerrinApprovedSpiderBatch.Outcome.NO_EPISODES -> "没有有效选集"
                                HerrinApprovedSpiderBatch.Outcome.PARSE_FAILED -> "播放解析失败"
                                HerrinApprovedSpiderBatch.Outcome.INVALID_MEDIA -> "没有可验证播放线路"
                                HerrinApprovedSpiderBatch.Outcome.ERROR -> "插件执行异常"
                                HerrinApprovedSpiderBatch.Outcome.DEFERRED -> "节点暂缓（授权、限流或短期冷却）"
                                null -> "正在检查"
                            }
                            tmdbState.tmdbSourceStatus = if (progress.match != null)
                                "已找到并验证播放线路，正在载入选集…"
                            else "${progress.stage?.label().orEmpty()} · ${tmdbState.tmdbSearchPlans.progress(targetId,planRevision).text()}；"+
                                (progress.activeIndex?.let{"第 $it 个节点检查中；"}?:"")+"最近结果：$outcome · ${progress.elapsedMs}ms"
                        }
                    },
                    session = object : HerrinApprovedSpiderBatch.Session {
                        override suspend fun eligible(site:TvBoxSite)=
                            "spider:"+site.key !in tmdbState.tmdbSearchPlans.completed(targetId,planRevision) && index.eligible("spider:"+site.key)
                        override suspend fun activate(site: TvBoxSite): Boolean =
                            spiderBridge.browse(site, currentTvbox,searchOnly=true) is SpiderResult.Ready
                        override suspend fun search(site: TvBoxSite, identity: HerrinFilmIdentity) =
                            HerrinTvBoxSpiderSource(site, spiderBridge).search(identity)
                        override suspend fun details(candidate: HerrinSourceMovie): Film? =
                            (spiderBridge.details(candidate.film) as? SpiderResult.Ready)?.value
                        override suspend fun canPlay(detailed: Film): Boolean =
                            pick(detailed) != null
                        override suspend fun pick(detailed: Film): Pair<Int, String>? =
                            HerrinPlayableSourceSelector.find(
                                detailed, coordinate = recoveryRequest?.coordinate,diagnose=true,
                                onStage={phase->sourceEvent(SourceSearchEvent("spider:"+spiderBridge.approvedActiveSiteKey(),phase,SourceSearchOutcome.CHECKING))},
                                resolve = { number, lineId ->
                                    spiderBridge.play(detailed, number, lineId, tvBoxAllowHttp)
                                },
                                verifyMedia = { media ->
                                    remoteReachability.canRead(media)
                                })?.let { it.episode to it.line }
                    }
                )
            } else null
            // Only interrupt a verified CMS selection for a demonstrably
            // higher-quality Spider claim. Personal NAS/cloud remains higher
            // priority. Labels rank verified-bytes choices; not HDR proof.
            val selectedCmsLabel = resolved.verifiedLine?.let { (ep, id) ->
                resolved.sourceDetails?.playbackEpisodes
                    ?.firstOrNull { it.number == ep }?.lines
                    ?.firstOrNull { it.id == id }?.title
            }
            val cmsClaim = HerrinFilmIdentityMatch.resolutionClaim(
                selectedCmsLabel.orEmpty())
                ?: resolved.lookup.options.firstOrNull()?.resolutionClaim ?: 0
            val preferSpider = batch?.match != null &&
                (resolved.lookup.options.isEmpty() ||
                    (resolved.lookup.selectedTier == HerrinSourceTier.TVBOX &&
                        batch.match.claim > cmsClaim))
            Triple(resolved, adapters, if (preferSpider) batch
                else batch?.copy(match = null))
        } } } finally {
            withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO){health?.save{searchTicket==tmdbState.tmdbSearchGeneration.get()}}
            if(searchTicket==tmdbState.tmdbSearchGeneration.get())tmdbState.tmdbSearchRunning=false
        }
        if(detailLookup) return@LaunchedEffect
        if(searchOutput==null){
            tmdbState.tmdbSourceStatus="前台搜索预算结束（${budgetMs/1000}秒） · ${tmdbState.tmdbSearchPlans.progress(targetId,planRevision).text()}；可继续搜索剩余节点"
            HerrinPlaybackDiagnosticJournal.record("LOOKUP","FOREGROUND_BUDGET_END")
            return@LaunchedEffect
        }
        val (verified,sources,batchScan)=searchOutput
        val lookup = verified.lookup
        if (batchScan != null) {
            tmdbState.tmdbSpiderBatchOffsets[targetId] = batchScan.nextOffset
            HerrinPlaybackDiagnosticJournal.record("LOOKUP", "SPIDER_BATCH_FINISHED",
                "CHECKED=${batchScan.checked}")
        }
        val batchMatch = batchScan?.match
        if (batchMatch != null) {
            val detail = batchMatch.detailed
            tmdbState.tmdbFallbackIndex.select(targetId, "spider:" + batchMatch.site.key)
            tmdbState.tmdbPersonalPlayback.remove(targetId)
            tmdbState.tmdbPlaybackRoutes.spider(targetId, batchMatch.site.key, detail)
            activeCmsSite = null
            spiderActive = true
            tmdbState.tmdbResolved[targetId] = (tmdbState.tmdbResolved[targetId] ?: tmdbFilm).copy(
                spiderVodId = detail.spiderVodId,
                playbackEpisodes = detail.playbackEpisodes,
                episodes = maxOf(1, detail.playbackEpisodes.size))
            episode = batchMatch.episode
            line = batchMatch.line
            recoveryRequest?.let {
                tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(targetId, episode, line, it.position)
                tmdbState.automaticSearch = null
            }
            tmdbState.tmdbSourceStatus = "已从 ${batchScan.checked} 个授权影视点中匹配并验证可播放线路：${batchMatch.site.name}；标称清晰度 ${if (batchMatch.claim >= 2160) "4K" else if (batchMatch.claim > 0) "${batchMatch.claim}p" else "未知"}，非实际解码画质"
            tmdbState.tmdbSourceStatus += " · ${tmdbState.tmdbSearchPlans.progress(targetId,planRevision).text()}"
            HerrinPlaybackDiagnosticJournal.record("SOURCE", "SPIDER_VERIFIED")
            return@LaunchedEffect
        }
        val found = lookup.options.firstOrNull()
        if (found == null) {
            tmdbState.automaticSearch = null
            HerrinPlaybackDiagnosticJournal.record("SOURCE", "NO_VERIFIED_MEDIA")
            tmdbState.tmdbSourceStatus = if (batchScan != null && batchScan.eligible == 0)
                "当前没有可自动检索的已授权 Spider 节点；请确认影视仓已加载、所需 JAR 已明确批准，不能仅凭 TMDB 海报推断有片源"
            else if (batchScan != null && batchScan.nextOffset == 0)
                "已遍历 ${batchScan.eligible} 个授权节点索引，尚无真实可播放线路；部分节点可能暂缓、超时或不兼容，请检查授权和时效"
            else if (batchScan != null)
                "已检查 ${(previousCompleted + batchScan.checked).coerceAtMost(batchScan.eligible)}/${batchScan.eligible} 个授权 Spider 影视点，本次达到检索时间上限；点击重新检索可继续剩余节点"
            else if (lookup.errors.isNotEmpty())
                "部分资源源检索失败或超时，尚未找到可播放的授权选集；可重新检索"
            else "SMB／WebDAV／TVBox 均未找到可播放的授权选集；可重新检索"
            tmdbState.tmdbSourceStatus += " · ${tmdbState.tmdbSearchPlans.progress(targetId,planRevision).text()}"
            return@LaunchedEffect
        }
        tmdbState.tmdbFallbackIndex.select(targetId, found.sourceId)
        HerrinPlaybackDiagnosticJournal.record("SOURCE", "VERIFIED_SOURCE_SELECTED")
        when (val adapter = sources.firstOrNull { it.id == found.sourceId }) {
            is HerrinPersonalSource -> {
                val playlist = verified.personalPlaylist
                if (playlist == null || playlist.episodes.isEmpty()) {
                    tmdbState.tmdbSourceStatus = "个人媒体没有可用的授权播放线路，已尝试其他来源"
                    return@LaunchedEffect
                }
                tmdbState.tmdbPersonalPlayback[targetId] = playlist.mediaByLine
                tmdbState.tmdbPlaybackRoutes.personal(targetId)
                activeCmsSite = null
                spiderActive = false
                tmdbState.tmdbResolved[targetId] = (tmdbState.tmdbResolved[targetId] ?: tmdbFilm).copy(
                    playbackEpisodes = playlist.episodes,
                    episodes = playlist.episodes.size
                )
                tmdbState.tmdbSourceStatus = "已找到 ${playlist.episodes.size} 组个人媒体选集、${playlist.mediaByLine.size} 条可选清晰度线路"
            }
            is HerrinTvBoxSpiderSource -> {
                val detailed = verified.sourceDetails
                if (detailed == null || detailed.playbackEpisodes.isEmpty()) {
                    tmdbState.tmdbSourceStatus = "Spider 搜索完成，但没有可用选集"
                    return@LaunchedEffect
                }
                tmdbState.tmdbPersonalPlayback.remove(targetId)
                tmdbState.tmdbPlaybackRoutes.spider(targetId, adapter.site.key, detailed)
                activeCmsSite = null
                spiderActive = true
                tmdbState.tmdbResolved[targetId] = (tmdbState.tmdbResolved[targetId] ?: tmdbFilm).copy(
                    spiderVodId = detailed.spiderVodId,
                    playbackEpisodes = detailed.playbackEpisodes,
                    episodes = maxOf(1, detailed.playbackEpisodes.size)
                )
                tmdbState.tmdbSourceStatus = "已匹配已授权的 Spider 影视点：${adapter.site.name}"
            }
            is HerrinTvBoxCmsSource -> {
                val detailed = verified.sourceDetails
                if (detailed == null || detailed.playbackEpisodes.isEmpty()) {
                    tmdbState.tmdbSourceStatus = "TVBox 搜索完成，但没有可用选集"
                    return@LaunchedEffect
                }
                tmdbState.tmdbPersonalPlayback.remove(targetId)
                tmdbState.tmdbPlaybackRoutes.cms(targetId, adapter.site)
                activeCmsSite = adapter.site
                spiderActive = false
                tmdbState.tmdbResolved[targetId] = (tmdbState.tmdbResolved[targetId] ?: tmdbFilm).copy(
                    cmsVodId = detailed.cmsVodId,
                    playbackEpisodes = detailed.playbackEpisodes,
                    episodes = maxOf(1, detailed.playbackEpisodes.size)
                )
                tmdbState.tmdbSourceStatus = "已匹配 TVBox ${adapter.site.name} · ${detailed.playbackEpisodes.size} 集"
            }
            else -> tmdbState.tmdbSourceStatus = "影视源没有返回可用于当前播放器的授权线路"
        }
        // The poster was opened before the network supplied its real episodes.
        // Select a real first line rather than leaving "演示线路 A" in state.
        (verified.verifiedLine ?: HerrinPlaybackEntry.firstPlayable(tmdbState.tmdbResolved[targetId]))
            ?.let { (number, id) ->
                episode = number
                line = id
                recoveryRequest?.let {
                    tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(targetId, number, id, it.position)
                    tmdbState.automaticSearch = null
                }
            }

    }
    LaunchedEffect(screen, filmId, metadataToken, imdbRatingsEpoch) {
        if (screen != "detail" || !filmId.startsWith("tmdb-") ||
            metadataToken.isBlank())
            return@LaunchedEffect
        val filmToEnrich = tmdbState.tmdbResolved[filmId] ?: (tmdbState.tmdbBrowseIndex + tmdbState.tmdbTop250Films).firstOrNull { it.id == filmId }
            ?: return@LaunchedEffect
        CrashJournal.stage("DETAIL")
        val detailed = try { tmdbDiscovery.detail(filmToEnrich, metadataToken) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: java.io.IOException) { null }
            ?: return@LaunchedEffect
        val imdbScore = withContext(Dispatchers.IO) {
            runCatching { personalImdbRatings.lookup(detailed.imdbId) }.getOrNull()
        }
        val current = tmdbState.tmdbResolved[detailed.id] ?: filmToEnrich
        tmdbState.tmdbResolved[detailed.id] = current.copy(
            synopsis = detailed.synopsis,
            actors = detailed.actors,
            directors = detailed.directors,
            people = detailed.people,
            regions = detailed.regions,
            releaseDate = detailed.releaseDate,
            runtimeMinutes = detailed.runtimeMinutes,
            imdbId = detailed.imdbId,
            imdbRating = imdbScore?.value,
            imdbVotes = imdbScore?.votes
        )
    }
    // Do not rebuild a potentially 240-item identity string on every D-pad recomposition.
    val catalogIdentity = remember(providerFilms) {
        providerFilms?.joinToString("|") { "${it.id}:${it.originalDisplay?.title ?: it.title}" }
    }
    var metadataForceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var metadataCorrection by remember { mutableStateOf<Film?>(null) }
    var metadataScanDone by remember { zeroIntState() }
    var metadataScanTotal by remember { zeroIntState() }
    var metadataScanNoMatch by remember { zeroIntState() }
    var metadataScanFailures by remember { zeroIntState() }
    LaunchedEffect(catalogScope) {
        facetActor = null; facetDirector = null; facetRegion = null
        facetYear = null; facetCategory = null; facetRating = null
    }
    LaunchedEffect(catalogScope, catalogIdentity, metadataToken, metadataRescanEpoch, tmdbConnectivity, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        CrashJournal.stage("SCRAPE")
        val eligible = providerFilms.orEmpty().take(ArtworkScrapePolicy.STARTUP_LIMIT)
        metadataScanTotal = eligible.size
        metadataScanDone = eligible.count { it.metadataSource == "TMDB" }
        metadataScanNoMatch = 0
        metadataScanFailures = 0
        // A configuration-only success never authorizes bulk search. Wait for
        // a real TMDB search endpoint response; otherwise 0/20 looks like
        // twenty bad movie names when the API is rate-limited or blocked.
        if (metadataToken.isBlank() || tmdbConnectivity != TmdbProbeState.CONNECTED ||
            tmdbState.tmdbBrowseIndex.isNotEmpty()) return@LaunchedEffect
        val unprocessed = eligible.filter { it.metadataSource != "TMDB" }
        // Network work stays at most three concurrent, but catalog publication
        // is coalesced to one update per 12 items instead of one per item.
        // This prevents dozens of whole-home regroupings during TV remote use.
        for (batch in unprocessed.chunked(12)) {
            val updates = mutableListOf<Triple<String, EnrichedArtwork?, TmdbLookupFailure?>>()
            for (group in batch.chunked(3)) {
                updates += coroutineScope {
                    group.map { original ->
                        async(Dispatchers.IO) {
                            var failure: TmdbLookupFailure? = null
                            val artwork = runCatching {
                                metadataResolver.lookup(original, metadataToken,
                                    forceRefresh = original.id in metadataForceIds,
                                    onFailure = { failure = it })
                            }.getOrNull()
                            Triple(original.id, artwork,
                                failure ?: if (artwork == null) TmdbLookupFailure.SERVER_ERROR else null)
                        }
                    }.awaitAll()
                }
            }
            val found = updates.mapNotNull { (id, artwork) ->
                artwork?.let { id to it }
            }.toMap()
            withContext(Dispatchers.Main.immediate) {
                CrashJournal.stage("PUBLISH")
                if (found.isNotEmpty()) {
                    providerFilms = providerFilms?.map { current ->
                        val artwork = found[current.id]
                        if (artwork != null && current.metadataSource != "TMDB")
                            FilmMetadataEnrichment.apply(current, artwork)
                        else current
                    }
                }
                metadataForceIds = metadataForceIds - batch.map { it.id }.toSet()
                metadataScanNoMatch += updates.count { it.third == TmdbLookupFailure.NO_MATCH }
                metadataScanFailures += updates.count {
                    it.third != null && it.third != TmdbLookupFailure.NO_MATCH
                }
                metadataScanDone += batch.size
            }
        }
    }
    // The initial enrichment queue is deliberately capped at 240 to avoid
    // flooding a TV with hundreds of network requests. Posters beyond that
    // cap are matched *on demand* after cursor dwell, instead of reintroducing
    // a second TMDB client inside every visible PosterArtwork composable.
    LaunchedEffect(catalogScope, filmId, metadataToken, metadataRescanEpoch, tmdbConnectivity) {
        if (metadataToken.isBlank() || tmdbConnectivity != TmdbProbeState.CONNECTED ||
            tmdbState.tmdbBrowseIndex.isNotEmpty()) return@LaunchedEffect
        delay(ArtworkScrapePolicy.FOCUS_DWELL_MS)
        val candidates = providerFilms.orEmpty()
        val index = candidates.indexOfFirst { it.id == filmId }
        if (index < 0) return@LaunchedEffect
        val selected = candidates[index]
        if (!ArtworkScrapePolicy.shouldMatchFocused(index, selected.metadataSource == "TMDB"))
            return@LaunchedEffect
        val info = withContext(Dispatchers.IO) {
            runCatching {
                metadataResolver.lookup(selected, metadataToken,
                    forceRefresh = selected.id in metadataForceIds)
            }.getOrNull()
        }
        if (info != null) {
            providerFilms = providerFilms?.map { current ->
                if (current.id == selected.id && current.metadataSource != "TMDB")
                    FilmMetadataEnrichment.apply(current, info)
                else current
            }
        }
        metadataForceIds = metadataForceIds - selected.id
    }
    var favorites by remember(catalogScope) { mutableStateOf(prefs.idsFor(catalogScope)) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    val systemReduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reduceMotion = reduced || systemReduced
    // A genuinely new Activity launch should replay the HERRIN slide;
    // restoring old saved-instance-state must not skip the startup curtain.
    val splash = startup.showing
    // Keep the finished H mark above the first home frames while it dissolves;
    // removing it before the hero is drawn causes a visible black flash.
    var splashExiting by remember { mutableStateOf(false) }
    SideEffect { if (splashExiting) startup.exiting = true }
    val homeVisible = true // Compose offline/cached home behind the curtain from the first frame.
    val sceneReveal = remember { Animatable(0f) }
    var hasEnteredHome by remember { mutableStateOf(false) }
    LaunchedEffect(homeVisible, screen, category, reduceMotion) {
        if (!homeVisible) {
            sceneReveal.snapTo(0f)
        } else {
            // A television's tab / Back action must respond immediately.
            // Keep only the *first* entrance animation; no 260ms fade on
            // every category and screen change.
            val firstEntrance = !hasEnteredHome
            sceneReveal.snapTo(if (firstEntrance && !reduceMotion) 0f else 1f)
            if (firstEntrance && !reduceMotion) {
                sceneReveal.animateTo(1f, tween(durationMillis = 220,
                    easing = FastOutSlowInEasing))
            }
            hasEnteredHome = true
        }
    }
    val browsePositions = rememberBrowseScrollStates()
    val railStates = browsePositions.rails
    val browseScrollStates = browsePositions.pages
    var moduleEntry by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen, category, moduleEntry) {
        val target = moduleEntry ?: return@LaunchedEffect
        if (screen != "browse" || target != category) {
            moduleEntry = null
            return@LaunchedEffect
        }
        browseScrollStates[target]?.scrollTo(0)
        railStates[target]?.scrollToItem(0)
        if (target == "首页") railStates["recommend"]?.scrollToItem(0)
        val page = TmdbBrowsePage.from(target)
        if (page != null) {
            val rows = TmdbRailSpec.forPage(page).map { it.key } +
                if (page == TmdbBrowsePage.HOME) listOf("recent") else emptyList()
            rows.forEach { tmdbBrowseState.scrolls[it]?.scrollToItem(0) }
        }
        if (moduleEntry == target) moduleEntry = null
    }
    val facetResultState = rememberLazyListState()
    // M4.25: only the active, already-loaded catalog is grouped. This is a
    // display projection; Film.id and the licensed playback paths stay intact.
    var projection by remember(catalogScope) {
        mutableStateOf(ShowCatalog.build(emptyList(), catalogScope) to emptyList<Film>())
    }
    LaunchedEffect(catalogScope, films) {
        // Cancellation prevents an obsolete catalog from publishing after a fast source change.
        projection = withContext(Dispatchers.Default) {
            val grouped = ShowCatalog.build(films, catalogScope)
            grouped to grouped.collapse(films)
        }
    }
    val showCatalog = projection.first
    val browseFilms = projection.second
    LaunchedEffect(catalogFilms, catalogScope, screen) {
        if (screen != "player" && withContext(Dispatchers.IO) { watchHistory.importLegacy(catalogScope, catalogFilms) } > 0) recentIndexEpoch++
    }
    val film = tmdbState.tmdbResolved[filmId] ?: films.firstOrNull { it.id == filmId }
        ?: if (filmId.startsWith("tmdb-")) Film(filmId, "影视资料暂不可用", "", "", "", "请返回搜索并重新打开此条目", R.drawable.photofilm_orbit)
        else films.firstOrNull() ?: demoFilms.first()
    // Single-source fallback is deliberately presentation-only: the original
    // Film remains the same, and legacy detail/selection paths stay usable.
    val showGroup = showCatalog.groupFor(film.id).takeUnless {
        screen == "detail" && film.id == bypassGroupFilmId
    }
    val heroSelections = remember { mutableStateMapOf<String, String>() }
    val collapsedCatalog = remember(browseFilms, catalogFilms) {
        val ids = catalogFilms.mapTo(hashSetOf(), Film::id)
        browseFilms.filter { it.id in ids }
    }
    val tmdbBrowsePage = TmdbBrowsePage.from(category).takeIf { tmdbPrimary }
    val browseRowsSnapshot = tmdbBrowseState.rows.toMap()
    val categoryHeroFilms = remember(collapsedCatalog, category, tmdbState.tmdbTop250Films, browseRowsSnapshot, tmdbBrowsePage, recentIndexFilms) {
        if (tmdbBrowsePage != null) (tmdbBrowseState.films(tmdbBrowsePage) + if (tmdbBrowsePage == TmdbBrowsePage.HOME) recentIndexFilms else emptyList()).distinctBy(Film::id)
        else BrowseHeroSelection.candidates(category, collapsedCatalog, tmdbState.tmdbTop250Films)
    }
    val categoryHero = BrowseHeroSelection.select(categoryHeroFilms, heroSelections[category])
    val displayFilm = if (screen == "browse" && category !in setOf("直播", "我的"))
        categoryHero ?: Film("loading-$category", "正在加载$category", "", category, "", "", R.drawable.photofilm_orbit)
        else showGroup?.displayFilm ?: film
    LaunchedEffect(category, categoryHero?.id, screen) {
        if (screen == "browse" && category !in setOf("直播", "我的") && categoryHero != null) filmId = categoryHero.id
    }
    // Focus/title change in the current input frame. Large hero decode and
    // full-screen crossfade should not run for every intermediate cursor stop.
    var backdropFilm by remember { mutableStateOf(displayFilm) }
    LaunchedEffect(displayFilm, screen, category) {
        if (screen == "search") delay(RemoteMotionPolicy.HERO_DWELL_MS)
        backdropFilm = displayFilm
    }
    // Item providers may outlive the frame which created them during a category change.
    // Freeze key policy with that provider instead of reading the new category midway through layout.
    val keyCategory = category
    val keysInBrowse = screen == "browse"
    fun displayKey(item: Film): String = if (screen == "search") item.id else TvFocusRoutes.posterKey(keyCategory, keysInBrowse, item.id, showCatalog.groupFor(item.id)?.key)
    // Playback-specific, observed Media3 track formats. Never sourced from a movie title or TMDB.
    val verifiedStreams = remember { mutableStateMapOf<String, VerifiedStreamSpec>() }
    LaunchedEffect(screen, filmId, spiderActive, showGroup?.key, detailLoadEpoch) {
        if (spiderActive && screen in setOf("detail", "play-intent") && !film.id.startsWith("tmdb-") &&film.spiderVodId != null) {
            val selectionConsent=spiderBridge.configurationTicket()
            val current=withContext(Dispatchers.IO){spiderBridge.isApprovedSourceFilm(film)}
            if(current &&film.playbackEpisodes.isNotEmpty())return@LaunchedEffect
            spiderStatus = "正在读取当前授权来源的详情和选集…"
            val loaded=if(current)spiderBridge.details(film) else {
                val refreshed=withTimeoutOrNull(20_000){withContext(Dispatchers.IO+SpiderRequestBudget(9_000)+SpiderSearchAuthority(selectionConsent)){
                    refreshSpiderSelection(film,tvBoxSingle,spiderBridge){site->
                        val runtime=site.jar?:tvBoxSingle?.spiderJar?:site.scriptApi.orEmpty()
                        TvBoxSpiderPolicy.mayRun(false,prefs.tvBoxWarehouseUrl,runtime,prefs.tvBoxTrustedSpiderRootUrl,prefs.tvBoxTrustedSpiderJarSpec)
                    }
                }}
                refreshed?.let{SpiderResult.Ready(it)}?:SpiderResult.Failure("旧来源会话已失效；请重新查找或应用来源，未复用旧签名地址")
            }
            when (loaded) {
                is SpiderResult.Ready -> {
                    providerFilms = providerFilms?.map { if (it.id == film.id) FilmMetadataEnrichment.mergeSourceDetails(it, loaded.value) else it }
                    spiderStatus = if (loaded.value.playbackEpisodes.isEmpty()) "该影片没有可用选集" else "已加载选集，请选择播放"
                }
                is SpiderResult.Failure -> spiderStatus = loaded.message
            }
        }
    }
    // Standard CMS responses often list only title/poster on ac=detail&pg=1.
    // Like TVBox, fetch ac=detail&ids=<vod_id> only when entering that film's
    // detail surface; no stream URL is inferred from the listing itself.
    LaunchedEffect(screen, filmId, activeCmsSite?.apiUrl, showGroup?.key, detailLoadEpoch) {
        val site = activeCmsSite
        if (showGroup == null && !spiderActive && screen in setOf("detail", "play-intent") && site != null &&
            film.cmsVodId != null && film.playbackEpisodes.isEmpty()) {
            cmsDetailStatus = "正在读取 ${film.title} 的完整选集…"
            when (val result = tvBoxVodRepo.details(site, film, tvBoxAllowHttp)) {
                is TvBoxVodDetailResult.Ready -> {
                    providerFilms = providerFilms?.map { if (it.id == film.id) FilmMetadataEnrichment.mergeSourceDetails(it, result.film) else it }
                    cmsDetailStatus = if (result.film.playbackEpisodes.isEmpty())
                        "该节点未提供可直接播放的媒体地址" else "已取得完整选集"
                }
                is TvBoxVodDetailResult.Error -> cmsDetailStatus = "影视详情：${result.reason}"
            }
        }
    }
    // Keep the selected-title CMS network coroutine OUTSIDE the giant
    // Herrin() Compose JVM method (64 KiB bytecode limit).
    CmsOneTapPlaybackEffect(
        screen = screen, film = film, site = activeCmsSite,
        spiderActive = spiderActive, pendingFilmId = cmsPlaybackPendingId,
        retryEpoch = playerRetryEpoch, allowHttp = tvBoxAllowHttp,
        repository = tvBoxVodRepo,
        onReady = { resolved, chosen ->
            providerFilms = providerFilms?.map { current ->
                if (current.id == resolved.id)
                    FilmMetadataEnrichment.mergeSourceDetails(current, resolved)
                else current
            }
            episode = chosen.first
            line = chosen.second
            cmsPlaybackFailedId = null
            cmsPlaybackPendingId = null
        },
        onFailure = { id ->
            if (cmsPlaybackPendingId == id) {
                cmsPlaybackFailedId = id
                cmsPlaybackPendingId = null
            }
        }
    )
    var groupDetailStatus by remember { mutableStateOf("") }
    // A catalog listing often has no playbackEpisodes until ac=detail/Spider
    // details. Resolve ONLY the selected show's members, on demand, bounded,
    // cancellable when leaving this detail or changing the active source.
    LaunchedEffect(screen, showGroup?.key, showGroup?.memberIds,
        spiderActive, activeCmsSite?.apiUrl, catalogScope) {
        val selected = showGroup
        if (screen !in setOf("detail", "play-intent") || selected == null || providerFilms == null) return@LaunchedEffect
        val pending = GroupSeasonIndex.detailsNeeded(selected, maxMembers = 8)
        groupDetailStatus = if (pending.isEmpty()) "" else "正在读取多季选集（0/${pending.size}）…"
        for ((index, original) in pending.withIndex()) {
            val current = providerFilms?.firstOrNull { it.id == original.id } ?: continue
            if (current.playbackEpisodes.isNotEmpty()) continue
            groupDetailStatus = "正在读取多季选集（${index + 1}/${pending.size}）：${current.title.take(28)}"
            if (spiderActive && current.spiderVodId != null) {
                val loaded = withTimeoutOrNull(3_200) { spiderBridge.details(current) }
                when (loaded) {
                    is SpiderResult.Ready -> if (loaded.value.id == current.id &&
                        loaded.value.playbackEpisodes.isNotEmpty()) {
                        providerFilms = providerFilms?.map { if (it.id == current.id) FilmMetadataEnrichment.mergeSourceDetails(it, loaded.value) else it }
                    }
                    is SpiderResult.Failure -> groupDetailStatus = "${current.title.take(24)}：${loaded.message.take(55)}"
                    null -> groupDetailStatus = "${current.title.take(24)}：选集读取超时"
                }
            } else if (!spiderActive && current.cmsVodId != null && activeCmsSite != null) {
                val loaded = withTimeoutOrNull(3_200) {
                    tvBoxVodRepo.details(activeCmsSite!!, current, tvBoxAllowHttp)
                }
                when (loaded) {
                    is TvBoxVodDetailResult.Ready -> if (loaded.film.id == current.id &&
                        loaded.film.playbackEpisodes.isNotEmpty()) {
                        providerFilms = providerFilms?.map { if (it.id == current.id) FilmMetadataEnrichment.mergeSourceDetails(it, loaded.film) else it }
                    }
                    is TvBoxVodDetailResult.Error -> groupDetailStatus = "${current.title.take(24)}：${loaded.reason.take(55)}"
                    null -> groupDetailStatus = "${current.title.take(24)}：选集读取超时"
                }
            }
        }
        val remaining = selected.members.map { it.film.id }.count { id ->
            providerFilms?.firstOrNull { it.id == id }?.playbackEpisodes.isNullOrEmpty()
        }
        groupDetailStatus = when {
            remaining == 0 -> "已载入 ${selected.members.size} 个来源条目的选集"
            remaining > 0 -> "仍有 $remaining 个条目无可用选集；请检查影视点是否提供详情或更换线路"
            else -> ""
        }
    }
    // One finite budget for the explicit selection; URL refreshes do not reset it.
    val playbackRecovery = remember(catalogScope, film.id, episode, line, screen,
        tmdbState.tmdbFallbackIndex.selected(film.id) ?: activeCmsSite?.key ?: spiderBridge.approvedActiveSiteKey()) {
        FongMiPlaybackRecovery.Session()
    }
    // Freshness is checked at an explicit playback/episode/retry entry, never used to stop an ongoing movie.
    val playbackNeedsRefresh=remember(screen,film.id,episode,line,playerRetryEpoch,tmdbState.tmdbPlaybackRoutes.validationVersion(film.id)){
        screen=="player" &&film.id.startsWith("tmdb-") &&tmdbState.discoveredSources[film.id]==null &&tmdbState.tmdbPlaybackRoutes.forFilm(film.id)!=null &&
            tmdbState.tmdbPlaybackRoutes.usable(film.id,spiderBridge.approvedActiveSiteKey())==null
    }
    LaunchedEffect(playbackNeedsRefresh){
        if(playbackNeedsRefresh){
            val coordinate=HerrinPlaybackCoordinate.from(film,episode,line)
            tmdbState.tmdbPlaybackRoutes.forget(film.id);tmdbState.tmdbPersonalPlayback.remove(film.id)
            tmdbState.tmdbResolved[film.id]?.let{old->tmdbState.tmdbResolved[film.id]=old.copy(playbackEpisodes=emptyList(),cmsVodId=null,spiderVodId=null)}
            tmdbState.tmdbSourceStatus="验证时间已过，正在重新查找并校验当前选集"
            if(coordinate!=null &&coordinate.canMapAcrossSources){
                tmdbState.automaticSearch=HerrinAutomaticPlayback.Search(film.id,coordinate,
                    watchHistory.load(catalogScope,film.id,episode,line)?.positionMs?:0)
                tmdbState.tmdbLookupEpoch++
            }else screen="detail"
        }
    }
    LaunchedEffect(screen, film.id, episode, line, spiderActive, playerRetryEpoch, tmdbState.automaticSearch,playbackNeedsRefresh) {
        val diagnosticSession = playbackDiagnosticId
        withContext(PlaybackDiagnosticSession.context(diagnosticSession)) {
        if (screen == "player") HerrinPlaybackDiagnosticJournal.record("SOURCE", "SELECTION_STARTED")
        spiderResolved = null
        spiderResolvedFor = HerrinAutomaticPlayback.Attempt(film.id, episode, line, playerRetryEpoch,
            tmdbState.tmdbFallbackIndex.selected(film.id) ?: catalogScope)
        if (spiderActive && screen == "player" && tmdbState.automaticSearch == null &&!playbackNeedsRefresh) {
            tmdbState.sourceDiscoveries[film.id]?.spiderPaused = true
            tmdbState.discoverySpiderLock.lock()
            try {
            val selectionConsent=spiderBridge.configurationTicket()
            val sourceFilm = if (film.id.startsWith("tmdb-")) tmdbState.tmdbPlaybackRoutes.sourceFilm(film.id) else film
            if (sourceFilm == null) {
                spiderResolved = PlaybackResult.Unavailable("影片来源已失效，请重新查找片源")
                return@withContext
            }
            var playableFilm: Film = sourceFilm
            if(!withContext(Dispatchers.IO){spiderBridge.isApprovedSourceFilm(playableFilm)}){
                val fresh=withTimeoutOrNull(20_000){withContext(Dispatchers.IO+SpiderRequestBudget(9_000)+SpiderSearchAuthority(selectionConsent)){
                    refreshSpiderSelection(playableFilm,tvBoxSingle,spiderBridge){site->
                        TvBoxSpiderPolicy.mayRun(false,prefs.tvBoxWarehouseUrl,site.jar?:tvBoxSingle?.spiderJar?:site.scriptApi.orEmpty(),
                            prefs.tvBoxTrustedSpiderRootUrl,prefs.tvBoxTrustedSpiderJarSpec)
                    }
                }}
                if(fresh==null ||fresh.playbackEpisodes.none{ep->ep.number==episode &&ep.lines.any{it.id==line}}){
                    spiderResolved=PlaybackResult.Unavailable("来源会话或选集已变化，请返回详情重新选择")
                    return@withContext
                }
                playableFilm=fresh
                if(film.id.startsWith("tmdb-")){
                    tmdbState.tmdbPlaybackRoutes.spider(film.id,requireNotNull(fresh.spiderOrigin).site,fresh)
                    tmdbState.tmdbResolved[film.id]?.let{metadata->tmdbState.tmdbResolved[film.id]=metadata.copy(
                        spiderVodId=fresh.spiderVodId,playbackEpisodes=fresh.playbackEpisodes,episodes=fresh.playbackEpisodes.size)}
                }else providerFilms=providerFilms?.map{if(it.id==film.id)FilmMetadataEnrichment.mergeSourceDetails(it,fresh) else it}
            }
            // Load missing episode metadata IN the full-screen loading surface.
            // Do not bounce a film-card click to a technical Spider detail page.
            if (playableFilm.playbackEpisodes.isEmpty() && playableFilm.spiderVodId != null) {
                when (val detailed = spiderBridge.details(playableFilm)) {
                    is SpiderResult.Ready -> {
                        playableFilm = detailed.value
                        providerFilms = providerFilms?.map {
                            if (it.id == film.id) FilmMetadataEnrichment.mergeSourceDetails(it, detailed.value) else it
                        }
                        val first = detailed.value.playbackEpisodes.firstOrNull()
                        val option = first?.lines?.firstOrNull()
                        if (first == null || option == null) {
                            spiderResolved = PlaybackResult.Unavailable("没有可用选集")
                            return@withContext
                        }
                        // Episode/line state changes restart this effect with the
                        // newly loaded Film, avoiding a double playerContent call.
                        if (episode != first.number || line != option.id) {
                            episode = first.number
                            line = option.id
                            return@withContext
                        }
                    }
                    is SpiderResult.Failure -> {
                        spiderResolved = PlaybackResult.Unavailable(detailed.message)
                        return@withContext
                    }
                }
            }
            val parsed = spiderBridge.play(playableFilm, episode, line, tvBoxAllowHttp,
                foregroundPlayback = true)
            spiderResolved = if(parsed is PlaybackResult.Ready && parsed.media.kernelPlayback != null) {
                HerrinPlaybackDiagnosticJournal.record("SOURCE", "KERNEL_MEDIA_VALIDATION_DEFERRED")
                parsed
            } else if(parsed is PlaybackResult.Ready) {
                val admitted = withContext(Dispatchers.IO) {
                    SelectedSpiderPlayback.admit(parsed.media, spiderBridge.approvedActiveSiteKey(),
                        remoteProbe = { media ->
                            val validation = withTimeoutOrNull(4_000) {
                                HerrinRemoteMediaProbe(authorizeLocal = { candidate ->
                                    FongMiSessionAuthorization.isApproved(candidate, spiderBridge.approvedActiveSiteKey())
                                }, evidence = { code, detail -> HerrinPlaybackDiagnosticJournal.record("SOURCE", code, detail, diagnosticSession) }).probe(media)
                            }
                            HerrinPlaybackDiagnosticJournal.record("SOURCE", "MEDIA_PROBE_${validation?.name ?: "PROBE_TIMEOUT"}")
                            validation == HerrinRemoteMediaProbe.Result.MEDIA
                        }, evidence = { code -> HerrinPlaybackDiagnosticJournal.record("SOURCE", code, "", diagnosticSession) })
                }
                if (!admitted) HerrinPlaybackDiagnosticJournal.record("SOURCE", "MEDIA_PROBE_REJECTED", "", diagnosticSession)
                if(admitted) parsed else
                    PlaybackResult.Unavailable("MEDIA_PROBE_REJECTED：当前来源授权或媒体响应验证失败；可重试、换线路或返回其他影视点")
            } else parsed
            // Record in the UI process: the :spider worker has a separate
            // in-memory journal. Without this, the on-TV journal says NO_EVENTS
            // even though the user sees SOURCE_PROXY_INVALID_ROUTE.
            when (val outcome = spiderResolved) {
                is PlaybackResult.Unavailable -> {
                    HerrinPlaybackDiagnosticJournal.record("SPIDER", "PLAYER_CONTENT_REJECTED")
                    if ("PROXY_INVALID_ROUTE" in outcome.reason) {
                        val code = Regex(
                            "route_reason=(UNSUPPORTED_OPERATION|SITE_KEY_MISMATCH|" +
                                "STATIC_MARKER_FORGED|INVALID_ALIAS|INVALID_QUERY|" +
                                "NOT_LOCAL_PROXY|NOT_PROXY_PATH|PROXY_STOPPED|" +
                                "LOOPBACK_ROUTE_REJECTED|ALIAS_REJECTED)"
                        ).find(outcome.reason)?.groupValues?.get(1) ?: "UNCLASSIFIED"
                        HerrinPlaybackDiagnosticJournal.record("PROXY", "ROUTE_$code")
                    }
                }
                is PlaybackResult.Ready ->
                    HerrinPlaybackDiagnosticJournal.record("SPIDER", "MEDIA_ROUTE_READY")
                null ->
                    HerrinPlaybackDiagnosticJournal.record("SPIDER", "PLAYBACK_EMPTY")
            }
            spiderStatus = when (val result = spiderResolved) {
                is PlaybackResult.Ready -> "播放地址已解析，正在准备播放器"
                is PlaybackResult.Unavailable -> result.reason
                else -> "播放解析未返回结果"
            }
            // Android can publish ApplicationExitInfo AFTER the Binder death
            // callback. Refresh only this failed playback's finite OS category,
            // without altering the requested source, episode or any credential.
            val failedStatus = spiderStatus
            if (failedStatus.contains("Spider 子进程异常退出") &&
                !failedStatus.contains("系统原因：")) {
                delay(900)
                val exitDetail=withContext(Dispatchers.IO){
                    listOfNotNull(SpiderStartupTrace.recent(context)?.let{"最后阶段：$it"},
                        SpiderExitDiagnostics.recent(context)?.let{"系统原因：$it"}, SpiderFailureTrace.recent(context)).joinToString("；")
                }
                exitDetail.takeIf{it.isNotBlank()}?.let { reason ->
                    if (spiderStatus == failedStatus) spiderStatus =
                        "$failedStatus；$reason"
                }
            }
            } finally {
                tmdbState.discoverySpiderLock.unlock()
            }
        }
        }
    }
    LaunchedEffect(screen, film.id) {
        if (screen != "player" || tmdbState.automaticSearch?.film?.let { it != film.id } == true) {
            tmdbState.automaticSearch = null
            tmdbState.automaticResume = null
            tmdbState.automaticPlayback.clear()
        }
    }
    val currentEpisode = film.playbackEpisodes.firstOrNull { it.number == episode }
    val currentLines = currentEpisode?.lines.orEmpty()
    val seasonLayout = remember(film.id, film.playbackEpisodes) { SeasonEpisodes.fromFilm(film) }
    val groupLayout = remember(showGroup) { showGroup?.let(GroupSeasonIndex::from) }
    val unmappedGroupMembers = showGroup?.members?.filter { member ->
        groupLayout?.sections?.any { section ->
            section.episodes.any { entry -> entry.sources.any { it.film.id == member.film.id } }
        } != true
    }.orEmpty()
    val groupResume = groupLayout?.let { layout ->
        GroupSeasonIndex.latest(layout) { option ->
            watchHistory.load(catalogScope, option.film.id, option.source.number, option.line.id)
        }
    }
    val groupSection = groupLayout?.sections?.firstOrNull { it.number == detailSelectedSeason }
        ?: groupLayout?.sections?.firstOrNull { it.number == groupResume?.first?.season }
        ?: groupLayout?.sections?.firstOrNull()
    val groupRowState = remember(showGroup?.key, groupSection?.number) { LazyListState() }
    val groupFocusedEpisode = groupFocusedCoordinates?.let { (season, number) ->
        groupLayout?.episode(season, number)
    } ?: groupSection?.episodes?.firstOrNull()
    val selectedGroupOption = groupLayout?.sections?.asSequence()?.flatMap { it.episodes.asSequence() }
        ?.flatMap { it.sources.asSequence() }?.firstOrNull { option ->
            option.film.id == film.id && option.source.number == episode && option.line.id == line
        } ?: groupResume?.first
    val seasonResume = seasonLayout?.let { layout ->
        SeasonPlayback.latest(layout) { sourceNumber, lineId ->
            watchHistory.load(catalogScope, film.id, sourceNumber, lineId)
        }
    }
    val detailSeason = seasonLayout?.sections?.firstOrNull {
        it.number == detailSelectedSeason
    } ?: seasonLayout?.sections?.firstOrNull {
        it.number == seasonResume?.entry?.season
    } ?: seasonLayout?.sections?.lastOrNull()
    val detailSeasonEntries = detailSeason?.episodes.orEmpty()
    val detailSeasonSource = detailSeasonEntries.firstOrNull { entry ->
        entry.source.number == episode && entry.lines.any { it.id == line }
    }
    val displayedLines = (detailSeasonSource ?: detailSeasonEntries.firstOrNull())?.lines ?: currentLines
    val detailSeasonRowState = remember(film.id, detailSeason?.number) { LazyListState() }
    var seasonOfficialEpisodes by remember(film.id, film.tmdbTvId, detailSeason?.number, metadataToken) {
        mutableStateOf<Map<Int, TmdbEpisodeDisplay>>(emptyMap())
    }
    LaunchedEffect(film.id, film.tmdbTvId, detailSeason?.number, metadataToken) {
        val seasonNumber = detailSeason?.number
        seasonOfficialEpisodes = if (film.metadataSource == "TMDB" &&
            film.tmdbTvId != null && seasonNumber != null && metadataToken.isNotBlank()) {
            withContext(Dispatchers.IO) {
                TmdbSeasonEpisodes(java.io.File(context.cacheDir, "tmdb-artwork"),
                    NetworkArtworkFetcher).lookup(film.tmdbTvId, seasonNumber, metadataToken)
            }
        } else emptyMap()
    }
    var groupOfficialEpisodes by remember(showGroup?.key, showGroup?.tmdbTvId, groupSection?.number, metadataToken) {
        mutableStateOf<Map<Int, TmdbEpisodeDisplay>>(emptyMap())
    }
    LaunchedEffect(screen, showGroup?.key, showGroup?.tmdbTvId, groupSection?.number, metadataToken) {
        val id = showGroup?.tmdbTvId
        val season = groupSection?.number
        groupOfficialEpisodes = if (screen == "detail" && id != null && season != null &&
            metadataToken.isNotBlank()) withContext(Dispatchers.IO) {
            TmdbSeasonEpisodes(java.io.File(context.cacheDir, "tmdb-artwork"),
                NetworkArtworkFetcher).lookup(id, season, metadataToken)
        } else emptyMap()
    }
    val streamEvidenceKey = "$catalogScope|$catalogRefresh|${film.id}|$episode|$line|" +
        currentLines.firstOrNull { it.id == line }?.uri.orEmpty()
    val resume = watchHistory.load(catalogScope, film.id, episode,
        if (currentLines.isEmpty()) "independent-test" else line)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val settingsListState = rememberLazyListState()
    var lastDetailLineFocus by remember { mutableStateOf("detail-first-line") }
    var restorePlaybackDetailFocus by remember { mutableStateOf(false) }
    var detailFavoriteAttached by remember { mutableStateOf(false) }
    var detailFavoriteFocused by remember { mutableStateOf(false) }
    LaunchedEffect(screen, restorePlaybackDetailFocus, detailFavoriteAttached) {
        if (screen == "detail" && restorePlaybackDetailFocus) {
            // The detail list has no screen Back row: header, synopsis, favorite.
            // Scroll before requesting, and wait for the real placed focus node.
            if (!detailFavoriteAttached) settingsListState.scrollToItem(2)
            withFrameNanos { }
            if (detailFavoriteAttached) {
                requester("detail-favorite").requestFocus()
                if (detailFavoriteFocused) restorePlaybackDetailFocus = false
            }
        }
    }
    val detailFocusJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun enterDetailEpisodes() {
        detailFocusJob[0]?.cancel()
        detailFocusJob[0] = uiScope.launch {
            DetailRegionFocus.move(settingsListState, requester("detail-current-episode"), true)
        }
    }
    LaunchedEffect(screen, film.id) { detailFocusJob[0]?.cancel(); if (screen == "detail") lastDetailLineFocus = "detail-first-line" }
    fun returnDetailLine(): Boolean {
        detailFocusJob[0]?.cancel()
        detailFocusJob[0] = uiScope.launch {
            DetailRegionFocus.move(settingsListState, requester(lastDetailLineFocus), false)
        }
        return true
    }
    // Cast is the final offscreen LazyColumn item. Scroll it into composition
    // before requesting focus; native spatial search cannot reach detached rows.
    fun moveToDetailCast(): Boolean = screen == "detail" &&
        DetailCastFocus.move(uiScope, settingsListState,
            displayFilm.people.isNotEmpty() || displayFilm.actors.isNotEmpty(),
            requester("detail-person-0")) {
            focusId == "detail-person-0"
        }
    LaunchedEffect(settingsTvBoxOnly) {
        if (screen == "settings" && !settingsTvBoxOnly) {
            // Expanding the deep diagnostics form replaces the compact list.
            // Its old scroll offset and the removed button must not strand focus.
            settingsListState.scrollToItem(0)
            withFrameNanos { }
            runCatching { requester("screen-first").requestFocus() }
        }
    }
    var settingsModule by remember { mutableStateOf("影视源") }
    var settingsNotice by remember { mutableStateOf("") }
    var settingsNoticeRevision by remember { mutableIntStateOf(0) }
    var siteMenuOpen by remember { mutableStateOf(false) }
    fun settingsResult(ok: Boolean) {
        settingsNotice = if (ok) "成功" else "失败"
        settingsNoticeRevision++
    }
    LaunchedEffect(settingsNoticeRevision) {
        if (settingsNotice.isNotBlank()) { delay(2400); settingsNotice = "" }
    }
    LaunchedEffect(vodImportFeedback, liveImportFeedback) {
        if (screen != "settings" || !settingsTvBoxOnly) return@LaunchedEffect
        val state = when (settingsModule) {
            "影视源" -> vodImportFeedback
            "直播源" -> liveImportFeedback
            else -> ""
        }
        if (state == "成功") settingsResult(true)
        else if (state.contains("失败") || state.contains("崩溃") || state.contains("超时")) settingsResult(false)
    }
    var personalProtocol by remember { mutableStateOf("WebDAV") }
    var lastMovieSettingsAction by remember { mutableStateOf("tvbox-warehouse-load") }
    // Latest D-pad press wins: an old animated scroll must never block a newer
    // request. Scroll instantly, then focus after the Lazy item is attached.
    // In-flight jobs are implementation details, not UI state: updating them
    // must not trigger a global Compose recomposition on every remote press.
    val settingsFocusJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val settingsIntent = remember { CompactSettingsIntent() }
    LaunchedEffect(screen, settingsTvBoxOnly, configInputFocused) {
        if (screen != "settings" || !settingsTvBoxOnly || configInputFocused) {
            settingsFocusJob[0]?.cancel()
            settingsIntent.clear()
        }
    }
    fun moveCompactSettingsFocus(destination: String) {
        val target = if (screen == "settings" && settingsTvBoxOnly && destination == "screen-first")
            "phone-config-main" else destination
        val moduleTargets = when (settingsModule) {
            "影视源" -> setOf("screen-first", "phone-config-main", "tvbox-warehouse-url", "tvbox-warehouse-load", "settings-source-sites")
            "海报与资料" -> setOf("screen-first", "phone-config-main", "compact-tmdb-split-toggle", "compact-tmdb-token", "compact-tmdb-part-1", "compact-tmdb-part-2", "compact-tmdb-part-3", "compact-tmdb-save", "compact-tmdb-rescan")
            "直播源" -> setOf("screen-first", "phone-config-main", "tvbox-live-direct-url", "tvbox-live-direct-load")
            else -> setOf("screen-first", "phone-config-main")
        }
        if (target !in moduleTargets) { runCatching { requester("phone-config-main").requestFocus() }; return }
        val itemIndex = when (settingsModule) {
            "海报与资料" -> when(target) { "screen-first", "phone-config-main" -> 0; "compact-tmdb-save", "compact-tmdb-rescan" -> 5; else -> 4 }
            "直播源" -> when(target) { "tvbox-live-direct-url" -> 3; "tvbox-live-direct-load" -> 4; else -> 0 }
            else -> TvFocusRoutes.compactSettingsItemIndex(
            target,
            sourceStatus = false,
            connectedStatus = false,
            failedStatus = false,
            noMatchesStatus = false
        )
        } ?: return
        settingsIntent.request(target)
        settingsFocusJob[0]?.cancel()
        fun confirmFocus(): Boolean {
            if (runCatching { requester(target).requestFocus() }.isFailure) return false
            if (focusId != target) return false
            settingsIntent.settled(target)
            return true
        }
        if (settingsListState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex } &&
            confirmFocus()) return
        settingsFocusJob[0] = uiScope.launch {
            settingsListState.scrollToItem(itemIndex)
            if (confirmFocus()) return@launch
            repeat(RemoteMotionPolicy.FOCUS_RETRY_FRAMES) {
                withFrameNanos { }
                if (confirmFocus()) return@launch
            }
            // If this target never attached, do not keep routing future keys
            // from a stale offscreen destination.
            settingsIntent.abandoned(target)
        }
    }
    val posterFocusJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    DisposableEffect(screen, category) {
        onDispose { posterFocusJob[0]?.cancel(); posterFocusJob[0] = null }
    }
    fun transferPosterFocus(from: LazyListState, to: LazyListState,
                            fromIndex: Int, destination: List<Film>, prefix: String,
                            vertical: androidx.compose.foundation.ScrollState, toBottom: Boolean) {
        if (destination.isEmpty()) return
        posterFocusJob[0]?.cancel()
        val index = fromIndex.coerceIn(0, destination.lastIndex)
        val sourceOffset = from.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == fromIndex }?.offset?.coerceAtLeast(0) ?: 0
        val target = "$prefix${displayKey(destination[index])}"
        // Cancelled jobs do not hold a global boolean lock. Rapid alternating
        // UP/DOWN requests can supersede pending layout/focus restoration.
        posterFocusJob[0] = uiScope.launch {
            // FocusRequester.requestFocus() returns Unit in this Compose version.
            // The onFocus callback, however, updates focusId synchronously,
            // so validate the actual destination instead of a nonthrowing call.
            fun requestActualFocus(): Boolean {
                if (runCatching { requester(target).requestFocus() }.isFailure) return false
                return focusId == target
            }
            val aligned = to.layoutInfo.visibleItemsInfo.any {
                it.index == index && it.offset - sourceOffset in -24..24
            }
            val verticalTarget = if (toBottom) vertical.maxValue else 0
            // For an already-attached destination, move the focus immediately
            // BEFORE any scrolling or waiting for another render frame.
            // Keep scroll and focus inside this cancellable latest-key job.
            if (aligned && requestActualFocus()) {
                if (vertical.value != verticalTarget) vertical.scrollTo(verticalTarget)
                RemoteTrace.event { "RAIL immediate target=$target x=$sourceOffset" }
                return@launch
            }
            if (vertical.value != verticalTarget) vertical.scrollTo(verticalTarget)
            if (!aligned) to.scrollToItem(index, -sourceOffset)
            // A LazyRow may attach during scrollToItem, so try once in this
            // frame before the three-frame offscreen-item recovery path.
            if (requestActualFocus()) {
                RemoteTrace.event { "RAIL post-scroll immediate target=$target" }
                return@launch
            }
            repeat(RemoteMotionPolicy.FOCUS_RETRY_FRAMES) {
                withFrameNanos { }
                if (requestActualFocus()) {
                    RemoteTrace.event { "RAIL attached target=$target x=$sourceOffset" }
                    return@launch
                }
            }
            RemoteTrace.event { "RAIL focus not confirmed target=$target" }
        }
    }
    var tmdbBrowseReturn by remember { mutableStateOf<PlaybackReturnContext?>(null) }
    var pendingHistoryFilm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen, pendingHistoryFilm, film.id, film.playbackEpisodes, catalogScope, tmdbState.sourceForeground, tmdbState.sourceSelectionReady(film.id)) {
        if (screen !in setOf("detail", "play-intent")) {
            pendingHistoryFilm = null
            tmdbState.resumeSourceFilm = null
            return@LaunchedEffect
        }
        if (!tmdbState.sourceSelectionReady(film.id)) return@LaunchedEffect
        if (screen == "play-intent" && !tmdbState.sourceForeground) return@LaunchedEffect
        if (pendingHistoryFilm != film.id || film.playbackEpisodes.isEmpty()) return@LaunchedEffect
        val saved = if (tmdbState.automaticResume?.film == film.id) null else
            withContext(Dispatchers.IO) { watchHistory.resolveIndexed(film, catalogScope,
                personalFileRoutes = tmdbState.tmdbPersonalPlayback[film.id]?.isNotEmpty() == true) }
        if (saved != null) {
            episode = saved.first.episode; line = saved.first.line
            seasonLayout?.sections?.firstOrNull { section -> section.episodes.any { it.source.number == episode } }?.let {
                detailSelectedSeason = it.number
                detailSeasonEpisodeNumbers[it.number] = episode
                detailSeasonLines[it.number] = line
            }
            tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(film.id, episode, line, saved.second)
        }
        pendingHistoryFilm = null
        if (screen == "play-intent") {
            if (saved == null && tmdbState.automaticResume?.film != film.id) HerrinPlaybackEntry.firstPlayable(film)?.let { (number, id) -> episode = number; line = id }
            screen = "player"
        }
    }
    OneTapDeadline(screen, film.id, tmdbState.sourceForeground) { tmdbState.discoveryRecovery = null; screen = "detail" }
    fun details(f: Film) {
        tmdbState.directPlayFilm = null
        tmdbState.discoveryRecovery = null
        tmdbState.rejectedDiscoveryHits.clear()
        DetailPlaybackMetrics.timeline.enter()
        if (screen == "browse") tmdbBrowseReturn = PlaybackReturnContext(screen, category, focusId, f.id)
        tmdbState.resumeSourceFilm = null
        RemoteTrace.event { "DETAILS invoked film=${f.id} from=$screen" }
        cmsDetailStatus = ""
        if (f.id.startsWith("tmdb-")) {
            // Restore exactly the source selected for this title before the user
            // can start the player. A different title may have changed the
            // global activeCmsSite/spiderActive since this title last opened.
            when (val route = tmdbState.tmdbPlaybackRoutes.usable(
                f.id, spiderBridge.approvedActiveSiteKey())) {
                is HerrinTmdbPlaybackRouteStore.Route.Cms -> {
                    activeCmsSite = route.site
                    spiderActive = false
                }
                is HerrinTmdbPlaybackRouteStore.Route.Spider -> {
                    activeCmsSite = null
                    spiderActive = true
                }
                HerrinTmdbPlaybackRouteStore.Route.Personal -> {
                    activeCmsSite = null
                    spiderActive = false
                }
                null -> {
                    // The old Spider session is no longer approved; a cached
                    // playback route must not reactivate it or expose stale URLs.
                    if (tmdbState.tmdbPlaybackRoutes.forFilm(f.id) != null) {
                        tmdbState.tmdbPlaybackRoutes.forget(f.id)
                        tmdbState.tmdbResolved[f.id]?.let { saved ->
                            tmdbState.tmdbResolved[f.id] = saved.copy(playbackEpisodes = emptyList(),
                                cmsVodId = null, spiderVodId = null)
                        }
                        tmdbState.tmdbPersonalPlayback.remove(f.id)
                        tmdbState.tmdbSourceStatus = "之前的 Spider 会话已失效，正在重新检索授权片源"
                        tmdbState.tmdbLookupEpoch++
                    }
                }
            }
        }
        filmId = f.id
        pendingHistoryFilm = f.id
        bypassGroupFilmId = null
        detailSelectedSeason = 0
        restoreSeasonFocus = false
        restoreGroupFocus = false
        groupFocusedCoordinates = null
        detailSeasonLines.clear()
        detailSeasonEpisodeNumbers.clear()
        val firstRealLine = HerrinPlaybackEntry.firstPlayable(f)
        episode = firstRealLine?.first ?: 1
        line = firstRealLine?.second ?: "演示线路 A"
        returnScreen = screen
        screen = "detail"
    }
    fun openMovie(item: Film) {
        if (screen == "search") tmdbState.searchReturnFocus = "card-${item.id}"
        // Index only the title actually selected by the user. Its original
        // source proof stays intact; normal detail/replay validation still owns it.
        if (screen == "search" && spiderActive && item.spiderVodId != null &&
            item.spiderOrigin?.site == spiderBridge.approvedActiveSiteKey())
            providerFilms = (listOf(item) + providerFilms.orEmpty().filterNot { it.id == item.id }).take(240)
        // The user chooses a film first, then its real episode and source.
        // Never start playback merely because a poster was selected.
        if (tmdbDiscovery.identity(item) != null && item.id !in tmdbState.tmdbResolved) tmdbState.tmdbResolved[item.id] = item
        if (tmdbDiscovery.identity(item) != null) uiScope.launch(Dispatchers.IO) {
            val snapshots = TvBoxCatalogSnapshot(context, Long.MAX_VALUE)
            snapshots.save("tmdb-library", "selected", (listOf(item) + snapshots.load("tmdb-library", "selected")).distinctBy(Film::id))
        }
        details(tmdbState.tmdbResolved[item.id] ?: item)
    }
    fun playIntent(item: Film) {
        tmdbState.discoveryRecovery = null
        tmdbState.rejectedDiscoveryHits.clear()
        openMovie(item)
        tmdbState.directPlayFilm = item.id
        tmdbState.discoveryLine = null
        tmdbState.loadedDiscoveryHits[item.id]?.let { tmdbState.requestedDiscoveryHits[item.id] = it }
            ?: tmdbState.requestedDiscoveryHits.remove(item.id)
        pendingHistoryFilm = item.id
        tmdbState.resumeSourceFilm = item.id
        screen = "play-intent"
        DetailPlaybackMetrics.timeline.click()
        HerrinPlaybackDiagnosticJournal.record("LOOKUP", "PLAY_INTENT")
    }
    fun selectDetailSeason(seasonNumber: Int) {
        val section = seasonLayout?.sections?.firstOrNull { it.number == seasonNumber } ?: return
        detailSelectedSeason = seasonNumber
        val entry = section.episodes.firstOrNull {
            it.source.number == detailSeasonEpisodeNumbers[seasonNumber]
        } ?: section.episodes.first()
        episode = entry.source.number
        line = entry.preferredLine(detailSeasonLines[seasonNumber]).id
    }
    fun playDiscoveryEpisode(number: Int, lineId: String): Boolean {
        val hitKey = tmdbState.requestedDiscoveryHits[film.id] ?: return false
        val hit = tmdbState.discoveredSources[film.id]?.hits?.firstOrNull { it.key == hitKey }
            ?: return false
        if (hit.episodes.none { it.number == number && it.lines.any { pair -> pair.first == lineId } }) return false
        if (tmdbState.discoveryLoadingFilm != film.id && tmdbState.discoverySelection != (film.id to hitKey)) {
            tmdbState.discoverySelection = film.id to hitKey
            tmdbState.discoverySelectionEpoch++
        }
        tmdbState.discoveryLine = lineId
        tmdbState.requestDiscoveryPlay(film.id, hitKey, number)
        return true
    }
    fun playSeasonEpisode(entry: SeasonEpisode, preferredLineId: String? = detailSeasonLines[entry.season]) {
        DetailPlaybackMetrics.timeline.click()
        val chosen = entry.preferredLine(preferredLineId)
        val request = SeasonPlayback.target(film, entry, chosen)
        detailSelectedSeason = entry.season
        detailSeasonEpisodeNumbers[entry.season] = entry.source.number
        detailSeasonLines[entry.season] = chosen.id
        episode = request.episode
        line = request.line
        restoreSeasonFocus = true
        if (film.id.startsWith("tmdb-") && !tmdbState.sourceSelectionReady(film.id)) {
            playDiscoveryEpisode(request.episode, request.line)
        } else if (!HerrinPlaybackEntry.unavailableTmdb(film)) screen = "player"
    }
    fun selectGroupSeason(seasonNumber: Int) {
        if (groupLayout?.sections?.none { it.number == seasonNumber } != false) return
        if (detailSelectedSeason != seasonNumber) groupFocusedCoordinates = null
        detailSelectedSeason = seasonNumber
    }
    fun selectGroupSource(option: GroupPlaybackRef) {
        val request = option.request()
        filmId = request.filmId
        episode = request.episode
        line = request.line
        detailSelectedSeason = option.season
        groupFocusedCoordinates = option.season to option.number
        // Line selection does not initiate playback.
    }
    fun playGroupSource(option: GroupPlaybackRef) {
        DetailPlaybackMetrics.timeline.click()
        val request = option.request()
        // The player and every licensed CMS / Spider resolver read the selected
        // original source Film. A group key is NEVER a playable Film ID.
        filmId = request.filmId
        episode = request.episode
        line = request.line
        detailSelectedSeason = option.season
        groupFocusedCoordinates = option.season to option.number
        restoreGroupFocus = true
        screen = "player"
    }
    fun playGroupEpisode(entry: GroupEpisode) {
        val mostRecent = entry.sources.mapNotNull { source ->
            watchHistory.load(catalogScope, source.film.id, source.source.number, source.line.id)
                ?.let { source to it }
        }.maxByOrNull { it.second.updatedAtMs }?.first
        playGroupSource(entry.preferred(selectedGroupOption ?: mostRecent))
    }
    LaunchedEffect(screen, film.id, detailSeason?.number, restoreSeasonFocus) {
        if (screen == "detail" && showGroup == null && restoreSeasonFocus && detailSeasonEntries.isNotEmpty()) {
            val focusIndex = detailSeasonEntries.indexOfFirst {
                it.source.number == episode && it.lines.any { option -> option.id == line }
            }.coerceAtLeast(0)
            detailSeasonRowState.scrollToItem(focusIndex)
            repeat(3) {
                withFrameNanos { }
                if (runCatching { requester("season-card-${detailSeason!!.number}-$focusIndex").requestFocus() }.isSuccess) {
                    restoreSeasonFocus = false
                    return@LaunchedEffect
                }
            }
            restoreSeasonFocus = false
        }
    }
    fun activateTvBoxSite(site: TvBoxSite, selected: TvBoxConfig.Single?, allowHttp: Boolean = true) {
        // If first-node auto selection already started this CMS request, navigate now;
        // its original request will publish the fetched catalog without a second GET.
        if (site.apiUrl != null && site == autoCmsRequest) {
            providerFilms = null
            category = "首页"; screen = "browse"
            return
        }
        if (site == activeCmsSite && providerFilms != null) {
            category = "首页"; screen = "browse"
            return
        }
        siteChoiceEpoch.incrementAndGet()
        siteActivationJob[0]?.cancel()
        when {
            site.apiUrl != null -> { siteActivationJob[0] = uiScope.launch {
                val pendingImportId = phoneSourceId
                choosePreferredSite(site, selected?.spiderJar)
                tvBoxStatus = "正在读取 ${site.name}…"
                when (val result = tvBoxVodRepo.load(site, allowHttp)) {
                    is TvBoxVodResult.Error -> { tvBoxStatus = "${site.name}：${result.reason}"; failPhoneSource("SOURCE_SITE_ACTIVATION_FAILED") }
                    is TvBoxVodResult.Ready -> {
                        if (!finishPhoneSource(pendingImportId)) return@launch
                        configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
                        spiderBridge.close()
                        spiderActive = false
                        activeCmsSite = site
                        prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                        prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, selected?.spiderJar)
                        providerFilms = result.films
                        providerStatus = "TVBox ${site.name} · ${result.films.size} 部"
                        tvBoxVodScope = "tvbox:${site.apiUrl}"
                        result.films.firstOrNull()?.let { filmId = it.id }
                        category = "首页"
                        screen = "browse"
                        tvBoxStatus = "已导入 ${site.name} 的影片目录"
                    }
                }
            }
            }
            (site.spiderApi != null || site.scriptApi != null) && selected != null -> { siteActivationJob[0] = uiScope.launch {
                val pendingImportId = phoneSourceId
                choosePreferredSite(site, selected.spiderJar)
                spiderBridge.close()
                HerrinPlaybackDiagnosticJournal.record("SPIDER", "USER_RESELECT_RESET")
                tvBoxStatus = "正在通过 ${site.name} 加载影片…"
                when (val result = spiderBridge.browse(site, selected)) {
                    is SpiderResult.Failure -> { tvBoxStatus = "${site.name}：${result.message}"; failPhoneSource("SOURCE_SITE_ACTIVATION_FAILED") }
                    is SpiderResult.Ready -> {
                        if (!finishPhoneSource(pendingImportId)) return@launch
                        configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
                        spiderActive = true
                        activeCmsSite = null
                        prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                        prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, selected.spiderJar)
                        providerFilms = result.value
                        providerStatus = "TVBox ${site.name} · ${result.value.size} 部"
                        tvBoxVodScope = "spider:${site.key}:${selected.spiderJar}"
                        result.value.firstOrNull()?.let { filmId = it.id }
                        prefs.tvBoxTrustedSpiderRootUrl = prefs.tvBoxWarehouseUrl
                        prefs.tvBoxTrustedSpiderJarSpec = site.jar ?: selected.spiderJar.orEmpty()
                        category = "首页"
                        screen = "browse"
                        tvBoxStatus = if (result.value.isEmpty())
                            "已启用 ${site.name}；首页暂未返回影片，可继续搜索或检查来源授权"
                        else "已导入 ${site.name} 的影片目录"
                    }
                }
            }
            }
            site.scriptApi != null -> tvBoxStatus = "${site.name} 属于 TVBox JS 规则，当前尚未集成对应运行时，请选择 Spider 站点"
            else -> tvBoxStatus = "${site.name} 使用尚不支持的插件格式，无法直接解析"
        }
    }
    DisposableEffect(spiderBridge) { onDispose { siteActivationJob[0]?.cancel() } }
    // Hiding an IME window alone does not release the TV IME's served input connection.
    // Wait for Compose's text-focus cancellation, then rebuild the native connection without an editor.
    LaunchedEffect(screen, focusId, searchInputFocused, configInputFocused) {
        val leavingSearchEditor = screen == "search" && !searchInputFocused &&
            (focusId.startsWith("card-") || (focusId.startsWith("search-") && focusId != "search-input"))
        val leavingSettingsEditor = screen == "settings" && !configInputFocused
        if (leavingSearchEditor || leavingSettingsEditor) {
            val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            // Read-only blur already ends the Compose session. Do not start an
            // asynchronous IME hide/restart transaction for an already hidden,
            // closed editor; retained CI traces show it swallowing DOWN only.
            if(leavingSearchEditor && !inputMethod.isAcceptingText && !imeVisible) return@LaunchedEffect
            keyboard?.hide()
            withFrameNanos { }
            inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
            // Restarting the input connection while a TV settings button has focus
            // reopens some television IMEs and swallows its first D-pad CENTER key.
            // Search is read-only after blur: Compose normally already ended the
            // connection. CI traced a lost DOWN / orphan UP after restarting that
            // closed session. Restart only if Android still serves an editor.
            if (leavingSearchEditor && inputMethod.isAcceptingText) inputMethod.restartInput(view)
            RemoteTrace.event { "IME handoff search=$leavingSearchEditor settings=$leavingSettingsEditor acceptingText=${inputMethod.isAcceptingText} nativeFocus=${view.hasFocus()}" }
        }
    }
    var lastNonPlayback by remember { mutableStateOf(PlaybackReturnContext("detail", category, focusId, filmId)) }
    val committedReturnContext = PlaybackReturnContext(screen, category, focusId, filmId)
    // The first player frame already needs the committed origin. An async
    // LaunchedEffect must not leave a fast Back using the previous film's origin.
    val playbackReturn = if (screen in setOf("player", "play-intent")) lastNonPlayback else committedReturnContext
    SideEffect {
        if (committedReturnContext.screen !in setOf("player", "play-intent")) lastNonPlayback = committedReturnContext
    }
    LaunchedEffect(screen) { if (screen != "player") recentIndexEpoch++ }
    fun back() {
        when (screen) {
            "player" -> {
                restoreGroupFocus = showGroup != null
                restoreSeasonFocus = showGroup == null && seasonLayout != null
                category = playbackReturn.category
                if (playbackReturn.screen == "detail") {
                    if (films.any { it.id == playbackReturn.film }) filmId = playbackReturn.film
                } else {
                    // Direct My/recent playback still returns to this film's
                    // details first; Back from details restores the original rail.
                    returnScreen = playbackReturn.screen
                    if (playbackReturn.screen == "browse") tmdbBrowseReturn = playbackReturn
                    if (playbackReturn.screen == "search") tmdbState.searchReturnFocus = playbackReturn.focus
                }
                focusId = "detail-favorite"
                restorePlaybackDetailFocus = !restoreGroupFocus && !restoreSeasonFocus
                screen = "detail"
            }
            "live-player" -> { category = "首页"; focusId = "nav-首页"; screen = "browse" }
            "detail", "play-intent" -> {
                bypassGroupFilmId = null
                if(returnScreen=="search") tmdbState.searchReturnFocus?.let{focusId=it;tmdbState.restoreSearchFocus=true}
                if (returnScreen == "browse") tmdbBrowseReturn?.let { origin ->
                    category = origin.category
                    focusId = origin.focus
                }
                tmdbBrowseReturn = null
                screen = returnScreen
            }
            "facets" -> {
                focusId = "facets"
                pendingToolbarFocus = "facets"
                screen = facetsReturnScreen
            }
            "search", "settings" -> {
                val target = if (screen == "settings") "settings" else "search"
                pendingToolbarFocus = target
                focusId = target
                screen = "browse"
            }
            "sites" -> { settingsTvBoxOnly = true; focusId = "settings"; screen = "settings" }
            else -> {
                if (SystemClock.uptimeMillis() - lastExitDismissedAt >= 300) exitConfirmation = true
            }
        }
    }
    val playerBackGate = remember(screen, film.id, episode, line) { PlaybackBackGate() }
    fun playerBack(downTime: Long? = null, closeTopLayer: () -> Unit = {}) {
        if (screen != "player") return
        playerBackGate.press(downTime, SystemClock.uptimeMillis(), closeTopLayer) {
            playbackPicker = null
            back()
        }
    }
    fun finishLiveSearchInput() {
        keyboard?.hide()
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
        pendingLiveSearchFocus = true
    }
    BackHandler(true) {
        when {
            splash -> splashExiting = true
            lanConfigOpen -> lanConfigOpen = false
            exitConfirmation -> { exitConfirmation = false; lastExitDismissedAt = SystemClock.uptimeMillis() }
            screen == "browse" && category == "直播" && liveSearchExpanded -> {
                if (liveSearchEditorFocused) finishLiveSearchInput()
                else { liveSearchExpanded = false; liveSearchQuery = "" }
            }
            screen == "search" && searchInputFocused -> {
                keyboard?.hide(); view.clearFocus(); searchInputFocused = false
                focusId = "search"
                uiScope.launch { withFrameNanos { }; runCatching { requester("search").requestFocus() } }
            }
            screen == "player" -> playerBack { playbackPicker = null }
            else -> back()
        }
    }
    LaunchedEffect(screen, splash, category) {
        CrashJournal.location(screen, category)
        RemoteTrace.event { "ROUTE screen=$screen category=$category focusId=$focusId" }
        if (!splash) {
            // TV navigation starts with an actual D-pad focus, even when the
            // platform last used touch mode before launching this window.
            inputModes.requestInputMode(InputMode.Keyboard)
            // Request only attached controls; returning to a rail first composes its remembered scroll position.
            withFrameNanos { }
            if (screen == "detail") {
                // Detail owns its lazy/season focus. The generic screen-first
                // request must not steal focus after playback has restored it.
                if (!restoreGroupFocus && !restoreSeasonFocus)
                    restorePlaybackDetailFocus = !detailFavoriteFocused
                return@LaunchedEffect
            }
            val id = when (screen) {
                "browse" -> (pendingToolbarFocus ?: focusId).let { if (it == "play") "details" else it }
                "search" -> focusId
                "facets" -> if (focusId.startsWith("facet-result-")) focusId else "screen-first"
                "settings" -> if (settingsTvBoxOnly) "phone-config-main" else "screen-first"
                else -> "screen-first"
            }
            // A lazy search result attaches after its parent. Its item effect restores focus below.
            if (screen == "search" && id.startsWith("card-") &&
                tmdbState.globalSearch.films.any {
                    "card-${displayKey(it)}" == id
                })
                return@LaunchedEffect
            runCatching { requester(id).requestFocus() }.onSuccess {
                pendingToolbarFocus = null
                StartupTiming.finish()
                RemoteTrace.event { "RESTORE target=$id completed" }
            }.onFailure {
                if (screen == "browse") runCatching { requester("nav-$category").requestFocus() }
            }
        }
    }
    LaunchedEffect(screen,tmdbState.restoreSearchFocus,tmdbState.globalSearch.films) {
        if(screen=="search" &&tmdbState.restoreSearchFocus) {
            val target=tmdbState.searchReturnFocus?:return@LaunchedEffect
            if(tmdbState.globalSearch.films.none{"card-${it.id}"==target})return@LaunchedEffect
            repeat(8) {
                withFrameNanos { }
                if(runCatching{requester(target).requestFocus()}.isSuccess) {
                    focusId=target;tmdbState.restoreSearchFocus=false
                    return@LaunchedEffect
                }
            }
        }
    }
    HerrinScreenCanvas {
        // Home and IO-backed artwork prepare under the full wordmark while it
        // breathes. Fade reveals an existing underlay instead of an empty frame.
        if ((!splash || startup.prepareHome) && screen != "player" && screen != "live-player" &&
            (!sourceConfigured || films.isNotEmpty())) {
            key(if (screen == "browse") category else screen) {
                HeroArtworkTransition(backdropFilm.takeIf { it.id == displayFilm.id } ?: displayFilm,
                    reduced = reduceMotion, modifier = Modifier.fillMaxSize())
            }
            // Cinematic image stays full-bleed. Scrims protect legibility without flattening the artwork.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0.0f to Color(0xEA05080D), 0.36f to Color(0xBD060A11),
                0.68f to Color(0x32060911), 1.0f to Color(0x10060911)
            )))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.0f to Color(0x7C05070B), 0.17f to Color.Transparent,
                0.54f to Color(0x2105070B), 1.0f to Color(0xF606080B)
            )))
        }
        // Standalone Compose method: keep Herrin() below JVM 64-KiB limit.
        @Composable fun renderFacetScreen() {
                val matches = showCatalog.collapse(FilmFacetFilters.results(films, facetSelection))
                val facetsScroll = rememberScrollState()
                val metadataMatched = films.count { it.metadataSource == "TMDB" }
                Column(Modifier.fillMaxSize().testTag("facet-screen")
                    .verticalScroll(facetsScroll)
                    .background(Color(0xE7080B10))
                    .padding(horizontal = 46.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        TvButton("‹ 返回", Modifier.testTag("screen-first")
                            .focusRequester(requester("screen-first")), reduceMotion,
                            onFocus = { focusId = "screen-first" }) { back() }
                        Text("智能资料筛选", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        TvButton("重置所有条件", Modifier.testTag("facet-reset"), reduceMotion,
                            selected = !facetSelection.isEmpty,
                            onFocus = { focusId = "facet-reset" }) {
                            facetActor = null; facetDirector = null; facetRegion = null
                            facetYear = null; facetCategory = null; facetRating = null
                        }
                    }
                    Text("评分（TMDB）、演员、导演、地区、首映年份及分类可组合筛选。影片播放仍直接使用原影视源。",
                        fontSize = 14.sp, color = Color(0xFFC4CDDB))
                    Text(when {
                        providerFilms == null && sourceConfigured -> "影视源目录加载中，暂无可筛选影片"
                        metadataToken.isBlank() -> "未配置 TMDB：可按源内年份/分类筛选；评分、演员、导演、地区需在高级设置中配置 Token"
                        metadataScanDone < metadataScanTotal -> "资料匹配中 $metadataScanDone/$metadataScanTotal；当前结果仅包含已取得的资料，尚非完整扫描"
                        metadataScanTotal < films.size -> "本轮已检查前 $metadataScanTotal/${films.size} 部；其余影片尚未刮削，主创/地区结果可能不完整"
                        else -> "本轮已检查 $metadataScanDone/${films.size} 部，成功匹配 $metadataMatched 部；未匹配作品不会被猜测归类"
                    }, Modifier.testTag("facet-coverage"), fontSize = 13.sp,
                        color = Color(0xFFE8CDA0))
                    FacetRail("分类", "category", FilmFacetFilters.categories(films), facetCategory,
                        reduceMotion, onFocus = { focusId = it }, onSelect = { facetCategory = it })
                    FacetRail("年份", "year", FilmFacetFilters.years(films), facetYear,
                        reduceMotion, onFocus = { focusId = it }, onSelect = { facetYear = it })
                    FacetRail("演员", "actor", FilmFacetFilters.actors(films), facetActor,
                        reduceMotion, onFocus = { focusId = it }, onSelect = { facetActor = it })
                    FacetRail("导演 / 主创", "director", FilmFacetFilters.directors(films), facetDirector,
                        reduceMotion, onFocus = { focusId = it }, onSelect = { facetDirector = it })
                    FacetRail("国家 / 地区", "region", FilmFacetFilters.regions(films), facetRegion,
                        reduceMotion, onFocus = { focusId = it }, onSelect = { facetRegion = it })
                    FacetRail("影片评分 · TMDB", "rating", FilmFacetFilters.ratingBands, facetRating,
                        reduceMotion,
                        downTarget = matches.firstOrNull()?.let { requester("facet-result-${displayKey(it)}") },
                        onFocus = { focusId = it }, onSelect = { facetRating = it })
                    Text("筛选结果 · ${matches.size} 部${if (metadataScanDone < metadataScanTotal && metadataToken.isNotBlank()) " · 更新中" else ""}",
                        Modifier.testTag("facet-result-count"), fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold)
                    if (matches.isEmpty()) {
                        Text("当前没有符合全部条件的影片。可点击某一行「全部」或重置条件。",
                            Modifier.testTag("facet-no-results"), color = Color(0xFFBEC8D7))
                    } else LazyRow(Modifier.testTag("facet-results"), state = facetResultState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(matches, key = { displayKey(it) }) { candidate ->
                            LaunchedEffect(candidate.id) {
                                if (focusId == "facet-result-${displayKey(candidate)}")
                                    runCatching { requester("facet-result-${displayKey(candidate)}").requestFocus() }
                            }
                            Poster(candidate, Modifier.testTag("facet-result-${displayKey(candidate)}")
                                .focusRequester(requester("facet-result-${displayKey(candidate)}")),
                                reduceMotion, onFocus = {
                                    filmId = candidate.id
                                    focusId = "facet-result-${displayKey(candidate)}"
                                }, onClick = { openMovie(candidate) })
                        }
                    }
                }
            
        }

        // Standalone Compose method: keep Herrin() below JVM 64-KiB limit.
        @Composable fun renderSitesScreen() {
                val selected = tvBoxSingle
                LazyColumn(Modifier.fillMaxSize().testTag("site-list"),
                    contentPadding = PaddingValues(horizontal = 44.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    item { TvButton("‹ 返回设置", Modifier.testTag("screen-first").focusRequester(requester("screen-first")), reduceMotion) { back() } }
                    item { Text("影视站点", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                    item { Text("已导入影视点列表。选择其中一个影视点后才加载其影片；部分来源需要额外组件才能打开。", color = Color(0xFFBDC8D6)) }
                    item { Text(ConsumerSourceStatus.display(tvBoxStatus), Modifier.testTag("source-site-status"), color = Color(0xFFDEE5EE)) }
                    item { Text("提示：部分影视点或网盘需要额外登录授权、或依赖来源专用交互页面。" +
                        "当前不保证支持所有来源的登录弹窗；仅显示授权入口不代表已登录。" +
                        "请先选无需登录的合法直连站点。", color = Color(0xFFC9B98D),
                        modifier = Modifier.testTag("source-plugin-limitations")) }

                    if (tvBoxWarehouses.isNotEmpty()) {
                        item { Text("仓库", fontSize = 20.sp) }
                        itemsIndexed(tvBoxWarehouses) { index, warehouse ->
                            TvButton("${warehouse.name} →", Modifier.testTag("site-warehouse-$index"), reduceMotion) {
                                settingsTvBoxOnly = true
                                tvBoxSelectedName = warehouse.name
                                tvBoxRequest = warehouse.url to false
                                tvBoxLoadEpoch++
                            }
                        }
                    }
                    itemsIndexed(selected?.sites.orEmpty()) { index, site ->
                        TvButton("${site.name}  ·  ${when {
                            site.apiUrl != null -> "CMS"
                            site.spiderApi != null -> "Spider"
                            site.scriptApi != null -> "JS 规则"
                            else -> "尚不兼容"
                        }}", Modifier.testTag("site-select-$index"), reduceMotion) {
                            activateTvBoxSite(site, selected)
                        }
                    }
                }
            
        }

        // Standalone Compose method: keep Herrin() below JVM 64-KiB limit.
        @Composable fun renderBrowseSearch() {
                val isSearch = screen == "search"
                val renderedCategory = category
                var myRecent by remember(catalogScope){mutableStateOf<List<Film>>(emptyList())}
                LaunchedEffect(isSearch,category,films,catalogScope,screen){
                    myRecent=if(!isSearch &&category=="我的")withContext(Dispatchers.Default){watchHistory.recent(catalogScope,films)} else emptyList()
                }
                val myPersonal = personalBrowseFilms
                var personalMatch by remember { mutableStateOf<Film?>(null) }
                personalMatch?.let { target -> PersonalMatchDialog(target) { personalMatch = null } }
                // Focus color animations can recompose every frame: searching,
                // regrouping and reading playback history must NOT run per frame.
                val filtered = remember(showCatalog, films, category, isSearch,
                    query, searchCategory, searchNewestFirst, favorites, searchFavoritesOnly,
                    facetSelection, tmdbState.tmdbTop250Films, tmdbState.globalSearch.films, screen, catalogScope,myRecent,myPersonal) {
                    if (isSearch) {
                        val queried = tmdbState.globalSearch.films.filter {
                            searchCategory == "全部" || it.category == searchCategory || searchCategory in it.genres
                        }.let { if (searchNewestFirst) it.sortedByDescending(Film::year) else it }
                        val favoritesOnly = if (searchFavoritesOnly) {
                            val liked = favorites
                            queried.filter { it.id in liked }
                        } else queried
                        FilmFacetFilters.results(favoritesOnly, facetSelection).distinctBy(Film::id)
                    }
                    else if (category == "Top 250")
                        // TMDB Top Rated identity and order are authoritative;
                        // metadata grouping is not allowed to hide its ranks.
                        tmdbState.tmdbTop250Films.map { tmdbState.tmdbResolved[it.id] ?: it }.distinctBy(Film::id)
                    else if (category == "我的") {
                        val recent = (recentIndexFilms.map { cached -> films.firstOrNull { it.id == cached.id } ?: cached } +
                            myRecent).distinctBy(Film::id)
                        val saved = (showCatalog.favorites(favorites) + tmdbState.tmdbResolved.values.filter { it.id in favorites }).distinctBy(Film::id)
                        showCatalog.collapse(HerrinMyLibrary.combine(recent + myPersonal, saved))
                    }
                    else showCatalog.collapse((catalogFilms + myPersonal.filter { it.metadataSource == "TMDB" }).filter {
                        category == "首页" || it.category == category ||
                            (category in setOf("科幻", "动作", "悬疑", "喜剧") &&
                                category in it.genres)
                    })
                }
                // History is recomputed on returning from playback or when the
                // source/catalog changes, not on every D-pad/animation frame.
                var watched by remember(catalogScope) { mutableStateOf<List<Film>>(emptyList()) }
                LaunchedEffect(isSearch, category, filtered, showCatalog, catalogScope, screen) {
                    watched = withContext(Dispatchers.Default) {
                        if (!isSearch && category == "首页") filtered.mapNotNull { shown ->
                            showCatalog.watchProgress(shown) { source, entry, option ->
                                watchHistory.load(catalogScope, source.id, entry.number, option.id)
                            }?.let { shown to it.updatedAtMs }
                        }.sortedByDescending { it.second }.map { it.first }
                        else emptyList()
                    }
                }
                val independentPage = TmdbBrowsePage.from(category).takeIf { tmdbPrimary && !isSearch }
                val railFilms = if (independentPage != null && independentPage != TmdbBrowsePage.HOME)
                    tmdbBrowseState.row(TmdbRailSpec(independentPage, TmdbRailKind.HOT)).entries.map { it.film }
                else if (!isSearch && category == "首页" && sourceConfigured) showCatalog.recentRail(recentIndexFilms, watched) else filtered
                val railState = railStates.getValue(if (isSearch) "search" else category)
                val recommendedFilms = if (independentPage == TmdbBrowsePage.HOME) tmdbBrowseState.row(TmdbRailSpec(independentPage, TmdbRailKind.HOT)).entries.map { it.film }
                    else if (!isSearch && category == "首页") { if (sourceConfigured) BrowseHeroSelection.candidates("首页", collapsedCatalog, tmdbState.tmdbTop250Films) else collapsedCatalog.asReversed() } else emptyList()
                val recommendationPrefix = if (railFilms.isEmpty()) "card-" else "recommend-"
                val recommendState = railStates.getValue("recommend")
                val browseScroll = browseScrollStates.getValue(if (isSearch) "search" else category)
                LaunchedEffect(screen, category) {
                    val restoreTarget = focusId
                    if (!isSearch && independentPage == null && moduleEntry == null &&
                        (restoreTarget.startsWith("card-") || restoreTarget.startsWith("recommend-"))) {
                        val inRecommendations = restoreTarget.startsWith("recommend-") || railFilms.isEmpty()
                        val entries = if (inRecommendations) recommendedFilms else railFilms
                        val prefix = if (inRecommendations) recommendationPrefix else "card-"
                        val index = entries.indexOfFirst { "$prefix${displayKey(it)}" == restoreTarget }
                        if (index >= 0) {
                            (if (inRecommendations) recommendState else railState).scrollToItem(index)
                            repeat(4) {
                                withFrameNanos { }
                                if (focusId != restoreTarget) return@LaunchedEffect
                                if (runCatching { requester(restoreTarget).requestFocus() }.isSuccess) return@LaunchedEffect
                            }
                        }
                    }
                }
                val suggestions = listOf("清空")
                val headerDownTarget = when {
                    isSearch -> "search-input"
                    category == "直播" -> if (liveChannels.isEmpty()) "live-config" else "live-group-all"
                    category == "我的" -> "my-manage-personal-sources"
                    category == "Top 250" -> if (railFilms.isEmpty()) "top250-retry" else
                        "card-${displayKey(railFilms.getOrNull(railState.firstVisibleItemIndex) ?: railFilms.first())}"
                    tmdbPrimary && catalogFilms.isEmpty() -> "tmdb-home-retry"
                    sourceConfigured && catalogFilms.isEmpty() -> null
                    films.isNotEmpty() -> "details"
                    else -> null
                }?.let { requester(it) }
                // Never observe LazyRow.layoutInfo during composition: each
                // layout pass invalidates it, leaving Compose perpetually busy.
                val visibleFirstIndex by remember(railState) {
                    derivedStateOf { railState.firstVisibleItemIndex }
                }
                // A TMDB cache row can have entries before the LazyRow has
                // attached its first poster. Never point the hero's DOWN edge
                // at a FocusRequester that has not been placed in the tree.
                val attachedRailKeys by remember(railState) {
                    derivedStateOf { railState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
                }
                val firstPosterTarget = if (independentPage != null)
                    TmdbBrowseFocus.first(tmdbBrowseState, independentPage,
                        if (category == "首页") railFilms else emptyList())
                        ?.takeIf(tmdbBrowseState::isPosterAttached)?.let { requester(it) }
                else railFilms.getOrNull(visibleFirstIndex)
                    ?.takeIf { displayKey(it) in attachedRailKeys }
                    ?.let { requester("card-${displayKey(it)}") }
                val sceneLift = with(LocalDensity.current) { 9.dp.toPx() }
                Column(Modifier.fillMaxSize().testTag("browse-scroll").graphicsLayer {
                    alpha = sceneReveal.value
                    translationY = (1f - sceneReveal.value) * sceneLift
                }.verticalScroll(browseScroll).padding(horizontal = 40.dp, vertical = 14.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 51.dp)) {
                        Row(Modifier.align(Alignment.CenterStart).testTag("header-brand"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.size(27.dp)
                                .border(1.dp, Color(0x6FE2EAF3), RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center) {
                                Text("H", fontSize = 16.sp, fontWeight = FontWeight.Light,
                                    color = Color(0xE7F3F5F8))
                            }
                            Text("HERRIN", letterSpacing = 2.5.sp,
                                fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                color = Color(0xE4F0F2F6))
                        }
                        Box(Modifier.align(Alignment.Center)) {
                            TvNavTabs(category, isSearch, focusId, reduceMotion, headerDownTarget,
                            { id -> requester(id) },
                            onFocus = { tmdbBrowseState.focus(null); focusId = it },
                            onSelect = { selected ->
                                if (selected != category || screen != "browse") {
                                    posterFocusJob[0]?.cancel()
                                    moduleEntry = selected
                                    heroSelections.remove(selected)
                                    focusId = "nav-$selected"
                                }
                                category = selected
                                val candidates = if (tmdbPrimary && TmdbBrowsePage.from(selected) != null) tmdbBrowseState.films(TmdbBrowsePage.from(selected)!!)
                                else if (selected == "Top 250") tmdbState.tmdbTop250Films else catalogFilms.filter {
                                    selected == "首页" || it.category == selected || selected in it.genres
                                }
                                (candidates.firstOrNull { it.id == heroSelections[selected] } ?: candidates.firstOrNull())?.let { filmId = it.id }
                                if (selected == "直播" && liveChannels.isNotEmpty()) {
                                    if (activeLiveChannel?.url !in liveChannels.map { it.url }) {
                                        activeLiveChannel = liveChannels.first()
                                    }
                                    screen = "live-player"
                                } else screen = "browse"
                            },
                            onSearch = { focusId = "search"; screen = "search" })
                        }
                        TvHeaderIconButton(gear = true,
                            modifier = Modifier.align(Alignment.CenterEnd)
                                .testTag("settings")
                                // Let the standalone gear sit directly on the hero:
                                // only D-pad focus should introduce a visible fill.
                                .then(if (headerDownTarget != null)
                                    Modifier.focusProperties { down = headerDownTarget }
                                    else Modifier)
                                .focusRequester(requester("settings")),
                            reduced = reduceMotion, onFocus = { focusId = "settings" }) {
                            settingsTvBoxOnly = true
                            screen = "settings"
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (isSearch) {
                        Text("搜索影片 · 类型与资料筛选", fontSize = 24.sp)
                        OutlinedTextField(query, { query = it }, Modifier.testTag("search-input")
                            .focusProperties { down = requester("search-filter-全部") }
                            .focusRequester(requester("search-input"))
                            .onFocusChanged { searchInputFocused = it.isFocused; RemoteTrace.event { "INPUT focus=${it.isFocused}" } }
                            .fillMaxWidth().padding(vertical = 8.dp),
                            label = { Text("输入片名 / 英文名（支持系统键盘）") }, singleLine = true,
                            readOnly = !searchInputFocused)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchDiscovery.categories.forEachIndexed { index, option ->
                                TvButton(option,
                                    modifier = Modifier.testTag("search-filter-$option")
                                        .focusProperties { down = requester("search-suggest-${suggestions[index.coerceAtMost(suggestions.lastIndex)]}") }
                                        .focusRequester(requester("search-filter-$option")),
                                    selected = searchCategory == option, reduced = reduceMotion, quiet = true,
                                    onFocus = { focusId = "search-filter-$option" }
                                ) { searchCategory = option }
                            }
                            TvButton(if (searchNewestFirst) "排序：最新优先" else "排序：目录顺序",
                                modifier = Modifier.testTag("search-sort")
                                    .focusProperties { down = requester("search-suggest-清空") }
                                    .focusRequester(requester("search-sort")),
                                selected = searchNewestFirst, reduced = reduceMotion, quiet = true,
                                onFocus = { focusId = "search-sort" }
                            ) { searchNewestFirst = !searchNewestFirst }
                            TvButton(if (searchFavoritesOnly) "★ 已收藏" else "☆ 我的收藏",
                                modifier = Modifier.testTag("search-favorites")
                                    .focusRequester(requester("search-favorites")),
                                reduced = reduceMotion, selected = searchFavoritesOnly,
                                quiet = true, onFocus = { focusId = "search-favorites" }) {
                                searchFavoritesOnly = !searchFavoritesOnly
                            }
                            TvButton(if (showSearchAdvancedFilters) "收起筛选" else
                                if (facetSelection.isEmpty) "更多筛选" else "更多筛选 · 已启用",
                                modifier = Modifier.testTag("search-advanced-toggle")
                                    .focusRequester(requester("search-advanced-toggle")),
                                reduced = reduceMotion, selected = showSearchAdvancedFilters,
                                quiet = true, onFocus = { focusId = "search-advanced-toggle" }) {
                                showSearchAdvancedFilters = !showSearchAdvancedFilters
                            }
                        }
                        Text(tmdbState.globalSearch.status, Modifier.testTag("tmdb-online-search-status"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvButton("重试", reduced = reduceMotion, quiet = true) { tmdbState.globalSearch.retry++ }
                            if (tmdbState.globalSearch.page < tmdbState.globalSearch.totalPages && tmdbState.globalSearch.lastLoadedPage == tmdbState.globalSearch.page && !tmdbState.globalSearch.loading)
                                TvButton("加载下一页", reduced = reduceMotion, quiet = true) { tmdbState.globalSearch.page++ }
                        }
                        Text("搜索结果 · ${filtered.size} 部 · ${searchCategory}" +
                            (if (searchFavoritesOnly) " · 我的收藏" else "") +
                            (if (!facetSelection.isEmpty) " · 高级条件" else ""),
                            modifier = Modifier.testTag("search-filter-status"),
                            fontSize = 12.sp, color = Color(0xFFC4CDDC))
                        if (showSearchAdvancedFilters) {
                            Column(Modifier.fillMaxWidth().testTag("search-advanced-filters"),
                                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("资料筛选 · 多条件组合", fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold, color = Color.White)
                                    TvButton("重置筛选", Modifier.testTag("search-facet-reset"),
                                        reduced = reduceMotion, quiet = true) {
                                        facetActor = null; facetDirector = null; facetRegion = null
                                        facetYear = null; facetCategory = null; facetRating = null
                                    }
                                }
                                FacetRail("分类", "category", FilmFacetFilters.categories(films),
                                    facetCategory, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetCategory = it })
                                FacetRail("年份", "year", FilmFacetFilters.years(films),
                                    facetYear, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetYear = it })
                                FacetRail("演员", "actor", FilmFacetFilters.actors(films),
                                    facetActor, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetActor = it })
                                FacetRail("导演", "director", FilmFacetFilters.directors(films),
                                    facetDirector, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetDirector = it })
                                FacetRail("国家 / 地区", "region", FilmFacetFilters.regions(films),
                                    facetRegion, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetRegion = it })
                                FacetRail("TMDB 评分", "rating", FilmFacetFilters.ratingBands,
                                    facetRating, reduceMotion, onFocus = { focusId = it },
                                    onSelect = { facetRating = it })
                            }
                        }
                        // An on-screen keyboard also permits search without a hardware keyboard or IME.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEachIndexed { index, word ->
                                TvButton(word, modifier = Modifier.testTag("search-suggest-$word")
                                    .focusProperties {
                                        up = requester(if (index == suggestions.lastIndex)
                                            "search-sort" else "search-filter-${SearchDiscovery.categories[index]}")
                                        if (firstPosterTarget != null) down = firstPosterTarget
                                    }.focusRequester(requester("search-suggest-$word")),
                                    reduced = reduceMotion, onFocus = { focusId = "search-suggest-$word" }) {
                                    query = if (word == "清空") "" else word
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
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
                    } else if (category == "Top 250") {
                        Spacer(Modifier.height(56.dp))
                        Text("TMDB 高分电影 Top 250", fontSize = 32.sp,
                            modifier = Modifier.testTag("top250-heading"))
                        if (categoryHero != null) {
                            Text(displayFilm.title, Modifier.testTag("hero-title-${displayFilm.id}"),
                                fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF5F7FA))
                            Text(listOfNotNull(displayFilm.year.takeIf { it.isNotBlank() }, displayFilm.category,
                                FilmMetadataEnrichment.ratingLabel(displayFilm)).joinToString(" · "),
                                fontSize = 13.sp, color = Color(0xFFE8CDA0))
                            Text(displayFilm.synopsis, Modifier.widthIn(max = 600.dp), maxLines = 2,
                                fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFCDD8E6))
                        }
                        Text(tmdbState.tmdbTop250Status.ifBlank {
                            if (railFilms.isNotEmpty()) "已读取 ${railFilms.size} 部高分电影"
                            else "正在等待 TMDB 高分榜数据…"
                        }, fontSize = 14.sp, lineHeight = 21.sp,
                            color = Color(0xFFD2DEEA),
                            modifier = Modifier.testTag("top250-network-status"))
                        if (railFilms.isEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("高分榜还没有加载结果；其他 TMDB 分类正常不代表此接口请求已成功。",
                                color = Color(0xFFC4CEDD), fontSize = 15.sp,
                                modifier = Modifier.testTag("top250-empty-title"))
                            Spacer(Modifier.height(12.dp))
                            TvButton("重新加载 Top250",
                                modifier = Modifier.testTag("top250-retry")
                                    .focusRequester(requester("top250-retry")),
                                reduced = reduceMotion) {
                                if (tmdbConnectivity == TmdbProbeState.CONNECTED)
                                    tmdbState.tmdbTop250RetryEpoch++
                                else tmdbProbeEpoch++
                            }
                            if (metadataToken.isBlank()) TvButton("配置 TMDB 令牌",
                                modifier = Modifier.testTag("top250-token-settings"),
                                reduced = reduceMotion) {
                                settingsTvBoxOnly = true
                                screen = "settings"
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                    } else if (category == "我的") {
                        Spacer(Modifier.height(56.dp))
                        Text("我的", fontSize = 34.sp,
                            modifier = Modifier.testTag("my-library-heading"))
                        Text("继续观看 · 我的收藏 · 当前影视资料库",
                            fontSize = 14.sp, color = Color(0xFFD2DEEA))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TvButton("管理个人媒体库",
                                modifier = Modifier.testTag("my-manage-personal-sources").focusRequester(requester("my-manage-personal-sources")),
                                reduced = reduceMotion) {
                                settingsModule = "个人媒体库"
                                settingsTvBoxOnly = true
                                screen = "settings"
                            }
                            Text("SMB / WebDAV 个人媒体库",
                                modifier = Modifier.testTag("my-personal-source-status"),
                                color = Color(0xFFAFC1D7), fontSize = 12.sp)
                        }
                        if (railFilms.isEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("暂无继续观看或收藏的影片。进入影片详情，添加收藏或播放后即可在这里查看。",
                                modifier = Modifier.testTag("my-library-empty"),
                                fontSize = 15.sp, color = Color(0xFFC4CEDD))
                        }
                        Spacer(Modifier.height(30.dp))
                    } else if (!tmdbPrimary && sourceConfigured && catalogFilms.isEmpty()) {
                        Spacer(Modifier.height(135.dp))
                        Text("影视源已添加", fontSize = 38.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("source-empty-title"))
                        val sourceName = activeCmsSite?.name ?: tvBoxSingle?.sites?.firstOrNull {
                            it.key == spiderBridge.approvedActiveSiteKey()
                        }?.name ?: "影视源"
                        Text(if (spiderActive || activeCmsSite != null) "$sourceName：暂无影片，可继续搜索或检查来源授权"
                            else "影视源已保存，请选择需要浏览的节点", fontSize = 15.sp, lineHeight = 23.sp,
                            color = Color(0xFFCDD8E6), modifier = Modifier.testTag("source-empty-status"))
                        Spacer(Modifier.height(24.dp))
                    } else {
                        Spacer(Modifier.height(132.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.width(13.dp).height(1.dp)
                                .background(Color(0xB8DCE5F0)))
                            Text(if (tmdbState.tmdbBrowseIndex.isNotEmpty()) "TMDB 精选"
                                else if (providerFilms == null) "演示片库" else "我的媒体",
                                fontSize = 11.sp, letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Medium, color = Color(0xC2DDE6F0))
                        }
                        Spacer(Modifier.height(11.dp))
                        if (tmdbPrimary && catalogFilms.isEmpty()) {
                            Text(tmdbState.tmdbDiscoveryStatus.ifBlank { tmdbProbeMessage }, color = Color(0xFFBFC3CA))
                            TvButton("重试 TMDB 首页", Modifier.testTag("tmdb-home-retry").focusRequester(requester("tmdb-home-retry"))) { tmdbProbeEpoch++ }
                        }
                        Text(displayFilm.title, modifier = Modifier.testTag("hero-title-${displayFilm.id}"), fontSize = 42.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp, color = Color(0xFFF5F7FA), lineHeight = 48.sp)
                        Text(displayFilm.subtitle, fontSize = 12.sp, letterSpacing = 2.3.sp,
                            color = Color(0xCADFE5EE))
                        Spacer(Modifier.height(12.dp))
                        Text("${displayFilm.year}   ·   ${displayFilm.category}",
                            fontSize = 12.sp, color = Color(0xD3E6EBF2))
                        if (displayFilm.ratingValue != null || displayFilm.genres.isNotEmpty())
                            Text(listOfNotNull(FilmMetadataEnrichment.ratingLabel(displayFilm),
                                displayFilm.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() })
                                .joinToString("   ·   "), fontSize = 13.sp,
                                color = Color(0xFFE8CDA0), modifier = Modifier.testTag("hero-metadata"))
                        Text(displayFilm.synopsis, Modifier.widthIn(max = 445.dp).padding(vertical = 7.dp),
                            fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2,
                            color = Color(0xCCDCE5EE))
                        // H-003: TMDB hero is presentation-only. A metadata hero
                        // must not offer direct play before source resolution.
                        // Keep the real details entry and preserve DOWN to the first
                        // attached poster; playback remains inside details/recent.
                        TvButton("详情", reduced = reduceMotion,
                            onFocus = { tmdbBrowseState.focus(null); focusId = "details" },
                            modifier = Modifier.testTag("details")
                                .then(if (firstPosterTarget != null)
                                    Modifier.focusProperties { down = firstPosterTarget } else Modifier)
                                .focusRequester(requester("details"))) { details(displayFilm) }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (independentPage != null) {
                        TmdbBrowseRails(tmdbBrowseState, independentPage,
                            if (independentPage == TmdbBrowsePage.HOME) railFilms else emptyList(), reduceMotion,
                            { id -> requester(id) },
                            onFocus = { item, tag -> filmId = item.id; heroSelections[category] = item.id; focusId = tag },
                            onOpen = { item, tag ->
                                val browseOrigin = PlaybackReturnContext("browse", category, tag, item.id)
                                val recent = independentPage == TmdbBrowsePage.HOME && tag == "card-${item.id}" && railFilms.any { it.id == item.id }
                                if (recent) playIntent(item) else openMovie(item)
                                tmdbBrowseReturn = browseOrigin
                                tmdbState.resumeSourceFilm = item.id.takeIf { recent }
                            }, restoreFocus = focusId)
                    } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "直播" -> "直播频道"; category == "我的" -> "继续观看与收藏 · ${filtered.size} 部"; category == "首页" -> if (sourceConfigured) "最近播放" else "演示片库"; else -> if (sourceConfigured) "${category} · ${filtered.size} 部" else "精选${category} · 演示片库" },
                            modifier = Modifier.testTag("recent-heading"),
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text(if (sourceConfigured) "" else if (!isSearch && category == "首页") "精选首页 · 演示片库" else "DISCOVER   /   HERRIN",
                            fontSize = 11.sp, letterSpacing = 1.5.sp,
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
                                TvButton("设置授权直播列表", modifier = Modifier.testTag("live-config")
                                    .focusRequester(requester("live-config")),
                                    reduced = reduceMotion, onFocus = { focusId = "live-config" }) {
                                    screen = "settings"
                                }
                                if (prefs.livePlaylistUrl.isNotBlank() || prefs.tvBoxLiveActive) {
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
                                    liveSearchQuery, liveFavoriteScope, liveFavoriteEpoch
                                ) {
                                    LiveChannelDiscovery.groupedVisible(liveChannels, resolvedGroup,
                                        showFavoriteChannelsOnly, query = liveSearchQuery) { channel ->
                                        liveFavorites.contains(liveFavoriteScope, channel.url)
                                    }
                                }
                                LaunchedEffect(liveSearchQuery, resolvedGroup,
                                    showFavoriteChannelsOnly, liveChannels) {
                                    // An old rail offset can hide a newly filtered first result.
                                    liveChannelRailState.scrollToItem(0)
                                }
                                Text("${if (liveSearchQuery.isBlank()) "显示" else "匹配"} ${visibleChannels.size} / ${LiveChannelDiscovery.choices(liveChannels).size} 个频道 · 同名频道合并线路",
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
                                    itemsIndexed(visibleChannels, key = { _, item -> item.first.url }) { index, choice ->
                                        val channel = choice.first
                                        val nowProgramme = currentGuide[channel.tvgId.ifBlank { channel.name }]?.now
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            TvButton(
                                                buildString {
                                                    if (channel.group.isNotBlank()) append("${channel.group} · ")
                                                    append(channel.name)
                                                    if (choice.options.size > 1) append("  ·  ${choice.options.size} 条线路")
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
                                                activeLiveIndex = liveChannels.indexOfFirst { it.url == channel.url }
                                                screen = "live-player"
                                            }
                                            if (choice.options.size > 1) TvButton("切换线路",
                                                modifier = Modifier.testTag("live-line-picker-$index")
                                                    .focusRequester(requester("live-line-picker-$index")),
                                                reduced = reduceMotion, quiet = true,
                                                onFocus = { focusId = "live-line-picker-$index" }) {
                                                pendingLiveLines = choice
                                            }
                                            TvButton(
                                                if (choice.options.any { liveFavorites.contains(liveFavoriteScope, it.url) })
                                                    "★ 已收藏" else "☆ 收藏",
                                                modifier = Modifier.testTag("live-favorite-$index")
                                                    .focusRequester(requester("live-favorite-$index")),
                                                reduced = reduceMotion, quiet = true,
                                                onFocus = { focusId = "live-favorite-$index" }
                                            ) {
                                                val saved = choice.options.filter {
                                                    liveFavorites.contains(liveFavoriteScope, it.url)
                                                }
                                                if (saved.isEmpty()) liveFavorites.toggle(liveFavoriteScope, channel.url)
                                                else saved.forEach { liveFavorites.toggle(liveFavoriteScope, it.url) }
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
                                val preview = visibleChannels.firstOrNull { choice ->
                                    choice.options.any { it.url == highlightedLiveUrl }
                                }?.first ?: visibleChannels.firstOrNull()?.first
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
                    else if (!isSearch && category == "首页" && railFilms.isEmpty()) Text("暂无最近播放", Modifier.padding(24.dp).testTag("recent-empty"))
                    else if (railFilms.isEmpty()) Text(if (isSearch) "没有找到匹配影片" else "暂无收藏，进入影片详情添加", Modifier.padding(24.dp))
                    else LazyRow(modifier = Modifier.testTag("catalog-film-rail"), state = railState, contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(railFilms, key = { _, item -> displayKey(item) }) { index, item ->
                            // Runs after this lazy item's focus target is attached, including Back from detail.
                            LaunchedEffect(item.id) {
                                if (isSearch && focusId == "card-${displayKey(item)}") {
                                    requester("card-${displayKey(item)}").requestFocus()
                                    RemoteTrace.event { "RESTORE search card=${displayKey(item)} attached" }
                                }
                            }
                            val homeRail = !isSearch && category == "首页" && recommendedFilms.isNotEmpty()
                            Poster(item, Modifier.testTag("card-${displayKey(item)}")
                                .focusProperties {
                                    up = requester(if (isSearch)
                                        "search-suggest-${suggestions[index.coerceAtMost(suggestions.lastIndex)]}"
                                        else TvFocusRoutes.posterUp(renderedCategory, index,
                                            heroActionsVisible = tmdbPrimary || !sourceConfigured || catalogFilms.isNotEmpty()))
                                    // DOWN is handled by transferPosterFocus, which first
                                    // scrolls and attaches the destination lazy item.
                                    down = if (homeRail) FocusRequester.Default else FocusRequester.Cancel
                                }
                                .onPreviewKeyEvent { event ->
                                    if (item.id.startsWith("personal-index:") && event.type == KeyEventType.KeyDown &&
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                                        personalMatch = item
                                        true
                                    } else if (homeRail && event.type == KeyEventType.KeyDown &&
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                        transferPosterFocus(railState, recommendState, index,
                                            recommendedFilms, "recommend-", browseScroll, true)
                                        true
                                    } else false
                                }
                                .focusRequester(requester("card-${displayKey(item)}")), reduceMotion,
                                onFocus = { filmId = item.id; heroSelections[category] = item.id; focusId = "card-${displayKey(item)}" }, onClick = {
                                    if (!isSearch && item.id.startsWith("personal-index:")) {
                                        val selected = PersonalMediaPlaybackEntry.from(item)
                                        if (selected == null) {
                                            personalOpenNotice = true
                                            HerrinPlaybackDiagnosticJournal.record("PERSONAL", "INDEX_ENTRY_INVALID")
                                        } else {
                                            // Commit only stable index coordinates synchronously.
                                            // SelectedIndexedPersonalPlayback owns the IO/auth path.
                                            DetailPlaybackMetrics.timeline.click()
                                            tmdbState.selectPersonal(selected)
                                            activeCmsSite = null; spiderActive = false
                                            filmId = selected.film.id
                                            episode = selected.episode; line = selected.line
                                            screen = "player"
                                            HerrinPlaybackDiagnosticJournal.record("LOOKUP", "PERSONAL_PLAY_INTENT")
                                        }
                                    } else {
                                        val recent = !isSearch && category in setOf("首页", "我的") && (recentIndexFilms + myRecent).any { it.id == item.id }
                                        if (recent) playIntent(item) else openMovie(item)
                                        tmdbState.resumeSourceFilm = item.id.takeIf { recent }
                                    }
                                },
                                progress = if (!isSearch && category == "首页") {
                                    remember(item.id, showCatalog, catalogScope, screen) {
                                        showCatalog.watchProgress(item) { source, part, option ->
                                            watchHistory.load(catalogScope, source.id, part.number, option.id)
                                        }?.fraction ?: watchHistory.indexedProgress(item.id)?.fraction
                                    }
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
                        LazyRow(state = recommendState, contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(recommendedFilms, key = { _, item -> displayKey(item) }) { index, item ->
                                Poster(item, Modifier.testTag("${recommendationPrefix}${displayKey(item)}")
                                    .focusProperties {
                                        up = railFilms.getOrNull(index.coerceAtMost(railFilms.lastIndex))?.let { requester("card-${displayKey(it)}") } ?: requester("details")
                                        down = FocusRequester.Cancel
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            if (railFilms.isEmpty()) {
                                                uiScope.launch {
                                                    browseScroll.scrollTo(0)
                                                    withFrameNanos { }
                                                    runCatching { requester("details").requestFocus() }
                                                }
                                            } else transferPosterFocus(recommendState, railState, index,
                                                railFilms, "card-", browseScroll, false)
                                            true
                                        } else false
                                    }
                                    .focusRequester(requester("${recommendationPrefix}${displayKey(item)}")), reduceMotion,
                                    onFocus = { filmId = item.id; heroSelections[category] = item.id; focusId = "${recommendationPrefix}${displayKey(item)}" },
                                    onClick = { openMovie(item) })
                            }
                        }
                    }
                    }
                }
                pendingLiveLines?.takeIf { category == "直播" }?.let { choice ->
                    AlertDialog(
                        onDismissRequest = { pendingLiveLines = null },
                        title = { Text("${choice.name} · 选择播放线路") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                choice.options.forEachIndexed { index, channel ->
                                    TvButton("线路 ${index + 1}${if (channel.url == activeLiveChannel?.url) " · 当前" else ""}",
                                        modifier = Modifier.testTag("live-line-$index"), reduced = reduceMotion) {
                                        activeLiveChannel = channel
                                        activeLiveIndex = liveChannels.indexOfFirst { it.url == channel.url }
                                        pendingLiveLines = null
                                        screen = "live-player"
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { pendingLiveLines = null }) { Text("取消") }
                        }
                    )
                }
            
        }

        if (homeVisible) when (screen) {
            "live-player" -> {
                val selected = activeLiveChannel
                if (selected != null) {
                    LiveTvSurface(
                        channels = liveChannels,
                        selected = selected,
                        onChannel = { channel ->
                            activeLiveChannel = channel
                            activeLiveIndex = liveChannels.indexOfFirst { it.url == channel.url }
                        },
                        favorites = liveFavorites,
                        favoriteScope = liveFavoriteScope,
                        onBack = { category = "首页"; focusId = "nav-首页"; screen = "browse" },
                        reduced = reduceMotion
                    )
                } else {
                    Text("当前未选中直播频道", Modifier.padding(50.dp))
                }
            }
            "play-intent" -> OneTapLoading { back() }
            "facets" -> renderFacetScreen()
            "sites" -> renderSitesScreen()
            "browse", "search" -> renderBrowseSearch()
            "detail", "player", "settings" -> {
                // An explicit user action can search other licensed providers
                // even if CMS/Spider parsing failed before Media3 was created.
                val tryOtherTmdbSource: (() -> Unit)? =
                    if (screen == "player" && film.id.startsWith("tmdb-") &&
                        tmdbState.tmdbFallbackIndex.selected(film.id) != null) ({
                        val target = film.id
                        if (tmdbState.discoveredSources[target]?.hits?.isNotEmpty() == true) {
                            tmdbState.tmdbSourceStatus = "当前播放失败，已保留各影视点及选集；请选择其他来源或线路"
                            screen = "detail"
                        } else if (tmdbState.tmdbFallbackIndex.failSelected(target)) {
                            tmdbState.tmdbPersonalPlayback.remove(target)
                            tmdbState.tmdbPlaybackRoutes.forget(target)
                            tmdbState.tmdbResolved[target]?.let { old ->
                                tmdbState.tmdbResolved[target] = old.copy(
                                    playbackEpisodes = emptyList(),
                                    cmsVodId = null, spiderVodId = null)
                            }
                            activeCmsSite = null
                            spiderActive = false
                            tmdbState.tmdbSourceStatus = "当前来源播放失败，正在查找其他已授权片源…"
                            tmdbState.tmdbLookupEpoch++
                            screen = "detail"
                        }
                    }) else null
                val recoveryAttempt = HerrinAutomaticPlayback.Attempt(film.id, episode, line, playerRetryEpoch,
                    tmdbState.tmdbFallbackIndex.selected(film.id) ?: catalogScope)
                val recoveryConfigEpoch = personalSourceConfigEpoch
                val recoveryTvBox = tvBoxSingle
                val personalRoute = tmdbState.tmdbPlaybackRoutes.forFilm(film.id) is HerrinTmdbPlaybackRouteStore.Route.Personal ||
                    film.id.startsWith("personal-") || film.id.startsWith("personal-index:")
                val recoverPlayback: (FongMiPlaybackRecovery.Failure, Long) -> Boolean = recover@{ failure, position ->
                    // A private file is never recovered through a warehouse/Spider.
                    if (personalRoute) return@recover false
                    if (screen != "player" || tmdbState.automaticSearch != null || filmId != recoveryAttempt.film ||
                        episode != recoveryAttempt.episode || line != recoveryAttempt.line ||
                        playerRetryEpoch != recoveryAttempt.retry || personalSourceConfigEpoch != recoveryConfigEpoch ||
                        tvBoxSingle != recoveryTvBox ||
                        (tmdbState.tmdbFallbackIndex.selected(film.id) ?: catalogScope) != recoveryAttempt.source) return@recover false
                    val selectedSource = tmdbState.tmdbFallbackIndex.selected(film.id) ?: catalogScope
                    val raw = tmdbState.tmdbPlaybackRoutes.sourceFilm(film.id) ?: film
                    if (spiderActive && !spiderBridge.isApprovedSourceFilm(raw)) return@recover false
                    val coordinate = HerrinPlaybackCoordinate.from(film, episode, line) ?: return@recover false
                    when (val next = tmdbState.automaticPlayback.next(
                        HerrinAutomaticPlayback.Key(catalogScope, film.id, coordinate), selectedSource,
                        film, episode, line, tryOtherTmdbSource != null, failure)) {
                        is HerrinAutomaticPlayback.Next.Line -> {
                            tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(film.id, episode, next.id, position)
                            line = next.id
                            HerrinPlaybackDiagnosticJournal.record("RECOVERY", "NEXT_AUTHORIZED_LINE")
                            true
                        }
                        HerrinAutomaticPlayback.Next.Source -> {
                            val known = tmdbState.discoveredSources[film.id]
                            if (known?.hits?.isNotEmpty() == true) {
                                tmdbState.loadedDiscoveryHits[film.id]?.let { tmdbState.rejectedDiscoveryHits += it }
                                val nextHit = known.hits.firstOrNull { it.relevance == SourceRelevance.CONFIRMED &&
                                    it.key !in tmdbState.rejectedDiscoveryHits }
                                if (nextHit == null) { screen = "detail"; return@recover true }
                                tmdbState.discoveryRecovery = HerrinAutomaticPlayback.Search(film.id, coordinate, position)
                                tmdbState.tmdbResolved[film.id]?.let { tmdbState.tmdbResolved[film.id] = it.copy(playbackEpisodes = emptyList()) }
                                tmdbState.requestedDiscoveryHits[film.id] = nextHit.key
                                tmdbState.discoverySelection = film.id to nextHit.key
                                tmdbState.discoverySelectionEpoch++
                                pendingHistoryFilm = film.id
                                screen = "play-intent"
                                return@recover true
                            }
                            if (!tmdbState.tmdbFallbackIndex.failSelected(film.id)) return@recover false
                            tmdbState.automaticSearch = HerrinAutomaticPlayback.Search(film.id, coordinate, position)
                            tmdbState.tmdbPersonalPlayback.remove(film.id)
                            tmdbState.tmdbPlaybackRoutes.forget(film.id)
                            tmdbState.tmdbResolved[film.id]?.let { old -> tmdbState.tmdbResolved[film.id] = old.copy(
                                playbackEpisodes = emptyList(), cmsVodId = null, spiderVodId = null) }
                            activeCmsSite = null
                            spiderActive = false
                            tmdbState.tmdbLookupEpoch++
                            HerrinPlaybackDiagnosticJournal.record("RECOVERY", "NEXT_AUTHORIZED_SOURCE")
                            true
                        }
                        null -> false
                    }
                }
                val cmsWaiting = screen == "player" && cmsPlaybackPendingId == film.id
                val cmsFailed = screen == "player" && cmsPlaybackFailedId == film.id
                val cmsLine = if (!personalRoute && screen == "player" && !playbackNeedsRefresh &&activeCmsSite != null &&
                    film.cmsVodId != null && !spiderActive)
                    film.playbackEpisodes.firstOrNull { it.number == episode }
                        ?.lines?.firstOrNull { it.id == line } else null
                val cmsPlaybackConfig = (tmdbState.tmdbPlaybackRoutes.forFilm(film.id) as? HerrinTmdbPlaybackRouteStore.Route.Cms)?.config ?: tvBoxSingle
                val cmsHttpSpider = cmsLine != null && activeCmsSite?.apiType == 4
                val cmsJsonParser = cmsLine?.takeUnless { cmsHttpSpider }?.let { selected ->
                    TvBoxJsonPlayback.choose(selected.uri, selected.title,
                        cmsPlaybackConfig?.jsonParsers.orEmpty())
                }
                val cmsBrowserParser = if (cmsJsonParser == null && !cmsHttpSpider) cmsLine?.let { selected ->
                    FongMiBrowserPlayback.choose(selected.uri, selected.title,
                        cmsPlaybackConfig?.playbackParsers.orEmpty(), tvBoxAllowHttp)
                } else null
                // Only playback URL resolution changes. Existing UI, TMDB,
                // film metadata and details remain completely untouched.
                val parsedCms = if(cmsHttpSpider)
                    HttpSpiderPlayback(requireNotNull(activeCmsSite),film,episode,line,cmsPlaybackConfig,tvBoxAllowHttp,playerRetryEpoch)
                else if (cmsLine != null && cmsJsonParser != null)
                    CmsJsonPlayback(cmsLine.uri, cmsJsonParser, tvBoxAllowHttp, playerRetryEpoch)
                else if (cmsLine != null && cmsBrowserParser != null)
                    CmsBrowserPlayback(cmsLine.uri, cmsBrowserParser, tvBoxAllowHttp, playerRetryEpoch)
                else null
                val cmsParseWaiting = (cmsHttpSpider || cmsJsonParser != null || cmsBrowserParser != null) && parsedCms == null
                val currentSpiderResult = spiderResolved.takeIf { !personalRoute && spiderResolvedFor == recoveryAttempt }
                val personalMedia = if (screen == "player" && film.playbackEpisodes.any { ep ->
                    ep.number == episode && ep.lines.any { it.id == line } })
                    tmdbState.tmdbPersonalPlayback[film.id]?.get(line) else null
                val indexedPersonalId = if (screen == "player" && personalRoute && film.id.startsWith("personal-"))
                    line.takeIf { it.startsWith("personal-file:") }?.removePrefix("personal-file:")
                        ?: film.id.removePrefix("personal-") else null
                val personalPlayback = if (indexedPersonalId != null)
                    SelectedIndexedPersonalPlayback(indexedPersonalId, playerRetryEpoch, smbSessions)
                    else personalMedia?.let { SelectedPersonalPlayback(it,playerRetryEpoch) }
                val personalWaiting = (personalMedia != null || indexedPersonalId != null) && personalPlayback == null
                val licensed = if (screen != "player") null
                    else if (personalRoute || personalMedia != null) personalPlayback ?:
                    if (!personalWaiting) PlaybackResult.Unavailable("PERSONAL_SELECTION_EXPIRED") else null
                    else if (spiderActive && screen == "player") currentSpiderResult
                    else if (screen == "player" && film.playbackEpisodes.isNotEmpty()) {
                        if (activeCmsSite != null && film.cmsVodId != null) {
                            if (cmsHttpSpider || cmsJsonParser != null || cmsBrowserParser != null) parsedCms
                            else TvBoxCmsPlaybackResolver.resolve(film, episode, line, tvBoxAllowHttp,
                                activeCmsSite?.headers.orEmpty())
                        } else LicensedPlaybackResolver.resolve(film, episode, line)
                    } else null
                val sourceFailure = licensed as? PlaybackResult.Unavailable
                LaunchedEffect(screen, recoveryAttempt, sourceFailure?.reason, tmdbState.automaticSearch) {
                    if (screen == "player" && tmdbState.automaticSearch == null && sourceFailure != null) {
                        val position = tmdbState.automaticResume?.takeIf { it.film == film.id &&
                            it.episode == episode && it.line == line }?.position
                            ?: watchHistory.load(catalogScope, film.id, episode, line)?.positionMs ?: 0L
                        recoverPlayback(HerrinAutomaticPlayback.resolutionFailure(sourceFailure.reason), position)
                    }
                }
                val licensedMedia = (licensed as? PlaybackResult.Ready)?.media
                // The independent testing URL must not start playing while a
                // selected CMS film is still resolving its real licensed source.
                val testMedia = if (screen == "player" && !spiderActive &&
                    !film.id.startsWith("tmdb-") &&
                    activeCmsSite == null && !personalRoute) AuthorizedUrls.https(prefs.testMediaUrl) else null
                // A retained async/private Ready result is not a navigation
                // command. Leaving player must dispose the sole session now.
                val chosenMedia = if (screen != "player" || tmdbState.automaticSearch != null ||playbackNeedsRefresh) null
                    else licensedMedia ?: testMedia?.let { PlaybackMedia(it) }
                if (chosenMedia != null) {
                    val historyLine = if (licensedMedia != null) line else "independent-test"
                    // Snapshot the selected episode. A disposed player's final callback
                    // must not read the mutable episode after the user chooses the next one.
                    val historyEpisode = episode
                    val historyScope = catalogScope
                    val previous = remember(historyScope, film.id, historyEpisode, historyLine, playerRetryEpoch) {
                        watchHistory.load(historyScope, film.id, historyEpisode, historyLine)
                    }
                    fun changeEpisode(delta: Int) {
                        if (screen != "player" || episode != historyEpisode || line != historyLine) return
                        val next = SeasonPlayback.adjacent(film, historyEpisode, historyLine, delta) ?: return
                        tmdbState.automaticPlayback.clear()
                        tmdbState.automaticResume = null
                        episode = next.episode
                        line = next.line
                    }
                    // Re-resolving playerContent must reconstruct the player even
                    // when a provider returns the same URI and headers again.
                    key(catalogScope, film.id, historyEpisode, historyLine, playerRetryEpoch) {
                    val historyTicket = remember(chosenMedia) { watchHistory.begin(historyScope, film.id, historyEpisode, historyLine) }
                    AuthorizedPlaybackPanel(
                        media = chosenMedia.copy(diagnosticSessionId = playbackDiagnosticId, displayTitle = film.title),
                        onNextEpisode = if (SeasonPlayback.adjacent(film, historyEpisode, historyLine, 1) != null) ({ changeEpisode(1) }) else null,
                        onPreviousEpisode = if (SeasonPlayback.adjacent(film, historyEpisode, historyLine, -1) != null) ({ changeEpisode(-1) }) else null,
                        onPlayer = {
                            if (tmdbState.automaticResume?.let { it.film == film.id &&
                                it.episode == historyEpisode && it.line == historyLine } == true)
                                tmdbState.automaticResume = null
                        },
                        recoverySession = playbackRecovery,
                        startPositionMs = tmdbState.automaticResume?.takeIf { it.film == film.id &&
                            it.episode == historyEpisode && it.line == historyLine }?.position
                            ?: previous?.positionMs ?: 0L,
                        externalPanelOpen = playbackPicker != null,
                        onLineSelection = if (film.playbackEpisodes.isNotEmpty()) ({ playbackPicker = "lines" }) else null,
                        onEpisodeSelection = { playbackPicker = if (film.category == "电影") "lines" else "episodes" },
                        onRefreshSource = if (!personalRoute && (spiderActive || cmsJsonParser != null || cmsBrowserParser != null)) ({ playerRetryEpoch++ }) else null,
                        onTryOtherSource = tryOtherTmdbSource.takeUnless { personalRoute },
                        onRetryMedia = if(personalRoute) ({ playerRetryEpoch++ }) else null,
                        onSourceExhausted = recoverPlayback,
                        onMediaSpecDetected = { verifiedStreams[streamEvidenceKey] = it },
                        onPlaybackProgress = { position, duration, finished ->
                            if (position >= 3_000L && duration > 0) uiScope.launch(Dispatchers.IO) {
                                watchHistory.recordFilm(historyTicket, historyScope, film, historyEpisode, historyLine, position, duration)
                                withContext(Dispatchers.Main) { if (screen != "player") recentIndexEpoch++ }
                            }
                            watchHistory.updateSession(historyTicket, historyScope, film.id, historyEpisode, historyLine,
                                position, duration, finished)
                        },
                        onBack = { if (screen == "player") back() },
                        onRemoteBack = { key, close -> playerBack(key, close) }
                    )
                    }
                    if (playbackPicker != null) PlaybackSelectionDialog(
                        film, episode, line, playbackPicker == "lines", reduced = reduceMotion,
                        onSelect = { selectedEpisode, selectedLine ->
                            if (selectedEpisode == episode && selectedLine != line) {
                                watchHistory.load(catalogScope, film.id, episode, line)?.let { saved ->
                                    watchHistory.update(catalogScope, film.id, episode, selectedLine,
                                        saved.positionMs, saved.durationMs)
                                }
                            }
                            tmdbState.automaticPlayback.clear()
                            tmdbState.automaticResume = null
                            tmdbState.tmdbFallbackIndex.clearRejected(film.id)
                            episode = selectedEpisode
                            line = selectedLine
                            playbackPicker = null
                        }, onDismiss = { playbackPicker = null },
                        onBack = { key -> playerBack(key) { playbackPicker = null } })
                } else if (screen == "player") {
                    val playbackUnavailable = tmdbState.automaticSearch == null &&
                        (currentSpiderResult is PlaybackResult.Unavailable ||
                        (!spiderActive && !cmsWaiting && !cmsParseWaiting && !personalWaiting))
                    val offlineDemonstration = !sourceConfigured && !spiderActive &&
                        film.playbackEpisodes.isEmpty()
                    OneTapFailureFallback(tmdbState.directPlayFilm == film.id && playbackUnavailable,
                        "$filmId|$episode|$line|$playerRetryEpoch") {
                        val reason = (parsedCms as? PlaybackResult.Unavailable)?.reason
                            ?: (currentSpiderResult as? PlaybackResult.Unavailable)?.reason
                            ?: (licensed as? PlaybackResult.Unavailable)?.reason.orEmpty()
                        if (!recoverPlayback(HerrinAutomaticPlayback.resolutionFailure(reason),
                                tmdbState.automaticResume?.position ?: watchHistory.load(catalogScope, film.id, episode, line)?.positionMs ?: 0L)) screen = "detail"
                    }
                    PlayerStartupSurface(
                        loading = !playbackUnavailable,
                        offlineDemonstration = offlineDemonstration,
                        cmsFailed = cmsFailed,
                        retryAvailable = personalRoute || spiderActive || cmsFailed || cmsHttpSpider || personalMedia != null || cmsJsonParser != null || cmsBrowserParser != null,
                        reducedMotion = reduceMotion,
                        personal = personalRoute,
                        failureHint = if (personalRoute) (licensed as? PlaybackResult.Unavailable)?.reason?.let(PlayerStartupHints::forPersonalFailure)
                            else (parsedCms as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure)
                            ?: (currentSpiderResult as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure)
                            ?: (licensed as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure),
                        // Before Media3 starts, the parser/CMS/Spider error must
                        // be visible rather than presenting a fake decoder failure.
                        failureDiagnostic = when {
                            personalRoute -> (licensed as? PlaybackResult.Unavailable)?.let { PlaybackErrorReport.source("PERSONAL", it.reason) }
                            parsedCms is PlaybackResult.Unavailable ->
                                PlaybackErrorReport.source(
                                    if (cmsJsonParser != null) "JSON" else "BROWSER",
                                    parsedCms.reason)
                            currentSpiderResult is PlaybackResult.Unavailable ->
                                PlaybackErrorReport.source("SPIDER",
                                    currentSpiderResult.reason)
                            licensed is PlaybackResult.Unavailable ->
                                PlaybackErrorReport.source("CMS", licensed.reason)
                            cmsFailed -> PlaybackErrorReport.source("CMS", "CMS 详情读取失败")
                            playbackUnavailable -> PlaybackErrorReport.source("GENERAL", "播放解析未返回结果")
                            else -> null
                        },
                        onRetry = {
                            if (cmsFailed) {
                                cmsPlaybackFailedId = null
                                cmsPlaybackPendingId = film.id
                            }
                            playerRetryEpoch++
                        },
                        onTryOtherSource = tryOtherTmdbSource.takeUnless { personalRoute },
                        onBack = { playerBack() },
                        onRemoteBack = { key -> playerBack(key) }
                    )
                } else {
                    // Isolate these large DSL blocks into independent methods.
                    // MainActivityKt.Herrin otherwise violates the JVM 64-KiB
                    // per-method bytecode limit when TVBox and player UI grow.
                    fun androidx.compose.foundation.lazy.LazyListScope.renderSettingsItems() {
                        item {
                            if (settingsTvBoxOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (settingsNotice.isNotBlank()) Text(settingsNotice, fontSize = 14.sp)
                            } else Text("高级设置", fontSize = 32.sp)
                        }
                        if (!settingsTvBoxOnly) {
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
                                prefs.tvBoxLiveActive = false
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
                        }
                        if (!settingsTvBoxOnly || settingsModule == "影视源") {
                        item { Text(if (settingsTvBoxOnly) "影视源" else "TVBox 多仓 / 单仓 / IPTV", fontSize = 22.sp) }
                        if (!settingsTvBoxOnly) {
                            item { Text("支持 urls 多仓、sites/lives 单仓、内嵌直播、M3U 与 TVBox TXT。Java csp_* Spider 可在本次明确授权后尝试加载与解析；JS/Python、网页二次解析及部分受保护插件仍可能不兼容。失效的配置接口可从本机已缓存的有效 JSON 回退。", color = Color(0xFFBDC8D6), lineHeight = 23.sp) }
                        }
                        item {
                            if (settingsTvBoxOnly) TvSourceInput(
                                tvBoxUrlDraft, { tvBoxUrlDraft = it },
                                "影视源地址",
                                fieldTag = "tvbox-warehouse-url", navTag = "tvbox-warehouse-nav",
                                navRequester = requester("tvbox-warehouse-url"),
                                editRequester = requester("tvbox-warehouse-editor"),
                                onNavFocus = { focusId = "tvbox-warehouse-url"; configInputFocused = false },
                                onEditingFocus = { configInputFocused = it; if (it) focusId = "tvbox-warehouse-editor" },
                                onMoveUp = { moveCompactSettingsFocus("phone-config-main") },
                                onMoveDown = { moveCompactSettingsFocus("tvbox-warehouse-load") }
                            ) else OutlinedTextField(tvBoxUrlDraft, { tvBoxUrlDraft = it },
                                Modifier.fillMaxWidth().testTag("tvbox-warehouse-url")
                                    .onFocusChanged { configInputFocused = it.isFocused },
                                label = { Text("影视源 · TVBox 单仓 / 多仓 HTTP(S) 地址") }, singleLine = true)
                        }
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TvButton(if (settingsTvBoxOnly) "应用影视源" else "读取配置",
                                modifier = Modifier.testTag("tvbox-warehouse-load")
                                    .focusRequester(requester("tvbox-warehouse-load")),
                                reduced = reduceMotion, onFocus = {
                                    focusId = "tvbox-warehouse-load"
                                    lastMovieSettingsAction = "tvbox-warehouse-load"
                                    keyboard?.hide()
                                }) {
                                tvBoxSelectedName = ""
                                // H-007: explicit reapply must retire an old/hung Spider
                                // session BEFORE loading the warehouse. Even if the
                                // HTTP refresh fails, no dead plugin IPC is reused.
                                // close() does not delete persisted warehouse or trust.
                                spiderBridge.close()
                                HerrinPlaybackDiagnosticJournal.record("CONFIG", "SOURCE_REAPPLY_RESET")
                                tvBoxRunSpiderAfterApply = true
                                vodImportFeedback = "读取中…"
                                phoneSourceId?.let { phoneSourceImport.update(it, "SUPERSEDED") }
                                phoneSourceId = null
                                tvBoxRequest = tvBoxUrlDraft.trim() to true
                                tvBoxLoadEpoch++
                            }
                            if (settingsTvBoxOnly) {
                                Spacer(Modifier.width(12.dp))
                                Box {
                                    val activeNode = tvBoxSingle?.sites?.firstOrNull {
                                        it.key == activeCmsSite?.key || it.key == spiderBridge.approvedActiveSiteKey() ||
                                            (it.key == autoCmsRequest?.key && it.apiUrl == autoCmsRequest?.apiUrl)
                                    } ?: tvBoxSingle?.let { config -> config.sites.firstOrNull {
                                        prefs.tvBoxPreferredRootId == SourceHealthIndex.digest(configuredTvBoxUrl) &&
                                            TvBoxAutoSites.identity(it, config.spiderJar) == prefs.tvBoxPreferredSiteId
                                    } }
                                    TvButton((activeNode?.name ?: "影视节点") + " ▾",
                                        modifier = Modifier.testTag("settings-source-sites")
                                            .focusRequester(requester("settings-source-sites")),
                                        reduced = reduceMotion, onFocus = {
                                            focusId = "settings-source-sites"
                                            lastMovieSettingsAction = "settings-source-sites"
                                            keyboard?.hide()
                                        }) { siteMenuOpen = true }
                                    DropdownMenu(expanded = siteMenuOpen, onDismissRequest = { siteMenuOpen = false }) {
                                        Text("部分来源需要登录授权；可先选择无需登录的来源。",
                                            Modifier.padding(12.dp).testTag("source-plugin-limitations"))
                                        tvBoxWarehouses.forEach { warehouse ->
                                            DropdownMenuItem(text = { Text(warehouse.name) }, onClick = {
                                                siteMenuOpen = false
                                                tvBoxSelectedName = warehouse.name
                                                tvBoxRunSpiderAfterApply = true
                                                tvBoxRequest = warehouse.url to false
                                                tvBoxLoadEpoch++
                                            })
                                        }
                                        tvBoxSingle?.sites.orEmpty().filter {
                                            it.apiUrl != null || it.spiderApi != null || it.scriptApi != null
                                        }.forEachIndexed { index, site ->
                                            DropdownMenuItem(modifier = Modifier.testTag("site-select-$index"), text = { Text(site.name) }, onClick = {
                                                siteMenuOpen = false
                                                activateTvBoxSite(site, tvBoxSingle)
                                            })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        } }
                        if (!settingsTvBoxOnly) {
                        item { OutlinedTextField(tvBoxJsonDraft, { tvBoxJsonDraft = it },
                            Modifier.fillMaxWidth().testTag("tvbox-import-json")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("可选：从旧 TVBox 复制完整单仓／多仓 JSON") },
                            minLines = 2, maxLines = 5) }
                        item { TvButton("导入并缓存 TVBox JSON", modifier = Modifier.testTag("tvbox-import-json-save"),
                            reduced = reduceMotion, onFocus = { keyboard?.hide() }) {
                            tvBoxSelectedName = ""
                            spiderBridge.close()
                            HerrinPlaybackDiagnosticJournal.record("CONFIG", "JSON_IMPORT_RESET")
                            phoneSourceId?.let { phoneSourceImport.update(it, "SUPERSEDED") }
                            phoneSourceId = null
                            tvBoxRequest = tvBoxUrlDraft.trim() to true
                            tvBoxImportJsonRequest = tvBoxJsonDraft
                            tvBoxLoadEpoch++
                        } }
                        }
                        }
                        if (settingsTvBoxOnly && settingsModule == "海报与资料") {
                            item { Text("海报与资料",
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                            item { Spacer(Modifier.height(0.dp)) }
                            item { TmdbTokenEntryFields(
                                compact = true, reducedMotion = reduceMotion,
                                splitMode = tmdbTokenSplitMode, onToggle = { toggleTmdbSplitInput() },
                                single = tmdbTokenDraft, onSingle = { tmdbTokenDraft = it },
                                first = tmdbTokenPart1, onFirst = { tmdbTokenPart1 = it },
                                second = tmdbTokenPart2, onSecond = { tmdbTokenPart2 = it },
                                third = tmdbTokenPart3, onThird = { tmdbTokenPart3 = it },
                                remoteNavigation = CompactTmdbRemoteNavigation(
                                    requester = { requester(it) },
                                    onNavFocus = { focusId = it; configInputFocused = false },
                                    onEditingFocus = { configInputFocused = it },
                                    onMoveUp = { id ->
                                        TvFocusRoutes.compactSettingsNeighbor(
                                            id, false, lastMovieSettingsAction, tmdbTokenSplitMode)
                                            ?.let(::moveCompactSettingsFocus)
                                    },
                                    onMoveDown = { id ->
                                        TvFocusRoutes.compactSettingsNeighbor(
                                            id, true, lastMovieSettingsAction, tmdbTokenSplitMode)
                                            ?.let(::moveCompactSettingsFocus)
                                    },
                                    onToggleFocus = { focusId = "compact-tmdb-split-toggle" }
                                )
                            ) }
                            item {
                                TvButton("刮削",
                                    modifier = Modifier.testTag("compact-tmdb-save")
                                        .focusRequester(requester("compact-tmdb-save")),
                                    reduced = reduceMotion,
                                    onFocus = { focusId = "compact-tmdb-save"; keyboard?.hide() }) {
                                    val candidate = candidateTmdbToken()
                                    val activeToken = candidate ?: metadataToken.takeIf { it.isNotBlank() }
                                    if (activeToken == null) settingsResult(false)
                                    else uiScope.launch {
                                        val saved = withContext(Dispatchers.IO) {
                                            runCatching { prefs.tmdbReadToken = activeToken }.isSuccess
                                        }
                                        if (!saved) { settingsResult(false); return@launch }
                                        metadataResolver.clearAuthCooldown(activeToken)
                                        metadataForceIds = providerFilms.orEmpty().map { it.id }.toSet()
                                        providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                        metadataToken = activeToken
                                        clearTmdbInput()
                                        metadataRescanEpoch++
                                        uiScope.launch {
                                            withContext(Dispatchers.IO) { tmdbDiscovery.invalidate(metadataToken) }
                                            tmdbProbeEpoch++
                                        }
                                        settingsResult(true)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(0.dp)) }
                        }
                        if (settingsTvBoxOnly && settingsModule == "个人媒体库") {
                            item { PersonalProtocolTabs(personalProtocol, reduceMotion, requester("personal-name")) { personalProtocol = it } }
                            item { PersonalLibrarySettings(personalProtocol, nameFocus = requester("personal-name"), onChanged = { personalSourceConfigEpoch++ }, onPlay = { mediaId ->
                                uiScope.launch {
                                    val result = withContext(Dispatchers.IO) { PersonalLibrary(context).play(mediaId, smbSessions) }
                                    if(result is PlaybackResult.Ready) {
                                        val target = "personal-$mediaId"
                                        val entry = Film(target, result.media.displayTitle, "", "个人媒体", "", "", R.drawable.photofilm_orbit,
                                            playbackEpisodes = listOf(PlaybackEpisode(1, "正片", listOf(PlaybackLine("personal-file", "个人媒体", result.media.uri)))))
                                        tmdbState.tmdbResolved[target] = entry
                                        tmdbState.tmdbPersonalPlayback[target] = mapOf("personal-file" to result.media)
                                        tmdbState.tmdbPlaybackRoutes.personal(target)
                                        activeCmsSite = null; spiderActive = false
                                        filmId = target; episode = 1; line = "personal-file"; screen = "player"
                                    }
                                }
                            }) }
                        }
                        if (!settingsTvBoxOnly || settingsModule == "直播源") {
                        item { Text("直播源", fontSize = 22.sp) }
                        item {
                            if (settingsTvBoxOnly) TvSourceInput(
                                tvBoxLiveUrlDraft, { tvBoxLiveUrlDraft = it },
                                "直播源 · M3U / TXT / TVBox HTTP(S) 地址",
                                fieldTag = "tvbox-live-direct-url", navTag = "tvbox-live-direct-nav",
                                navRequester = requester("tvbox-live-direct-url"),
                                editRequester = requester("tvbox-live-direct-editor"),
                                onNavFocus = { focusId = "tvbox-live-direct-url"; configInputFocused = false },
                                onEditingFocus = { configInputFocused = it; if (it) focusId = "tvbox-live-direct-editor" },
                                onMoveUp = { moveCompactSettingsFocus("phone-config-main") },
                                onMoveDown = { moveCompactSettingsFocus("tvbox-live-direct-load") }
                            ) else OutlinedTextField(tvBoxLiveUrlDraft, { tvBoxLiveUrlDraft = it },
                                Modifier.fillMaxWidth().testTag("tvbox-live-direct-url")
                                    .onFocusChanged { configInputFocused = it.isFocused },
                                label = { Text("直播源 · M3U / TXT / TVBox HTTP(S) 地址") }, singleLine = true)
                        }
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(if (settingsTvBoxOnly) "应用直播源" else "导入直播源并打开直播",
                                modifier = Modifier.testTag("tvbox-live-direct-load")
                                    .focusRequester(requester("tvbox-live-direct-load")),
                                reduced = reduceMotion, onFocus = {
                                    focusId = "tvbox-live-direct-load"
                                    keyboard?.hide()
                                }) {
                                val source = tvBoxLiveUrlDraft.trim()
                                liveImportFeedback = "读取中…"
                                uiScope.launch {
                                    tvBoxStatus = "正在验证直播源…"
                                    when (val loaded = tvBoxLiveRepo.load(source, tvBoxAllowHttp)) {
                                        is TvBoxLiveResult.Error -> {
                                            tvBoxStatus = loaded.reason
                                            liveImportFeedback = "失败"
                                        }
                                        is TvBoxLiveResult.Ready -> {
                                            if (tvBoxLiveStore.save(loaded.channels, tvBoxAllowHttp)) {
                                                prefs.tvBoxLiveActive = true
                                                prefs.tvBoxLiveSourceUrl = source
                                                liveRefresh++
                                                tvBoxStatus = "已验证并缓存 ${loaded.channels.size} 个直播频道，请打开直播页"
                                                liveImportFeedback = "成功"
                                            } else {
                                                tvBoxStatus = "本地保存直播频道失败"
                                                liveImportFeedback = "失败"
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))

                        } }
                        if (!settingsTvBoxOnly) {
                            item { Text(tmdbState.preferredSiteStatus, modifier = Modifier.testTag("preferred-site-status"), fontSize = 13.sp) }
                            item { Text(tvBoxStatus, modifier = Modifier.testTag("tvbox-status"),
                                color = Color(0xFFCFD7E4), lineHeight = 23.sp) }
                        }
                        }
                        if (!settingsTvBoxOnly) {
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
                            val directCount = preview.sites.count { it.apiUrl != null }
                            val adapterCount = preview.sites.count { it.needsAdapter }
                            item { Text("${if (tvBoxSelectedName.isBlank()) "当前单仓" else tvBoxSelectedName} · ${preview.sites.size} 个影视站点", fontSize = 18.sp) }
                            item { Text(preview.sites.take(15).joinToString("  ·  ") { it.name }.ifBlank { "未提供站点条目" },
                                modifier = Modifier.testTag("tvbox-site-preview"), lineHeight = 24.sp) }
                            item { Text("${directCount} 个 HTTP(S) CMS 接口；${adapterCount} 个站点需要 Spider/JS。导入时仅读取站点清单，选择影视点后才读取目录；成功识别配置仍不等于可播放。",
                                modifier = Modifier.testTag("tvbox-site-capabilities"), lineHeight = 23.sp) }
                            item { TvButton(if (tvBoxShowAllSites) "收起完整站点列表" else "查看全部 ${preview.sites.size} 个影视站点",
                                modifier = Modifier.testTag("tvbox-sites-toggle"), reduced = reduceMotion) {
                                tvBoxShowAllSites = !tvBoxShowAllSites
                            } }
                            if (tvBoxShowAllSites) {
                                itemsIndexed(preview.sites) { index, site ->
                                    TvButton("${index + 1}. ${site.name} · ${when {
                                        site.apiUrl != null -> "标准 CMS 试读入口"
                                        site.spiderApi != null -> "Java Spider · 可尝试"
                                        site.needsAdapter -> "需其他解析插件"
                                        else -> "无可访问接口"
                                    }}", modifier = Modifier.testTag("tvbox-site-$index"), reduced = reduceMotion) {
                                        tvBoxStatus = when {
                                            site.apiUrl != null -> "${site.name} 是 HTTP(S) 入口；请使用上方「浏览 ${site.name}」尝试标准 CMS JSON。"
                                            site.spiderApi != null -> "${site.name} 使用 Java Spider；应用影视源时会自动尝试，或点击下方浏览按钮。"
                                            site.needsAdapter -> "${site.name} 需要不属于 Java csp_* 的其他插件，当前不能执行。"
                                            else -> "${site.name} 未声明 HTTP(S) 影视接口，暂无可用的内容加载方式。"
                                        }
                                    }
                                }
                            }
                            val directSites = preview.sites.filter { it.apiUrl != null }
                            if (directSites.isNotEmpty()) {
                                item { Text("可尝试读取的标准 CMS 影视接口", fontSize = 18.sp) }
                                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    itemsIndexed(directSites) { index, site ->
                                        TvButton("浏览 ${site.name}", modifier = Modifier.testTag("tvbox-vod-$index"),
                                            reduced = reduceMotion) {
                                            activateTvBoxSite(site, preview, tvBoxAllowHttp)
                                        }
                                    }
                                } }
                            }
                            val spiderSites = preview.sites.filter { it.spiderApi != null || it.scriptApi != null }
                            if (spiderSites.isNotEmpty()) {
                                item { Text("Java Spider 影视站点 · ${spiderSites.size} 个", fontSize = 18.sp) }
                                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    itemsIndexed(spiderSites) { index, site ->
                                        TvButton("浏览 ${site.name}", modifier = Modifier.testTag("tvbox-spider-$index"),
                                            reduced = reduceMotion) {
                                            activateTvBoxSite(site, preview, tvBoxAllowHttp)
                                        }
                                    }
                                } }
                                item { Text("外部插件运行于应用进程，可能执行任意代码。当前仅支持 Java JAR + MD5 校验；QuickJS/Python、云盘授权、网页二次解析及需要内置代理的线路尚未保证兼容。", color = Color(0xFFE2C38B), lineHeight = 22.sp) }
                            }
                            val playlists = preview.lives.filter { it.sourceUrl != null || it.channels.isNotEmpty() }.take(15)
                            if (playlists.isNotEmpty()) {
                                item { Text("可导入的 TVBox 直播入口（频道将在导入前验证）", fontSize = 18.sp) }
                                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    itemsIndexed(playlists) { index, live ->
                                        TvButton("导入 ${live.name}", modifier = Modifier.testTag("tvbox-live-$index"),
                                            reduced = reduceMotion) {
                                            uiScope.launch {
                                                tvBoxStatus = "正在验证 ${live.name} 直播频道…"
                                                val loaded = if (live.channels.isNotEmpty()) {
                                                    val safe = live.channels.filter { TvBoxUrl.normalize(it.url, tvBoxAllowHttp) != null }
                                                    if (safe.isEmpty()) TvBoxLiveResult.Error("此直播项没有通过安全校验的频道地址")
                                                    else TvBoxLiveResult.Ready(safe)
                                                } else tvBoxLiveRepo.load(live.sourceUrl.orEmpty(), tvBoxAllowHttp)
                                                when (loaded) {
                                                    is TvBoxLiveResult.Error -> tvBoxStatus = "${live.name}：${loaded.reason}"
                                                    is TvBoxLiveResult.Ready -> {
                                                        if (tvBoxLiveStore.save(loaded.channels, tvBoxAllowHttp)) {
                                                            prefs.tvBoxLiveActive = true
                                                            liveRefresh++
                                                            selectedLiveGroup = null
                                                            tvBoxStatus = "已导入 ${live.name} 的 ${loaded.channels.size} 个频道；打开直播页即可观看"
                                                        } else tvBoxStatus = "${live.name}：缓存频道失败"
                                                    }
                                                }
                                            }
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
                        }
                        if (!settingsTvBoxOnly) {
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
                        item { Text("可选高清海报元数据（TMDB）", fontSize = 21.sp) }
                        item { Text("TMDB 需要你自己的 API Read Access Token。仅在你主动保存 Token 后匹配影片元数据；匹配不确定时保留影视源海报。", color = Color(0xFFCDD8E6)) }
                        item { TmdbTokenEntryFields(
                            compact = false, reducedMotion = reduceMotion,
                            splitMode = tmdbTokenSplitMode, onToggle = { toggleTmdbSplitInput() },
                            single = tmdbTokenDraft, onSingle = { tmdbTokenDraft = it },
                            first = tmdbTokenPart1, onFirst = { tmdbTokenPart1 = it },
                            second = tmdbTokenPart2, onSecond = { tmdbTokenPart2 = it },
                            third = tmdbTokenPart3, onThird = { tmdbTokenPart3 = it }
                        ) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("保存 TMDB Token", modifier = Modifier.testTag("tmdb-token-save"),
                                reduced = reduceMotion, onFocus = { keyboard?.hide() }) {
                                val value = candidateTmdbToken()
                                if (value != null) uiScope.launch {
                                    val saved = withContext(Dispatchers.IO) { runCatching { prefs.tmdbReadToken = value }.isSuccess }
                                    if (!saved) { tmdbSettingsStatus = "安全存储不可用，令牌未保存"; return@launch }
                                    metadataResolver.clearAuthCooldown(value)
                                    // Saving the SAME token must still retry old negative results;
                                    // match the compact-settings action and never require relaunch.
                                    metadataForceIds = providerFilms.orEmpty().map { it.id }.toSet()
                                    providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                    metadataToken = value
                                    clearTmdbInput()
                                    metadataRescanEpoch++
                                    uiScope.launch {
                                        withContext(Dispatchers.IO) { tmdbDiscovery.invalidate(metadataToken) }
                                        tmdbProbeEpoch++
                                    }
                                    tmdbSettingsStatus = "已保存；正在重新匹配海报与影视资料"
                                } else tmdbSettingsStatus = "请输入完整的 TMDB API Read Access Token"
                            }
                            TvButton("停用高清刮削", modifier = Modifier.testTag("tmdb-token-clear"),
                                reduced = reduceMotion) {
                                uiScope.launch {
                                val saved = withContext(Dispatchers.IO) { runCatching { prefs.tmdbReadToken = "" }.isSuccess }
                                if (!saved) { tmdbSettingsStatus = "安全存储不可用，未更改设置"; return@launch }
                                metadataToken = ""
                                providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                clearTmdbInput()
                                tmdbSettingsStatus = "已停用 TMDB 网络匹配，改用源内海报"
                                }
                            }
                        } }
                        item { Text(tmdbSettingsStatus, color = Color(0xFFCDD8E6)) }
                        item { Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Image(painterResource(R.drawable.tmdb_official_logo),
                                contentDescription = "TMDB 官方徽标",
                                modifier = Modifier.width(90.dp).height(65.dp),
                                contentScale = ContentScale.Fit)
                            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                                fontSize = 12.sp, color = Color(0xFF99A6B8))
                        } }
                        item { TvButton("手机扫码配置", modifier = Modifier.testTag("phone-config-open"), reduced = reduceMotion) { lanConfigOpen = true } }
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
                        item { TvButton("检测 TMDB 图片服务", reduced = reduceMotion) {
                            imageCdnStatus = "正在独立检测图片 CDN…"
                            uiScope.launch { imageCdnStatus = withContext(Dispatchers.IO) { TmdbImageConnectivity.check() } }
                        } }
                        item { Text(imageCdnStatus, fontSize = 14.sp) }
                        item { Text(deviceStatus, modifier = Modifier.testTag("device-status"),
                            color = Color(0xFFCFD7E4), lineHeight = 22.sp) }
                        item { TvButton(if (confirmCacheClear) "再次确定清理海报缓存" else "清理海报缓存",
                            modifier = Modifier.testTag("poster-cache-clear"), reduced = reduceMotion) {
                            if (!confirmCacheClear) {
                                confirmCacheClear = true
                                posterCacheStatus = "请在 15 秒内再次确定；只清理授权海报缓存文件"
                            } else {
                                confirmCacheClear = false
                                uiScope.launch {
                                    val cleared = withContext(Dispatchers.IO) { tmdbDiscovery.clearPersistent(); posterCache.clearFiles() }
                                    posterCacheStatus = "已清理 $cleared 个缓存文件；收藏、播放历史和目录均已保留"
                                }
                            }
                        } }
                        item { Text(posterCacheStatus, modifier = Modifier.testTag("poster-cache-status"),
                            color = Color(0xFFBDC8D6)) }
                        item { Text(providerStatus, fontSize = 15.sp, color = Color(0xFFCFD7E4)) }
                        item { Text(configStatus, fontSize = 13.sp, color = Color(0xFFBDC8D6)) }
                        item { Text("仅保存你有权访问的 HTTPS 目录。可切换、修改、二次确认删除、复制 JSON 备份或从 JSON 合并导入，最多 10 个。更换 URL 会变更收藏和续播作用域；删除目录不会删除其历史记录。不会抓取第三方影视站。", lineHeight = 22.sp) }

                        item { Text("HERRIN M4.25 Alpha · 原生 Android TV\n影视播放源只提供播放线路，TMDB 独立补充海报、简介、评分、演职员头像、上映日期、片长与影片分类。\n影视首页右上角「筛选」可按 TMDB 评分、演员、导演、地区及年份组合筛选。\n当前影视点内，同一作品被拆成多个季条目时可整合为统一海报、季标签和16:9单集卡；原始来源和播放线路均保留。\n跨不同影视点的内容尚未统一扫描整合；缺乏可靠季集标签时可进入原始条目。\n详情页媒体规格严格区分影视源标称与播放器已识别格式，未知内容播放后再识别。\n在高级设置配置个人 TMDB API Token 后启用刮削；不采集未经授权的豆瓣评分。\n直播上下键换台，选频道即播；TVBox 网页解析/受保护线路尚未保证兼容。\n方向键移动 · 确定选择 · 返回上一级", lineHeight = 28.sp) }
                        }
                    
                    }
                    fun androidx.compose.foundation.lazy.LazyListScope.renderDetailItems() {
                        item { Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(if (screen == "player" && !spiderActive && film.id.startsWith("tmdb-") &&
                                    film.playbackEpisodes.isEmpty()) "片源搜索"
                                else if (screen == "player" && !spiderActive && !film.id.startsWith("tmdb-")) "播放演示"
                                else if (screen == "detail") displayFilm.title else film.title,
                                fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            HerrinRatingBadges(displayFilm)
                            Text(buildString {
                                append(displayFilm.releaseDate ?: displayFilm.year).append("  ·  ")
                                append(displayFilm.category)
                                displayFilm.runtimeMinutes?.let {
                                    append("  ·  ")
                                    append(if (displayFilm.category == "剧集" || displayFilm.category == "动漫") "单集约 " else "资料片长 ")
                                    append(it).append(" 分钟")
                                }
                                verifiedStreams[streamEvidenceKey]?.labels()
                                    ?.filter { it == "4K" || it.startsWith("HDR") ||
                                        it.startsWith("Dolby") }
                                    ?.distinct()?.take(3)?.forEach { append("  ·  ").append(it) }
                                if (displayFilm.genres.isNotEmpty()) append("\n类型：").append(displayFilm.genres.joinToString(" · "))
                                if (displayFilm.regions.isNotEmpty()) append("\n国家 / 地区：").append(displayFilm.regions.joinToString(" · "))
                                if (displayFilm.directors.isNotEmpty()) append("\n导演 / 主创：").append(displayFilm.directors.joinToString(" · "))
                            }, lineHeight = 25.sp, modifier = Modifier.testTag("detail-metadata"))
                        } }
                        item { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("剧情简介", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                Text(displayFilm.synopsis.ifBlank { "暂未取得可靠剧情简介" },
                                    modifier = Modifier.testTag("detail-synopsis"), lineHeight = 26.sp,
                                    color = Color(0xFFE1E8F2))
                            } }
                        // H-004: Favorite always sits between synopsis and node selector.
                        if (screen == "detail") item(key = "detail-favorite") {
                            DisposableEffect(Unit) { onDispose {
                                detailFavoriteAttached = false
                                detailFavoriteFocused = false
                            } }
                            val groupFavorited = showGroup?.members?.any { it.film.id in favorites }
                                ?: (film.id in favorites)
                            TvButton(if (groupFavorited) "♥ 已收藏 · 取消" else "♡ 添加收藏",
                                modifier = Modifier.testTag("detail-favorite")
                                    .focusRequester(requester("detail-favorite"))
                                    .onGloballyPositioned { detailFavoriteAttached = true }
                                    .onFocusChanged {
                                        detailFavoriteFocused = it.isFocused
                                        if (it.isFocused) {
                                            focusId = "detail-favorite"
                                            restorePlaybackDetailFocus = false
                                        }
                                    }, reduced = reduceMotion) {
                                if (showGroup == null) prefs.toggleFor(catalogScope, film.id)
                                else if (groupFavorited) showGroup.members.filter { it.film.id in favorites }
                                    .forEach { prefs.toggleFor(catalogScope, it.film.id) }
                                else prefs.toggleFor(catalogScope, showGroup.representative.id)
                                favorites = prefs.idsFor(catalogScope)
                            }
                        }
                        if(film.id.startsWith("tmdb-") && screen == "detail") {
                            val sourceSnapshot = tmdbState.discoveredSources[film.id] ?: HerrinSourceDiscovery.Snapshot("pending-" + film.id, 0)
                            sourceSnapshot.let { discovery ->
                                herrinDiscoveryItems(discovery, tmdbState.tmdbSearchRunning, reduceMotion,
                                    select = { hit, selectedLine ->
                                        if (hit.node.id !in discovery.selectableNodeIds ||
                                            hit.episodes.isEmpty()) return@herrinDiscoveryItems
                                        DetailPlaybackMetrics.timeline.click()
                                        tmdbState.discoveryRecovery = null
                                        tmdbState.rejectedDiscoveryHits.clear()
                                        pendingHistoryFilm = null
                                        // Reuse the real, already fetched line/episode layout.
                                        // The preview has no URI, headers or playback authority.
                                        val preview = hit.episodes.map { ep ->
                                            PlaybackEpisode(ep.number, ep.title, ep.lines.map { (id, title) ->
                                                PlaybackLine(id, title, "")
                                            })
                                        }
                                        val previous = tmdbState.tmdbResolved[film.id] ?: film
                                        val same = tmdbState.loadedDiscoveryHits[film.id] == hit.key &&
                                            tmdbState.sourceSelectionReady(film.id)
                                        if (!same) {
                                            tmdbState.tmdbResolved[film.id] = previous.copy(
                                                playbackEpisodes = preview, episodes = preview.size,
                                                cmsVodId = hit.movie.film.cmsVodId,
                                                spiderVodId = hit.movie.film.spiderVodId)
                                        }
                                        val chosen = preview.firstOrNull { ep -> same && ep.number == episode &&
                                            ep.lines.any { it.id == selectedLine }
                                        } ?: preview.firstOrNull { ep ->
                                            ep.lines.any { it.id == selectedLine }
                                        } ?: preview.firstOrNull()
                                        if (chosen != null) {
                                            episode = chosen.number
                                            line = selectedLine?.takeIf { id ->
                                                chosen.lines.any { it.id == id }
                                            } ?: chosen.lines.firstOrNull()?.id.orEmpty()
                                        }
                                        if (same) {
                                            // A different already-loaded line needs no detail fetch.
                                            tmdbState.discoveryLine = null
                                            screen = "player"
                                            return@herrinDiscoveryItems
                                        }
                                        if (tmdbState.requestedDiscoveryHits[film.id] == hit.key &&
                                            tmdbState.discoveryLoadingFilm == film.id) {
                                            // Keep the requested line while one detail rebind is
                                            // in flight. Never cancel/restart the same request.
                                            tmdbState.discoveryLine = selectedLine
                                            tmdbState.requestDiscoveryPlay(film.id, hit.key, chosen?.number)
                                            return@herrinDiscoveryItems
                                        }
                                        tmdbState.requestedDiscoveryHits[film.id] = hit.key
                                        tmdbState.discoveryLine = selectedLine
                                        tmdbState.discoverySelection = film.id to hit.key
                                        tmdbState.discoverySelectionEpoch++
                                        tmdbState.requestDiscoveryPlay(film.id, hit.key, chosen?.number)
                                    }, resume = { failedNode ->
                                        tmdbState.discoverySelection = null
                                        val node = failedNode ?: tmdbState.selectedDiscoveryNode[film.id]
                                            ?.takeIf { discovery.selectableNodeIds.isNotEmpty() }
                                        if (node != null) uiScope.launch {
                                            tmdbState.sourceDiscoveries[film.id]?.resetNode(discovery.revision, node)?.let { tmdbState.discoveredSources[film.id] = it }
                                            tmdbState.tmdbLookupEpoch++
                                        } else {
                                            tmdbState.sourceDiscoveries.remove(film.id)
                                            tmdbState.discoveredSources.remove(film.id)
                                            tmdbState.autoLayoutSelected.remove(film.id)
                                            tmdbState.selectedDiscoveryNode.remove(film.id)
                                            tmdbState.tmdbLookupEpoch++
                                        }
                                        tmdbState.tmdbExtendedRequest = film.id
                                    }, selectedNode = tmdbState.selectedDiscoveryNode[film.id],
                                    onNode = { tmdbState.selectedDiscoveryNode[film.id] = it },
                                    lineFocus = { index -> requester(if (index == 0)
                                        "detail-first-line" else "detail-line-$index") },
                                    onLineFocus = { index ->
                                        lastDetailLineFocus = if (index == 0)
                                            "detail-first-line" else "detail-line-$index"
                                    },
                                    onDown = ::enterDetailEpisodes)
                            }
                        }
                        if (showGroup != null && screen == "detail") item {
                            Text("多季整合 · 当前目录 ${showGroup.members.size} 个原始条目 · 播放仍使用原影视点",
                                modifier = Modifier.testTag("group-detail-members"),
                                color = Color(0xFFBFD6EF), fontSize = 15.sp)
                            if (groupDetailStatus.isNotBlank()) Text(groupDetailStatus,
                                modifier = Modifier.testTag("group-detail-status"),
                                color = Color(0xFFE1C58C), fontSize = 14.sp)
                        }
                        // Third-party playback resolution stays internal, not on the movie details UI.
                        if (!film.id.startsWith("tmdb-") && !spiderActive && film.cmsVodId != null && cmsDetailStatus.isNotBlank())
                            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(cmsDetailStatus, color = Color(0xFFE2C38B),
                                    modifier = Modifier.testTag("cms-detail-status"))
                                if (film.playbackEpisodes.isEmpty() &&
                                    !cmsDetailStatus.startsWith("正在")) {
                                    TvButton("重新读取选集", modifier = Modifier.testTag("cms-detail-retry"),
                                        reduced = reduceMotion) {
                                        cmsDetailStatus = "正在重新读取选集…"
                                        detailLoadEpoch++
                                    }
                                }
                            } }
                        if (screen == "player") {
                            if (!spiderActive) item { var status by remember { mutableStateOf("正在准备演示…") }
                                LaunchedEffect(film.id, episode, line) {
                                    status = when(val result = DemoPlaybackSource().resolve(PlaybackRequest(film.id, episode, line))) {
                                        is PlaybackResult.Unavailable -> result.reason
                                        is PlaybackResult.Ready -> "适配器尚未安装"
                                    }
                                }
                                Text(status, fontSize = 20.sp)
                            }
                            item { TvButton("返回详情", reduced = reduceMotion) {
                                restoreGroupFocus = showGroup != null
                                restoreSeasonFocus = showGroup == null && seasonLayout != null
                                screen = "detail"
                            } }
                        } else {
                            item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (tmdbState.sourceSelectionReady(film.id) && !HerrinPlaybackEntry.unavailableTmdb(film) && (providerFilms == null || film.id.startsWith("tmdb-"))) TvButton(DetailPlayLabel.text(
                                    season = groupResume?.first?.season ?: seasonResume?.entry?.season ?: detailSeason?.number ?:
                                        SeasonEpisodes.explicitEpisode(currentEpisode?.title.orEmpty())?.first ?:
                                        SeasonEpisodes.explicitSeason(currentLines.firstOrNull { it.id == line }?.title.orEmpty()),
                                    episode = groupResume?.first?.number ?: seasonResume?.entry?.number ?:
                                        (detailSeasonSource ?: detailSeasonEntries.firstOrNull())?.number ?:
                                        SeasonEpisodes.explicitEpisode(currentEpisode?.title.orEmpty())?.second ?:
                                        SeasonEpisodes.explicitNumber(currentEpisode?.title.orEmpty()),
                                    positionMs = groupResume?.second?.positionMs ?: seasonResume?.position?.positionMs ?: resume?.positionMs ?:
                                        tmdbState.automaticResume?.takeIf { it.film == film.id && it.episode == episode && it.line == line }?.position
                                ), modifier = Modifier.testTag("detail-play-cta"), selected = true, reduced = reduceMotion) {
                                    DetailPlaybackMetrics.timeline.click()
                                    if (showGroup != null && groupResume != null)
                                        playGroupSource(groupResume.first)
                                    else if (showGroup != null && groupSection?.episodes?.isNotEmpty() == true)
                                        playGroupEpisode(groupSection.episodes.first())
                                    else if (showGroup != null)
                                        groupDetailStatus = "还没有可播放选集；请等待详情返回或改选原始条目"
                                    else if (spiderActive && film.playbackEpisodes.isEmpty())
                                        spiderStatus = "Spider 尚未返回播放选集，请稍后重试或选择其他站点"
                                    else if (providerFilms != null && film.playbackEpisodes.isEmpty())
                                        cmsDetailStatus = "该影视点尚未返回可播放选集；请检查来源或更换站点"
                                    else if (seasonResume != null)
                                        playSeasonEpisode(seasonResume.entry, seasonResume.line.id)
                                    else if (seasonLayout != null && detailSeasonEntries.isNotEmpty())
                                        playSeasonEpisode(detailSeasonSource ?: detailSeasonEntries.first())
                                    else screen = "player"
                                }

                            } }
                            if (providerFilms == null && showGroup == null && resume != null && seasonLayout == null) {
                                item { Text("继续播放 · 已观看 ${resume.positionMs / 1000} 秒 / ${resume.durationMs / 1000} 秒",
                                    fontSize = 17.sp, color = Color(0xFFD4E6FD)) }
                                item { TvButton("从头播放", reduced = reduceMotion) {
                                    watchHistory.clear(catalogScope, film.id, episode,
                                        if (currentLines.isEmpty()) "independent-test" else line)
                                    screen = "player"
                                } }
                            }
                            if (!film.id.startsWith("tmdb-") && showGroup == null && !HerrinPlaybackEntry.unavailableTmdb(film))
                                item { Text("线路",
                                modifier = Modifier.testTag("detail-line-heading"),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                            if (!film.id.startsWith("tmdb-") && showGroup == null && !HerrinPlaybackEntry.unavailableTmdb(film) &&
                                (providerFilms == null || displayedLines.isNotEmpty())) item {
                                DetailLineRow(
                                    if (displayedLines.isEmpty()) listOf("演示线路 A" to "演示线路 A", "演示线路 B" to "演示线路 B")
                                    else displayedLines.map { it.id to it.title },
                                    line, reduceMotion,
                                    requester = { index -> requester(if (index == 0) "detail-first-line" else "detail-line-$index") },
                                    onFocus = { index -> lastDetailLineFocus = if (index == 0) "detail-first-line" else "detail-line-$index" },
                                    onDown = ::enterDetailEpisodes,
                                    onSelect = { id -> pendingHistoryFilm = null; line = id; detailSeason?.let { detailSeasonLines[it.number] = id } })
                            }

                            if (showGroup != null && groupLayout != null && groupSection != null) {
                                item {
                                    GroupSeasonBrowser(groupLayout, groupSection,
                                        groupRowState, groupOfficialEpisodes,
                                        reduced = reduceMotion,
                                        selected = selectedGroupOption,
                                        focusedEpisode = groupFocusedEpisode,
                                        restoreEpisode = groupFocusedCoordinates.takeIf { restoreGroupFocus },
                                        onRestoreFocus = { restoreGroupFocus = false },
                                        history = { entry -> entry.sources.mapNotNull { option ->
                                            watchHistory.load(catalogScope, option.film.id,
                                                option.source.number, option.line.id)
                                        }.maxByOrNull { it.updatedAtMs } },
                                        onSeasonSelect = ::selectGroupSeason,
                                        onEpisodeFocus = { entry ->
                                            groupFocusedCoordinates = entry.season to entry.number
                                        }, onEpisodePlay = ::playGroupEpisode,
                                        onLinePlay = ::selectGroupSource,
                                        onMoveToCast = ::moveToDetailCast)
                                }
                            } else if (showGroup != null) {
                                item { Text("当前原始条目没有可信的季集标记；可打开单个影视条目继续选集。",
                                    modifier = Modifier.testTag("group-unmapped"),
                                    color = Color(0xFFBDC8D6)) }
                            } else if (seasonLayout != null && detailSeason != null) {
                                item {
                                    SeasonBrowser(seasonLayout, detailSeason,
                                        detailSeasonRowState, seasonOfficialEpisodes,
                                        reduced = reduceMotion,
                                        currentlyPlayingEpisode = episode,
                                        currentlyPlayingLine = line,
                                        history = { entry -> entry.lines.asSequence().mapNotNull { option ->
                                            watchHistory.load(catalogScope, film.id,
                                                entry.source.number, option.id)
                                        }.maxByOrNull { it.updatedAtMs } },
                                        onSeasonSelect = ::selectDetailSeason,
                                        onEpisodeFocus = { entry ->
                                            detailSeasonEpisodeNumbers[entry.season] = entry.source.number
                                        }, onPlay = { entry ->
                                            playSeasonEpisode(entry)
                                        },
                                        onMoveToCast = ::moveToDetailCast, entryFocus = requester("detail-current-episode"), onMoveToLines = ::returnDetailLine)
                                }
                            } else if (!film.id.startsWith("tmdb-") || film.playbackEpisodes.isNotEmpty()) {
                                item { Text(if (providerFilms != null && film.playbackEpisodes.isEmpty())
                                    "原始条目 · 尚无可播放的真实选集"
                                    else if (film.playbackEpisodes.isEmpty()) "集数" else "集数",
                                    fontSize = 20.sp) }
                                if (providerFilms != null && film.playbackEpisodes.isEmpty()) item {
                                    Text("当前影视点未返回有效的集数或线路；不会将演示集当作真实播放地址。",
                                        modifier = Modifier.testTag("source-no-playable-episodes"),
                                        color = Color(0xFFD9B78A), fontSize = 14.sp)
                                } else item { Row(Modifier.fillMaxWidth().drawWithContent { drawContent(); DetailPlaybackMetrics.timeline.episodesDrawn() }.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (film.playbackEpisodes.isEmpty()) {
                                        (1..film.episodes).forEach { n ->
                                            TvButton("第 $n 集", modifier = Modifier.testTag("episode-$n")
                                                .then(if (n == episode) Modifier.focusRequester(requester("detail-current-episode")) else Modifier)
                                                .onPreviewKeyEvent { event ->
                                                    event.type == KeyEventType.KeyDown && when (event.nativeKeyEvent.keyCode) {
                                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> returnDetailLine()
                                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> moveToDetailCast()
                                                        else -> false
                                                    }
                                                }, reduced = reduceMotion,
                                                selected = episode == n) {
                                                if (film.id.startsWith("tmdb-") &&
                                                    (!tmdbState.sourceSelectionReady(film.id) ||
                                                        HerrinPlaybackEntry.unavailableTmdb(film))) {
                                                    tmdbState.tmdbSourceStatus = "选集已显示，播放授权正在就绪"
                                                } else {
                                                    episode = n
                                                    DetailPlaybackMetrics.timeline.click()
                                                    screen = "player"
                                                }
                                            }
                                        }
                                    } else {
                                        film.playbackEpisodes.forEach { entry ->
                                            TvButton("第 ${entry.number} 集 · ${entry.title}",
                                                modifier = Modifier.testTag("episode-${entry.number}")
                                                    .then(if (entry.number == episode) Modifier.focusRequester(requester("detail-current-episode")) else Modifier)
                                                    .onPreviewKeyEvent { event ->
                                                        if (event.type != KeyEventType.KeyDown) false
                                                        else when (event.nativeKeyEvent.keyCode) {
                                                            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                                                                returnDetailLine()
                                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                                                                moveToDetailCast()
                                                            else -> false
                                                        }
                                                    },
                                                reduced = reduceMotion, selected = episode == entry.number) {
                                                DetailPlaybackMetrics.timeline.click()
                                                if (film.id.startsWith("tmdb-") &&
                                                    (!tmdbState.sourceSelectionReady(film.id) ||
                                                        HerrinPlaybackEntry.unavailableTmdb(film))) {
                                                    entry.lines.firstOrNull { it.id == line }?.id
                                                        ?.let { playDiscoveryEpisode(entry.number, it) }
                                                        ?: entry.lines.firstOrNull()?.id?.let { playDiscoveryEpisode(entry.number, it) }
                                                } else {
                                                    episode = entry.number
                                                    line = entry.lines.firstOrNull { it.id == line }?.id
                                                        ?: entry.lines.firstOrNull()?.id ?: line
                                                    screen = "player"
                                                }
                                            }
                                        }
                                    }
                                } }
                            }
                        if (showGroup != null && unmappedGroupMembers.isNotEmpty()) item {
                            GroupRawSourceChooser(unmappedGroupMembers, reduceMotion) { original ->
                                bypassGroupFilmId = original.id
                                filmId = original.id
                                episode = original.playbackEpisodes.firstOrNull()?.number ?: 1
                                line = original.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
                                restoreGroupFocus = false
                            }
                        }
                        if (displayFilm.people.isNotEmpty()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("相关演职人员", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                LazyRow(Modifier.testTag("detail-people"),
                                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                                    itemsIndexed(displayFilm.people.take(10), key = { index, person ->
                                        "$index-${person.name}" }) { index, person ->
                                        var personFocused by remember(person.name) { mutableStateOf(false) }
                                        Column(Modifier.width(122.dp)
                                            .testTag("detail-person-$index")
                                            .border(2.dp, if (personFocused) Color.White else Color.Transparent,
                                                RoundedCornerShape(14.dp))
                                            .onFocusChanged {
                                                personFocused = it.isFocused
                                                if (it.isFocused) focusId = "detail-person-$index"
                                            }
                                            .then(if (index == 0)
                                                Modifier.focusRequester(requester("detail-person-0"))
                                                else Modifier)
                                            .focusable()
                                            .padding(vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            PersonPortrait(person, Modifier.size(82.dp))
                                            Text(person.name, fontSize = 14.sp, maxLines = 2,
                                                textAlign = TextAlign.Center, lineHeight = 17.sp)
                                            Text(person.role.ifBlank { "演员" }, fontSize = 12.sp,
                                                maxLines = 2, textAlign = TextAlign.Center,
                                                color = Color(0xFFB7C5D5), lineHeight = 15.sp)
                                        }
                                    }
                                }
                            }
                        } else if (displayFilm.actors.isNotEmpty()) item {
                            Text("主演：${displayFilm.actors.take(8).joinToString(" · ")}",
                                modifier = Modifier.testTag("detail-people")
                                    .focusRequester(requester("detail-person-0"))
                                    .onFocusChanged { if (it.isFocused) focusId = "detail-person-0" }
                                    .focusable(), color = Color(0xFFD7E0EA))
                        }


                        }
                    
                    }
                    Row(Modifier.fillMaxSize().background(Color(0xAA080B10))) {
                    if (screen == "settings" && settingsTvBoxOnly) SettingsModules(settingsModule, reduceMotion) {
                        settingsModule = it
                        configInputFocused = false
                        uiScope.launch { settingsListState.scrollToItem(0) }
                    }
                    LazyColumn(Modifier.testTag(if (screen == "detail") "film-detail-list" else "settings-list").fillMaxSize(), state = settingsListState,
                    contentPadding = PaddingValues(if (screen == "settings" && settingsTvBoxOnly) 24.dp else 48.dp),
                    verticalArrangement = Arrangement.spacedBy(if (screen == "settings" && settingsTvBoxOnly) 12.dp else 18.dp)) {
                    if (screen == "settings") item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!settingsTvBoxOnly)
                            TvButton("‹ 返回", Modifier.testTag("screen-first").focusRequester(requester("screen-first")),
                                reduceMotion, onFocus = { if (screen == "settings") focusId = "screen-first" }) { back() }
                        if (screen == "settings") Spacer(Modifier.weight(1f))
                        if (screen == "settings") TvButton("手机扫码配置", Modifier.testTag("phone-config-main")
                            .focusRequester(requester("phone-config-main")), reduceMotion,
                            onFocus = { focusId = "phone-config-main" }) { lanConfigOpen = true }
                    } }
                    if (screen == "settings") renderSettingsItems()
                    else renderDetailItems()
                    }
                }
                }
            }
        }
        metadataCorrection?.let { original ->
            MetadataCorrectionDialog(original, metadataResolver.manualMatch(original),
                onConfirm = { match ->
                    metadataResolver.setManualMatch(original, match)
                    metadataForceIds = setOf(original.id)
                    providerFilms = providerFilms?.map { if (it.id == original.id) FilmMetadataEnrichment.restore(it) else it }
                    metadataRescanEpoch++
                    metadataCorrection = null
                }, onDismiss = { metadataCorrection = null })
        }
        if (exitConfirmation) ExitConfirmation(onCancel = {
            exitConfirmation = false
            lastExitDismissedAt = SystemClock.uptimeMillis()
            val previousFocus = focusId
            uiScope.launch { withFrameNanos { }; runCatching { requester(previousFocus).requestFocus() } }
        }, onExit = {
            exitConfirmation = false
            lanConfigOpen = false
            (context as? android.app.Activity)?.finish()
        })
        if (lanConfigOpen) LanConfigDialog(onDismiss = { lanConfigOpen = false }, onApplied = { module ->
            when (module) {
                "tmdb" -> {
                    phoneTokenReload[0]?.cancel()
                    phoneTokenReload[0] = uiScope.launch {
                        val saved = withContext(Dispatchers.IO) { Preferences(context).tmdbReadToken }
                        prefs.acceptPersistedToken(saved)
                        metadataToken = saved
                        tmdbProbeEpoch++
                    }
                }
                "sources" -> {
                    tvBoxRunSpiderAfterApply = false
                    val pending = phoneSourceImport.current()?.takeIf { it.state == "SAVED" }
                    if (pending != null) {
                        // Never invalidate an active player's personal routes
                        // merely because the phone SENT a candidate. First
                        // validate/commit the warehouse, then refresh source state.
                        phoneSourceId = pending.id
                        tvBoxUrlDraft = pending.displayUrl
                        tvBoxRequest = pending.url to true
                        tvBoxLoadEpoch++
                    } else personalSourceConfigEpoch++
                }
                "catalogs" -> { catalogRefresh++ }
                "live" -> { liveRefresh++; epgRefresh++ }
                "nas" -> { personalSourceConfigEpoch++ }
            }
        })
        if (personalOpenNotice && screen == "browse") Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Text("暂时无法打开个人媒体", color = Color.White,
                modifier = Modifier.padding(24.dp).background(Color(0xEE17191D)).padding(12.dp).testTag("personal-open-result"))
        }
        SideEffect {
            // StartupHost honors only Herrin's explicit preference. Some TV
            // firmware defaults the global animator scale to zero; applying
            // the combined UI flag here used to switch V13 to static mid-reveal.
            startup.reduced = reduced
            // The navigation/home skeleton is already committed in this
            // composition. Only the bounded local cache read is required; never
            // wait for a network response or a non-empty grouped projection.
            // While it finishes, the complete wordmark keeps breathing.
            startup.homeReady = tmdbState.cacheHydrated
            startup.restoreBrowseFocus={
                if(!startup.showing &&screen=="browse" &&!lanConfigOpen &&!exitConfirmation &&
                    !(category=="直播" &&liveSearchExpanded)) {
                    inputModes.requestInputMode(InputMode.Keyboard)
                    RemoteTrace.event { "BROWSE_WINDOW_FOCUS input_mode=${inputModes.inputMode}" }
                    // Restore synchronously when the OS grants this window input.
                    // Waiting for another key would consume the first direction.
                    runCatching{requester(pendingToolbarFocus?:focusId).requestFocus();true}.getOrElse{
                        runCatching{requester("nav-$category").requestFocus();true}.getOrDefault(false)
                    }
                }else false
            }
        }
    }
}

/**
 * Native D-pad season selector; publicly testable in instrumentation with synthetic,
 * user-authorized entries. Never changes a playback URL or synthesizes a missing season.
 */
@Composable
internal fun SeasonBrowser(
    layout: SeasonLayout,
    active: SeasonSection,
    rowState: LazyListState,
    officialEpisodes: Map<Int, TmdbEpisodeDisplay>,
    reduced: Boolean,
    currentlyPlayingEpisode: Int,
    currentlyPlayingLine: String,
    history: (SeasonEpisode) -> WatchPosition?,
    onSeasonSelect: (Int) -> Unit,
    onEpisodeFocus: (SeasonEpisode) -> Unit,
    onPlay: (SeasonEpisode) -> Unit,
    onMoveToCast: () -> Boolean = { false },
    entryFocus: FocusRequester? = null,
    onMoveToLines: (() -> Boolean)? = null
) {
    LaunchedEffect(active.number, currentlyPlayingEpisode) {
        val index = active.episodes.indexOfFirst { it.source.number == currentlyPlayingEpisode }
        if (index >= 0) rowState.scrollToItem(index)
    }
    val uiScope = rememberCoroutineScope()
    val focusers = remember { mutableMapOf<String, FocusRequester>() }
    fun focusAt(key: String): FocusRequester = focusers.getOrPut(key) { FocusRequester() }
    Column(Modifier.fillMaxWidth().testTag("season-browser"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            layout.sections.forEach { section ->
                TvButton("第${section.number}季",
                    Modifier.testTag("season-tab-${section.number}")
                        .focusRequester(focusAt("season-tab-${section.number}"))
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                onSeasonSelect(section.number)
                                uiScope.launch {
                                    val targetIndex = section.episodes.indexOfFirst { it.source.number == currentlyPlayingEpisode }.coerceAtLeast(0)
                                    rowState.scrollToItem(targetIndex)
                                    repeat(3) {
                                        withFrameNanos { }
                                        if (runCatching {
                                                focusAt("season-card-${section.number}-$targetIndex").requestFocus()
                                            }.isSuccess) return@launch
                                    }
                                }
                                true
                            } else event.type == KeyEventType.KeyDown &&
                                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                                onMoveToLines?.invoke() == true
                        },
                    reduced = reduced, selected = section.number == active.number,
                    onFocus = { onSeasonSelect(section.number) }) {
                    onSeasonSelect(section.number)
                }
            }
        }
        Text("第${active.number}季 · ${active.episodes.size} 集可选",
            color = Color(0xFFCAD7E6), fontSize = 16.sp)
        LazyRow(Modifier.testTag("season-cards").drawWithContent { drawContent(); DetailPlaybackMetrics.timeline.episodesDrawn() }, state = rowState,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(active.episodes,
                key = { _, entry -> "${entry.season}-${entry.number}-${entry.source.number}" }) { index, entry ->
                SeasonEpisodeCard(entry, officialEpisodes[entry.number], history(entry),
                    selected = currentlyPlayingEpisode == entry.source.number &&
                        entry.lines.any { it.id == currentlyPlayingLine },
                    reduced = reduced,
                    modifier = Modifier.testTag("season-card-${entry.season}-$index")
                        .then(if (entry.source.number == currentlyPlayingEpisode && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                        .focusRequester(focusAt("season-card-${entry.season}-$index"))
                        .focusProperties { up = focusAt("season-tab-${entry.season}") }
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> onMoveToLines?.invoke() == true
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onMoveToCast()
                                else -> false
                            }
                        },
                    onFocus = { onEpisodeFocus(entry) },
                    onClick = { onPlay(entry) })
            }
        }
    }
}

/** Full-screen live television: Left/OK reveals the channel drawer over the playing video. */
@Composable
private fun LiveTvSurface(
    channels: List<LiveChannel>,
    selected: LiveChannel,
    onChannel: (LiveChannel) -> Unit,
    favorites: LiveFavorites,
    favoriteScope: String,
    onBack: () -> Unit,
    reduced: Boolean
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var linePicker by remember { mutableStateOf<LiveChannelChoice?>(null) }
    var playerFocusEpoch by remember { zeroIntState() }
    fun closeDrawer() { linePicker = null; drawerOpen = false; playerFocusEpoch++ }
    var drawerGroup by remember { mutableStateOf<String?>(null) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var favoriteVersion by remember { zeroIntState() }
    // Manual line choices are optional. OK on a programme always starts playback
    // immediately with its first source, or a previously chosen line this session.
    val preferredLineUrls = remember(channels) { mutableStateMapOf<String, String>() }
    val groups = remember(channels) { LiveChannelDiscovery.groups(channels) }
    val visible = remember(channels, drawerGroup, favoriteOnly, searchText, favoriteVersion) {
        LiveChannelDiscovery.groupedVisible(channels, drawerGroup, favoriteOnly, searchText) {
            favorites.contains(favoriteScope, it.url)
        }
    }
    val groupRequester = remember { FocusRequester() }
    val groupListState = rememberLazyListState()
    val closeRequester = remember { FocusRequester() }
    val favoritesRequester = remember { FocusRequester() }
    val searchToggleRequester = remember { FocusRequester() }
    val searchFieldRequester = remember { FocusRequester() }
    val channelRequesters = remember(visible) { visible.map { FocusRequester() } }
    val channelListState = rememberLazyListState()
    val selectedChannelIndex = visible.indexOfFirst { choice -> choice.options.any { it.url == selected.url } }
        .takeIf { it >= 0 } ?: 0
    val channelTarget = channelRequesters.getOrNull(selectedChannelIndex)
    val selectedChoice = remember(channels, selected.url) {
        LiveChannelDiscovery.choices(channels).firstOrNull { choice ->
            choice.options.any { it.url == selected.url }
        }
    }
    LaunchedEffect(selectedChoice?.name) {
        val choice = selectedChoice
        if (choice != null && choice.first.url != selected.url) {
            val key = LiveChannelNavigation.preferenceKey(choice)
            if (preferredLineUrls[key] == null) preferredLineUrls[key] = selected.url
        }
    }
    fun zapChannel(delta: Int) {
        val next = LiveChannelNavigation.move(channels, selected.url, delta, preferredLineUrls)
        if (next != null && next.url != selected.url) onChannel(next)
    }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            val selectedGroupIndex = ((drawerGroup?.let { groups.indexOf(it) } ?: -1) + 1)
                .coerceAtLeast(0)
            groupListState.scrollToItem(selectedGroupIndex)
            if (visible.isNotEmpty()) channelListState.scrollToItem(selectedChannelIndex)
            withFrameNanos { }
            runCatching { (channelTarget ?: groupRequester).requestFocus() }
                .onFailure { runCatching { groupRequester.requestFocus() } }
        }
    }
    val firstLineRequester = remember { FocusRequester() }
    LaunchedEffect(linePicker) {
        if (linePicker != null) {
            withFrameNanos { }
            runCatching { firstLineRequester.requestFocus() }
        } else if (drawerOpen) {
            // The line subpage detaches its focused button. Returning must focus
            // an attached programme, otherwise the next TV key is lost.
            withFrameNanos { }
            runCatching { (channelTarget ?: groupRequester).requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("live-fullscreen")
        .onPreviewKeyEvent { event ->
            // Leave directional browsing untouched while the drawer is open.
            // On the full-screen focused player, Up/Down should zap programmes
            // without opening a drawer or stepping through duplicate URLs.
            val key = event.nativeKeyEvent
            if (!drawerOpen && (key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                    key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                if (event.type == KeyEventType.KeyDown && key.repeatCount == 0) {
                    zapChannel(if (key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                }
                true
            } else false
        }) {
        AuthorizedPlaybackPanel(
            media = PlaybackMedia(selected.url, alternateUris = selectedChoice?.options.orEmpty()
                .map { it.url }.filter { it != selected.url },
                allowHttpRedirects = selected.url.startsWith("http://", ignoreCase = true)),
            isLive = true,
            onLiveMenu = { drawerOpen = true },
            focusRestoreEpoch = playerFocusEpoch,
            onBack = { when {
                linePicker != null -> linePicker = null
                drawerOpen -> closeDrawer()
                else -> onBack()
            } }
        )
        if (!drawerOpen) {
            Column(Modifier.align(Alignment.BottomStart).padding(28.dp)
                .background(Color(0xA5141924), RoundedCornerShape(16.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(selected.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("live-playing-name"))
                Text("↑↓ 换台 · 左键或确定键打开频道列表", fontSize = 12.sp, color = Color(0xFFBECBD9),
                    modifier = Modifier.testTag("live-open-drawer-hint"))
            }
        } else {
            Row(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(630.dp)
                .background(Color(0xF208101B)).testTag("live-channel-drawer")) {
                Column(Modifier.width(165.dp).fillMaxHeight()
                    .background(Color(0xDD111A26)).padding(12.dp)) {
                    Text("分组", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = Color.White, modifier = Modifier.padding(9.dp))
                    LazyColumn(state = groupListState,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        item {
                            TvButton("全部频道", Modifier.testTag("live-drawer-all")
                                .focusProperties { right = channelTarget ?: closeRequester }
                                .then(if (drawerGroup == null) Modifier.focusRequester(groupRequester) else Modifier), reduced,
                                selected = drawerGroup == null) { drawerGroup = null }
                        }
                        itemsIndexed(groups) { index, group ->
                            TvButton(group.ifBlank { "未分组" },
                                Modifier.testTag("live-drawer-group-$index")
                                    .focusProperties { right = channelTarget ?: closeRequester }
                                    .then(if (group == drawerGroup) Modifier.focusRequester(groupRequester) else Modifier), reduced,
                                selected = group == drawerGroup) { drawerGroup = group }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                    Text(if (linePicker == null) "频道 · ${visible.size}" else "选择线路",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White, fontSize = 19.sp)
                        TvButton("收起", Modifier.testTag("live-drawer-close")
                            .focusProperties { left = favoritesRequester; down = searchToggleRequester }
                            .focusRequester(closeRequester), reduced, quiet = true) {
                            closeDrawer()
                        }
                    }
                    if (linePicker == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(if (favoriteOnly) "全部" else "★ 收藏",
                            Modifier.testTag("live-drawer-favorites")
                                .focusProperties {
                                    down = if (searchOpen) searchFieldRequester else channelTarget ?: groupRequester
                                }.focusRequester(favoritesRequester), reduced,
                            selected = favoriteOnly, quiet = true) { favoriteOnly = !favoriteOnly }
                        TvButton(if (searchOpen) "取消查找" else "⌕ 查找",
                            Modifier.testTag("live-drawer-search-toggle")
                                .focusProperties {
                                    up = closeRequester
                                    down = if (searchOpen) searchFieldRequester else channelTarget ?: groupRequester
                                }.focusRequester(searchToggleRequester), reduced, quiet = true) {
                            searchOpen = !searchOpen
                            if (!searchOpen) searchText = ""
                        }
                    }
                    if (searchOpen && linePicker == null) {
                        OutlinedTextField(searchText, { searchText = it.take(40) },
                            Modifier.fillMaxWidth().testTag("live-drawer-search-input")
                                .focusProperties {
                                    up = searchToggleRequester
                                    down = channelTarget ?: groupRequester
                                }.focusRequester(searchFieldRequester),
                            label = { Text("频道名称 / tvg-id") }, singleLine = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (visible.isEmpty() && linePicker == null) Text("当前筛选没有频道", color = Color(0xFFBDC8D6),
                        modifier = Modifier.testTag("live-drawer-empty"))
                    if (linePicker != null) {
                        val choice = linePicker!!
                        Text("${choice.name} · ${choice.options.size} 条可用线路", color = Color(0xFFCFD7E4))
                        TvButton("‹ 返回频道", Modifier.testTag("live-drawer-back-to-channels"), reduced) {
                            linePicker = null
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            itemsIndexed(choice.options, key = { _, line -> line.url }) { index, channel ->
                                TvButton("线路 ${index + 1}${if (selected.url == channel.url) " · 正在播放" else ""}",
                                    Modifier.testTag("live-drawer-line-$index")
                                        .then(if (index == 0) Modifier.focusRequester(firstLineRequester) else Modifier),
                                    reduced, selected = selected.url == channel.url) {
                                    preferredLineUrls[LiveChannelNavigation.preferenceKey(choice)] = channel.url
                                    onChannel(channel)
                                    closeDrawer()
                                }
                            }
                        }
                    } else LazyColumn(state = channelListState,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(visible, key = { _, choice -> choice.first.url }) { index, choice ->
                            val channel = choice.first
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TvButton("${choice.name}${if (choice.options.size > 1) " · ${choice.options.size} 线路 ›" else ""}",
                                    Modifier.weight(1f).testTag("live-drawer-channel-$index")
                                        .focusProperties {
                                            left = groupRequester
                                            if (index == 0) up = if (searchOpen) searchFieldRequester
                                                else searchToggleRequester
                                        }
                                        .focusRequester(channelRequesters[index]), reduced,
                                    selected = choice.options.any { it.url == selected.url }) {
                                    onChannel(LiveChannelNavigation.chosenLine(choice, preferredLineUrls))
                                    closeDrawer()
                                }
                                if (choice.options.size > 1) TvButton("线路",
                                    Modifier.testTag("live-drawer-line-picker-$index")
                                        .focusProperties { left = channelRequesters[index] },
                                    reduced, quiet = true) {
                                    linePicker = choice
                                }
                                TvButton(if (choice.options.any { favorites.contains(favoriteScope, it.url) }) "★" else "☆",
                                    Modifier.testTag("live-drawer-favorite-$index")
                                        .focusProperties {
                                            left = channelRequesters[index]
                                            if (index == 0) up = if (searchOpen) searchFieldRequester
                                                else searchToggleRequester
                                        }, reduced,
                                    quiet = true) {
                                    val saved = choice.options.filter { favorites.contains(favoriteScope, it.url) }
                                    if (saved.isEmpty()) favorites.toggle(favoriteScope, channel.url)
                                    else saved.forEach { favorites.toggle(favoriteScope, it.url) }
                                    favoriteVersion++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Poster(film: Film, modifier: Modifier, reduced: Boolean, onFocus: () -> Unit, onClick: () -> Unit, progress: Float? = null) {
    var focused by remember { mutableStateOf(false) }
    // Keep artwork dimensions fixed. Enlarging the whole focus target used to
    // crop neighboring cards and made TV posters appear to change size.
    val lift by animateFloatAsState(if (focused && !reduced) 2.5f else 0f,
        if (reduced) snap() else tween(105, easing = FastOutSlowInEasing),
        label = "poster-focus-lift")
    // Draw focus instantly, animate only the compositor translation;
    // do not invalidate the image/border for 190ms on every key press.
    val stroke = if (focused) Color(0xFFF6F7FA) else Color(0x665E6A78)
    val shape = RoundedCornerShape(13.dp)
    val liftPx = with(LocalDensity.current) { 1.dp.toPx() }
    Box(modifier.width(217.dp).height(116.dp)
        .graphicsLayer { translationY = -lift * liftPx }
        .onFocusChanged {
            focused = it.isFocused
            RemoteTrace.event { "POSTER ${film.id} focus=$focused" }
            if (it.isFocused) {
                RemoteFocusLatencyProbe.posterFocused(film.id)
                onFocus()
            }
        }
        .drawWithContent {
            drawContent()
            if (focused) RemoteFocusLatencyProbe.posterDrawn(film.id)
        }
        .border(if (focused) 2.dp else 1.dp, stroke, shape)
        .clip(shape).background(Color(0xFF101821))
        .onPreviewKeyEvent { event ->
            // Keep native Compose clickable as the single source of key activation.
            RemoteTrace.event { "POSTER ${film.id} key=${event.nativeKeyEvent.keyCode} type=${event.type} focus=$focused" }
            false
        }
        .clickable { RemoteTrace.event { "POSTER ${film.id} CLICK clickable" }; onClick() }) {
        // A single full-bleed image covers the entire card, including the
        // area previously occupied by an opaque 29 dp metadata footer.
        Box(Modifier.fillMaxSize().testTag("poster-image-${film.id}"),
            contentAlignment = Alignment.Center) {
            PosterArtwork(film, Modifier.fillMaxSize(), reduced)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(75.dp)
            .background(Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.40f to Color(0x66070C14),
                1.0f to Color(0xF0080D16))))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp,
                bottom = if (progress != null) 9.dp else 7.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(film.title, Modifier.weight(1f).testTag("poster-title-${film.id}"),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, maxLines = 1)
            Text(film.releaseDate?.takeIf { it.isNotBlank() } ?: film.year,
                Modifier.testTag("poster-date-${film.id}"),
                fontSize = 10.sp, color = Color(0xFFE0E5EB), maxLines = 1)
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                .background(Color(0xCC495160))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Color(0xFFDCE8FF)))
            }
        }
        FilmMetadataEnrichment.ratingLabel(film)?.let { label ->
            Text(label, Modifier.align(Alignment.TopStart).testTag("poster-rating-${film.id}")
                .padding(8.dp).background(Color(0xD91B2636), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, color = Color(0xFFFFDC9E))
        }
        if (focused) {
            Text("H", Modifier.align(Alignment.TopEnd)
                .padding(10.dp).background(Color(0xBD10141A), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
