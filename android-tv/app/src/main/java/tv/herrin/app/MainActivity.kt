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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
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
    const val WORDMARK_HOLD_MS = 1800L
    const val FADE_MS = 340
    const val TARGET_ANIMATION_BUDGET_MS = WORDMARK_HOLD_MS + FADE_MS
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
    internal fun frameSummary(): String = frameDiagnostics?.summary() ?: "渲染指标未采集"
    override fun onDestroy() {
        frameDiagnostics?.close()
        frameDiagnostics = null
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.start()
        super.onCreate(savedInstanceState)
        val startup=StartupPresentation()
        if(android.os.Build.VERSION.SDK_INT>=31) {
            splashScreen.setOnExitAnimationListener { native ->
                val icon=native.iconView
                if(icon!=null && icon.width>0) startup.wordmarkWidthDp=icon.width*.6f/resources.displayMetrics.density
                window.decorView.postOnAnimation {
                    androidx.core.view.OneShotPreDrawListener.add(window.decorView){StartupTiming.brandExit();native.remove()}
                }
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
    val foreground = if (focused) Color(0xFF151515) else Color(0xFFF5F5F5)
    val label = if (gear) "设置" else "搜索"
    Box(modifier.size(if (gear) 35.dp else 33.dp)
        .graphicsLayer { scaleX = focusScale; scaleY = focusScale }
        .semantics { contentDescription = label }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clip(shape)
        .background(if (focused) Color(0xFFF4F4F4)
            else if (selected) Color(0xFF3A3D43) else Color.Transparent)
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
                drawCircle(foreground, radius = d * 0.29f, center = centerAt,
                    style = Stroke(width = d * 0.10f))
                drawCircle(foreground, radius = d * 0.087f, center = centerAt,
                    style = Stroke(width = d * 0.075f))
                for (index in 0 until 8) {
                    val angle = (index * kotlin.math.PI / 4).toFloat()
                    val ux = kotlin.math.cos(angle)
                    val uy = kotlin.math.sin(angle)
                    drawLine(foreground,
                        Offset(centerAt.x + ux * d * 0.35f,
                            centerAt.y + uy * d * 0.35f),
                        Offset(centerAt.x + ux * d * 0.46f,
                            centerAt.y + uy * d * 0.46f),
                        strokeWidth = d * 0.095f, cap = StrokeCap.Round)
                }
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
    var lastExitDismissedAt by remember { mutableLongStateOf(0L) }
    val personalImdbRatings = remember(context) { HerrinImdbRatings(context) }
    var imdbRatingsEpoch by remember { mutableIntStateOf(0) }
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
    var liveFavoriteEpoch by remember { mutableIntStateOf(0) }
    var liveStatus by remember { mutableStateOf("请在设置中配置自己的授权 HTTPS M3U 直播列表") }
    var epgProgrammes by remember { mutableStateOf<List<LiveProgramme>>(emptyList()) }
    var epgStatus by remember { mutableStateOf("未配置 XMLTV 节目单") }
    var highlightedLiveUrl by remember { mutableStateOf<String?>(null) }
    var epgRefresh by remember { mutableIntStateOf(0) }
    var liveClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var liveRefresh by remember { mutableIntStateOf(0) }
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
    var personalSourceConfigEpoch by remember { mutableIntStateOf(0) }
    var nasDavUrlDraft by remember { mutableStateOf(prefs.nasWebDavUrl) }
    var cloudDavUrlDraft by remember { mutableStateOf(prefs.cloudWebDavUrl) }
    var nasDavUserDraft by remember { mutableStateOf("") }
    var nasDavPasswordDraft by remember { mutableStateOf("") }
    var cloudDavUserDraft by remember { mutableStateOf("") }
    var cloudDavPasswordDraft by remember { mutableStateOf("") }
    var personalSourceFeedback by remember { mutableStateOf("") }
    val tmdbState=remember { TmdbScreenState() }
    var providerFilms by remember { mutableStateOf<List<Film>?>(null) }
    var providerStatus by remember { mutableStateOf("当前使用离线演示目录") }
    var filmId by rememberSaveable { mutableStateOf(demoFilms.first().id) }
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
    val tvBoxRepository = remember(context) { TvBoxConfigRepository(context) }
    val tvBoxVodRepo = remember { TvBoxVodRepository() }
    val spiderBridge = remember(context) { TvBoxSpiderBridge(context) }
    DisposableEffect(spiderBridge) { onDispose { spiderBridge.close() } }
    // Applying a source is the explicit user action that permits its Spider.
    // On subsequent launches, auto-run only the exact previously accepted JAR+MD5.
    var tvBoxRunSpiderAfterApply by remember { mutableStateOf(false) }
    var tvBoxColdStartRequest by remember { mutableStateOf(false) }
    var spiderActive by remember { mutableStateOf(false) }
    var spiderStatus by remember { mutableStateOf("尚未选择 Spider 站点") }
    var playerRetryEpoch by remember { mutableIntStateOf(0) }
    var spiderResolved by remember { mutableStateOf<PlaybackResult?>(null) }
    var spiderResolvedFor by remember { mutableStateOf<HerrinAutomaticPlayback.Attempt?>(null) }
    var tvBoxVodScope by remember { mutableStateOf<String?>(null) }
    var activeCmsSite by remember { mutableStateOf<TvBoxSite?>(null) }
    var cmsDetailStatus by remember { mutableStateOf("") }
    var detailLoadEpoch by remember { mutableIntStateOf(0) }
    // Only the selected CMS title may resolve its missing episodes inside
    // the regular player loading surface. No catalog-wide eager scans.
    var cmsPlaybackPendingId by remember { mutableStateOf<String?>(null) }
    var cmsPlaybackFailedId by remember { mutableStateOf<String?>(null) }
    var tvBoxUrlDraft by rememberSaveable { mutableStateOf(prefs.tvBoxWarehouseUrl) }
    // Large pasted backups should never be marshalled into an Activity saved-state Bundle.
    var tvBoxJsonDraft by remember { mutableStateOf("") }
    var tvBoxLiveUrlDraft by rememberSaveable { mutableStateOf(prefs.tvBoxLiveSourceUrl) }
    // HTTP metadata/playlist access is enabled by default; plugin execution is
    // limited to a user-applied source or a previously pinned JAR fingerprint.
    var tvBoxAllowHttp by remember { mutableStateOf(true) }
    var tvBoxRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var tvBoxImportJsonRequest by remember { mutableStateOf<String?>(null) }
    var tvBoxLoadEpoch by remember { mutableIntStateOf(0) }
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
        for(id in tmdbState.tmdbResolved.keys.toList())tmdbState.tmdbResolved[id]?.let{old->
            tmdbState.tmdbResolved[id]=old.copy(playbackEpisodes=emptyList(),cmsVodId=null,spiderVodId=null)
        }
    }
    LaunchedEffect(Unit) {
        if (configuredTvBoxUrl.isNotBlank()) {
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
        val runSpiderAfterApply = tvBoxRunSpiderAfterApply
        val preferCache = tvBoxColdStartRequest
        tvBoxColdStartRequest = false
        tvBoxRunSpiderAfterApply = false
        tvBoxStatus = "正在读取 TVBox 配置…"
        vodImportFeedback = "读取中…"
        spiderActive = false
        activeCmsSite = null
        spiderBridge.close()
        tvBoxSingle = null
        tvBoxShowAllSites = false
        tvBoxHerrinCatalog = null
        val imported = tvBoxImportJsonRequest
        tvBoxImportJsonRequest = null
        val loadedConfig = if (imported != null)
            tvBoxRepository.importJson(imported, address, tvBoxAllowHttp)
            else tvBoxRepository.load(address, tvBoxAllowHttp, preferCached = preferCache)
        when (val result = loadedConfig) {
            is TvBoxConfig.Warehouses -> {
                tvBoxWarehouses = result.entries
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
        if (isRoot && (tvBoxSingle != null || tvBoxWarehouses.isNotEmpty())) {
            if (prefs.tvBoxWarehouseUrl != address) {
                // A different root can never inherit approval of the old binary.
                prefs.tvBoxTrustedSpiderRootUrl = ""
                prefs.tvBoxTrustedSpiderJarSpec = ""
            }
            prefs.tvBoxWarehouseUrl = address
            configuredTvBoxUrl = address
        }
        // Cache-first on startup, refresh in the background for the next
        // launch. Never replace the active browsing state mid-remote action.
        if (preferCache && ((loadedConfig is TvBoxConfig.Single && loadedConfig.fromCache) ||
                (loadedConfig is TvBoxConfig.Warehouses && loadedConfig.fromCache))) {
            launch(Dispatchers.IO) { tvBoxRepository.load(address, tvBoxAllowHttp) }
        }
        if (settingsTvBoxOnly && tvBoxSingle != null) {
            // Import must activate an actual catalog, not just display 49 names
            // under Settings while the home page stays on the demo collection.
            val config = tvBoxSingle!!
            val savedSite = prefs.tvBoxLastWorkingSiteId.takeIf {
                prefs.tvBoxLastWorkingRootUrl == prefs.tvBoxWarehouseUrl
            }.orEmpty()
            // TVBox-style import must not probe all third-party plugins.
            // Restore at most the previously selected site on startup.
            val candidates = TvBoxAutoSites.resumeOnly(config, savedSite)
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
                val elapsed = SystemClock.elapsedRealtime() - scanStarted
                if (elapsed >= TvBoxAutoScanPolicy.TOTAL_BUDGET_MS) {
                    scanBudgetExceeded = true
                    break
                }
                val timeout = TvBoxAutoScanPolicy.timeoutMs(
                    elapsed, cmsElapsed, hasRunnableSpider, spider = site.spiderApi != null || site.scriptApi != null)
                    ?: continue
                if (site.apiUrl != null) {
                    vodImportFeedback = "探测 ${candidateIndex + 1}/${candidates.size}"
                    tvBoxStatus = "正在试读 ${site.name}（${candidateIndex + 1}/${candidates.size}）…"
                    val cmsStart = SystemClock.elapsedRealtime()
                    val loaded = withTimeoutOrNull(timeout) {
                        tvBoxVodRepo.load(site, allowHttp = true)
                    }
                    cmsElapsed += SystemClock.elapsedRealtime() - cmsStart
                    when (loaded) {
                        is TvBoxVodResult.Ready -> if (loaded.films.isNotEmpty()) {
                            chosen = site to loaded.films
                            break
                        }
                        else -> Unit
                    }
                } else if (site.spiderApi != null || site.scriptApi != null) {
                    val jarSpec = site.jar ?: config.spiderJar ?: site.scriptApi ?: continue
                    // No silent execution of a changed or newly introduced JAR on startup.
                    if (!TvBoxSpiderPolicy.mayRun(runSpiderAfterApply, prefs.tvBoxWarehouseUrl,
                            jarSpec, prefs.tvBoxTrustedSpiderRootUrl,
                            prefs.tvBoxTrustedSpiderJarSpec)) continue
                    vodImportFeedback = "探测 ${candidateIndex + 1}/${candidates.size}"
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
                        is SpiderResult.Ready -> if (result.value.isNotEmpty()) {
                            chosen = site to result.value
                            chosenSpider = true
                            prefs.tvBoxTrustedSpiderRootUrl = prefs.tvBoxWarehouseUrl
                            prefs.tvBoxTrustedSpiderJarSpec = jarSpec
                            break
                        }
                        null -> spiderFailure = "Spider ${site.name} 响应超时"
                    }
                }
            }
            if (chosen != null) {
                prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(chosen.first, config.spiderJar)
            }
            if (chosen == null && !spiderCrashed &&
                SystemClock.elapsedRealtime() - scanStarted >= TvBoxAutoScanPolicy.TOTAL_BUDGET_MS)
                scanBudgetExceeded = true
            if (chosen != null) {
                spiderActive = chosenSpider
                activeCmsSite = if (chosenSpider) null else chosen.first
                providerFilms = chosen.second
                providerStatus = "TVBox ${chosen.first.name} · ${chosen.second.size} 部"
                tvBoxVodScope = if (chosenSpider) "spider:${chosen.first.key}:${chosen.first.jar ?: config.spiderJar}"
                    else "tvbox:${chosen.first.apiUrl}"
                filmId = chosen.second.first().id
                tvBoxStatus += "；已自动导入 ${chosen.first.name}，首页已切换到影视源内容"
                vodImportFeedback = "成功"
            } else {
                providerFilms = null
                activeCmsSite = null
                tvBoxVodScope = "tvbox:pending:$address"
                providerStatus = "已导入 ${config.sites.size} 个影视点；选择影视点后才加载影片目录"
                if (spiderCrashed) {
                    tvBoxStatus = "配置读取成功，但 Spider 子进程异常退出。已停止自动重试以保护电视；可打开「选择影视点」尝试其他站点。"
                    vodImportFeedback = "插件崩溃"
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
        if (tvBoxSingle == null) vodImportFeedback = "导入失败"
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
    val tmdbPrimary = prefs.tmdbReadToken.isNotBlank()
    val extraNasConfigured = remember(personalSourceConfigEpoch) { AdditionalServiceConfigs(context).enabled("nas").isNotEmpty() }
    val sourceConfigured = extraNasConfigured || tmdbPrimary || configuredTvBoxUrl.isNotBlank() || tmdbState.tmdbHomeFilms.isNotEmpty() ||
        prefs.nasWebDavUrl.isNotBlank() || prefs.cloudWebDavUrl.isNotBlank()
    var recentIndexEpoch by remember { mutableIntStateOf(0) }
    var recentIndexFilms by remember { mutableStateOf<List<Film>>(emptyList()) }
    LaunchedEffect(recentIndexEpoch) { recentIndexFilms = withContext(Dispatchers.IO) { watchHistory.indexedFilms() } }
    val resolvedSnapshot = tmdbState.tmdbResolved.toMap()
    val catalogFilms = remember(tmdbPrimary, tmdbState.tmdbHomeFilms, tmdbState.tmdbTop250Films, resolvedSnapshot, providerFilms, sourceConfigured) { if (tmdbPrimary) {
        (tmdbState.tmdbHomeFilms + tmdbState.tmdbTop250Films).distinctBy(Film::id)
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
    val playbackScene = screen == "player" || screen == "live-player"
    LaunchedEffect(playbackScene, startup.showing) {
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        // Only scene boundaries change hints; episode/line changes never toggle the display mode.
        if (playbackScene) activity.releaseUiRefreshPreference()
        else if(!startup.showing) activity.preferSmoothTvUi()
    }
    // Media rekeys (refresh, episode and line) keep their active source. Only
    // leaving the player releases its Spider resources and next play rehydrates.
    DisposableEffect(screen == "player", spiderActive) {
        val ownsPlayback = screen == "player" && spiderActive
        onDispose { if (ownsPlayback) spiderBridge.releasePlayback() }
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
    var detailSelectedSeason by rememberSaveable { mutableIntStateOf(0) }
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
    var metadataRescanEpoch by remember { mutableIntStateOf(0) }
    var tmdbConnectivity by remember { mutableStateOf(TmdbProbeState.NOT_CONFIGURED) }
    var tmdbProbeMessage by remember { mutableStateOf(TmdbProbeState.NOT_CONFIGURED.text) }
    // Must not be tied to metadataRescanEpoch: a successful probe increments
    // that epoch, which otherwise causes an endless configuration/search loop.
    var tmdbProbeEpoch by remember { mutableIntStateOf(0) }
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
    val tmdbDiscovery = remember(context) { HerrinTmdbDiscovery(fetcher = MetadataFetchers.current, cacheDir = java.io.File(context.filesDir, "tmdb-home-pages")) }
    LaunchedEffect(metadataToken) {
        CrashJournal.stage("CACHE")
        tmdbState.tmdbHomeFilms = emptyList()
        tmdbState.tmdbTop250Films = emptyList()
        if (metadataToken.isNotBlank()) {
            val cached = withContext(Dispatchers.IO) { tmdbDiscovery.cachedHome(metadataToken) }
            tmdbState.tmdbHomeFilms = cached
            tmdbState.tmdbTop250Films = withContext(Dispatchers.IO) { tmdbDiscovery.cachedTop250(metadataToken) }
        }
    }
    // TMDB is the authoritative discovery index. No video-site homepage is
    // consulted to build the hero, ratings or poster rails.
    LaunchedEffect(metadataToken, tmdbConnectivity, startup.networkReady) {
        if(!startup.networkReady)return@LaunchedEffect
        if (tmdbConnectivity != TmdbProbeState.CONNECTED || metadataToken.isBlank()) return@LaunchedEffect
        tmdbState.tmdbDiscoveryStatus = "正在加载 TMDB 热播影片与类型推荐…"
        val keys = listOf("trending-movies", "trending-tv", "new-movies",
            "on-air-tv", "top250", "sci-fi", "action", "mystery", "animation", "variety", "documentary")
        CrashJournal.stage("DISCOVERY")
        val loaded = mutableListOf<Film>()
        for (key in keys) {
            val section = tmdbDiscovery.sections.first { it.key == key }
            val shelf = try { tmdbDiscovery.load(section, metadataToken) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: java.io.IOException) { null }
            if (shelf != null) {
                loaded += shelf.films
                // Top Rated is already loaded as a home recommendation.
                // Reuse its valid first page immediately in Top250 instead
                // of showing an empty tab until a separate 13-page fetch.
                if (key == "top250" && shelf.films.isNotEmpty() &&
                    tmdbState.tmdbTop250Films.isEmpty()) {
                    tmdbState.tmdbTop250Films = shelf.films
                    tmdbState.tmdbTop250Status = "已读取高分电影 ${shelf.films.size}/250 部，打开榜单可继续加载"
                }
            }
        }
        if (loaded.isNotEmpty()) {
            tmdbState.tmdbHomeFilms = loaded.distinctBy(Film::id)
            // Discovery publication must not steal selection or focus.
            tmdbState.tmdbDiscoveryStatus = "TMDB 已加载 ${tmdbState.tmdbHomeFilms.size} 部作品；播放时优先检索 NAS／云盘"
        } else {
            tmdbState.tmdbDiscoveryStatus = "TMDB 未返回新资料；保留首页缓存，可重试联网"
        }
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
    // A TMDB detail starts a REAL per-title resource lookup. This never uses
    // the scraped poster/title as an executable source or auto-plays a video.
    val lookupFilm=remember(filmId,tmdbState.tmdbHomeFilms,tmdbState.tmdbTop250Films,recentIndexFilms){
        (tmdbState.tmdbHomeFilms+tmdbState.tmdbTop250Films+recentIndexFilms).firstOrNull{it.id==filmId}
    }
    LaunchedEffect(screen, filmId, lookupFilm?.title,lookupFilm?.subtitle,lookupFilm?.year,lookupFilm?.tmdbTvId,tvBoxSingle,
        tvBoxWarehouses, personalSourceConfigEpoch, tmdbState.tmdbLookupEpoch) {
        val searchTicket=tmdbState.tmdbSearchGeneration.incrementAndGet()
        val consentAtStart=spiderBridge.configurationTicket()
        tmdbState.tmdbSearchRunning=false
        val recoveryRequest = tmdbState.automaticSearch?.takeIf { it.film == filmId && screen == "player" }
        if ((screen != "detail" && recoveryRequest == null) || !filmId.startsWith("tmdb-") ||
            (tmdbState.tmdbResolved[filmId]?.playbackEpisodes?.isNotEmpty() == true &&
                tmdbState.tmdbPlaybackRoutes.usable(filmId,spiderBridge.approvedActiveSiteKey())!=null)) return@LaunchedEffect
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
        if(!extended){tmdbState.tmdbSearchPlans.reset(targetId);tmdbState.tmdbSpiderBatchOffsets.remove(targetId);tmdbState.tmdbSpiderBatchCompleted.remove(targetId)}
        val budgetMs=if(extended)SourceSearchPolicy.EXTENDED_MS else SourceSearchPolicy.FOREGROUND_MS
        val lookupEpoch=tmdbState.tmdbLookupEpoch
        val configEpoch=personalSourceConfigEpoch
        tmdbState.tmdbStatusFilm=targetId
        tmdbState.tmdbSearchRunning=true;tmdbState.tmdbStageStarted=System.nanoTime()
        SourceSearchDiagnostics.begin()
        tmdbState.tmdbSourceStatus = "正在快速检索 NAS／云盘及已授权影视点…"
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
        val cloudUrl = prefs.cloudWebDavUrl
        val allowNasHttp = prefs.nasAllowHttp
        val allowCloudHttp = prefs.cloudAllowHttp
        var health:SourceHealthIndex?=null
        var planRevision=""
        var priorNodes=0
        var completedNodesNow=0
        var totalNodesNow=0
        suspend fun sourceEvent(event:SourceSearchEvent){
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate){
                if(searchTicket==tmdbState.tmdbSearchGeneration.get() &&filmId==targetId &&tmdbState.tmdbLookupEpoch==lookupEpoch &&personalSourceConfigEpoch==configEpoch &&tvBoxSingle===currentTvbox){
                    SourceSearchDiagnostics.record(event)
                    if(event.total>0)totalNodesNow=maxOf(totalNodesNow,event.total)
                    if(event.nodeComplete){
                        completedNodesNow=maxOf(completedNodesNow,event.completed)
                        tmdbState.tmdbSearchPlans.complete(targetId,planRevision,event.source)
                        tmdbState.tmdbSourceStatus=tmdbState.tmdbSourceStatus.substringBefore(" · 已检查")+" · 已检查 ${priorNodes+event.completed}/${priorNodes+event.total}"
                        return@withContext
                    }
                    if(event.outcome==SourceSearchOutcome.CHECKING)tmdbState.tmdbStageStarted=System.nanoTime()
                    tmdbState.tmdbSourceStatus="${event.stage.label()} · ${event.outcome.label()}"+
                        (if(totalNodesNow>0)" · 已检查 ${priorNodes+completedNodesNow}/${priorNodes+totalNodesNow}" else "")+" · 本阶段已用 ${event.elapsedMs}ms"
                }
            }
        }
        val searchOutput=try { withTimeoutOrNull(budgetMs) { withContext(Dispatchers.IO+SpiderSearchAuthority(consentAtStart)) {
            val adapters = mutableListOf<HerrinFilmSource>()
            for ((slot, url, insecure, kind) in listOf(
                listOf("nas", nasUrl, allowNasHttp.toString(), "NAS"),
                listOf("cloud", cloudUrl, allowCloudHttp.toString(), "CLOUD")
            )) {
                if (url.isBlank()) continue
                val login = HerrinPersonalCredentialStore(context, slot).load()
                val account = HerrinWebDavAccount(
                    if (slot == "nas") "NAS" else "云盘", url,
                    login.first, login.second,
                    if (kind == "NAS") HerrinSourceType.NAS else HerrinSourceType.CLOUD,
                    insecure.toBoolean()
                )
                runCatching { HerrinWebDavSource(account) }.getOrNull()?.let(adapters::add)
            }
            AdditionalServiceConfigs(context).nasAccounts().forEach { account ->
                runCatching { HerrinWebDavSource(account) }.getOrNull()?.let(adapters::add)
            }
            val directSites = mutableListOf<TvBoxSite>()
            directSites += currentTvbox?.sites?.filter { it.apiUrl != null }.orEmpty().take(100)
            // Metadata cache first; explicit continuation alone may refresh missing warehouse data.
            val scope=SourceHealthIndex.digest(listOf(approvedRoot,trustedSpiderRoot,trustedSpiderJar,currentTvbox.toString(),nasUrl,cloudUrl,
                AdditionalServiceConfigs(context).enabled("nas").toString(),importedWarehouses.toString(),
                context.getSharedPreferences("herrin_personal_secrets",0).getString("_revision","legacy").orEmpty()).joinToString("|"))
            health=SourceHealthIndex(java.io.File(context.filesDir,"source-health-v1.json"),scope)
            val index=requireNotNull(health)
            planRevision=scope+SourceHealthIndex.digest(searchIdentity.toString())
            val (plan,replaced)=tmdbState.tmdbSearchPlans.get(targetId,planRevision,currentTvbox?.sites.orEmpty(),index::rank)
            if(replaced){spiderPageOffset=0;previousCompleted=0}
            val completedNodes=tmdbState.tmdbSearchPlans.completed(targetId,planRevision)
            priorNodes=completedNodes.size
            // Multiwarehouse configs are loaded as data (no JAR/JS execution).
            // Only user-imported root warehouse URLs are eligible for bounded
            // lookup; cap fan-out so a TV does not stall or scan the internet.
            for (warehouse in importedWarehouses) {
                if (directSites.size >= 100) break
                when (val nested = tvBoxRepository.forSearch(warehouse.url, tvBoxAllowHttp, allowNetwork = extended)) {
                    is TvBoxConfig.Single ->
                        directSites += nested.sites.filter { it.apiUrl != null }.take(100-directSites.size)
                    else -> Unit
                }
            }
            directSites.distinctBy { it.apiUrl }.take(100).forEach { site ->
                adapters += HerrinTvBoxCmsSource(site, tvBoxVodRepo, tvBoxAllowHttp)
            }
            // Java nodes use one serialized search batch; an explicitly active script keeps its own authorization.
            approvedSpiderSite?.takeIf{it.scriptApi!=null}?.let{adapters+=HerrinTvBoxSpiderSource(it,spiderBridge)}

            val personals = adapters.filterIsInstance<HerrinWebDavSource>().associateBy { it.id }
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
                adapters.filterNot { it.id in tmdbState.tmdbFallbackIndex.rejected(targetId) ||it.id in completedNodes }
            ).find(
                searchIdentity,
                personalPlayback = { offer ->
                    val result=personals[offer.sourceId]?.playback(offer)
                        ?: PlaybackResult.Unavailable("该个人媒体来源未授权")
                    (result as? PlaybackResult.Ready)?.media?.let{personalUris.add(it.uri)}
                    result
                },
                streaming=true,health=index,onEvent=::sourceEvent,
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
                        if (passed) "MEDIA_BYTES_VERIFIED" else "MEDIA_BYTES_REJECTED")
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
                    searchIdentity, plan.sites,
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
                    maxWallClockMs = budgetMs,
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
                                "$visited/${progress.eligible}，继续寻找可播放线路…"
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
                            else "${progress.stage?.label().orEmpty()} · 已检查 $visited/${progress.eligible}；"+
                                (progress.activeIndex?.let{"第 $it 个节点检查中；"}?:"")+"最近结果：$outcome · ${progress.elapsedMs}ms"
                        }
                    },
                    session = object : HerrinApprovedSpiderBatch.Session {
                        override suspend fun eligible(site:TvBoxSite)=index.eligible("spider:"+site.key)
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
        if(searchOutput==null){
            tmdbState.tmdbSourceStatus="前台搜索预算结束（${budgetMs/1000}秒）；尚无已验证线路，可继续搜索剩余节点"
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
            else "NAS／云盘／TVBox 均未找到可播放的授权选集；可重新检索"
            return@LaunchedEffect
        }
        tmdbState.tmdbFallbackIndex.select(targetId, found.sourceId)
        HerrinPlaybackDiagnosticJournal.record("SOURCE", "VERIFIED_SOURCE_SELECTED")
        when (val adapter = sources.firstOrNull { it.id == found.sourceId }) {
            is HerrinWebDavSource -> {
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
            metadataToken.isBlank() || tmdbConnectivity != TmdbProbeState.CONNECTED)
            return@LaunchedEffect
        val filmToEnrich = (tmdbState.tmdbHomeFilms + tmdbState.tmdbTop250Films).firstOrNull { it.id == filmId }
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
    var metadataScanDone by remember { mutableIntStateOf(0) }
    var metadataScanTotal by remember { mutableIntStateOf(0) }
    var metadataScanNoMatch by remember { mutableIntStateOf(0) }
    var metadataScanFailures by remember { mutableIntStateOf(0) }
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
            tmdbState.tmdbHomeFilms.isNotEmpty()) return@LaunchedEffect
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
            tmdbState.tmdbHomeFilms.isNotEmpty()) return@LaunchedEffect
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
    val railStates = (listOf("首页", "电影", "剧集", "动漫", "综艺", "纪录片", "Top 250", "直播", "我的", "search", "recommend")).associateWith { rememberLazyListState() }
    val browseScrollStates = railStates.keys.associateWith { rememberScrollState() }
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
    val film = films.firstOrNull { it.id == filmId } ?: films.firstOrNull() ?: demoFilms.first()
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
    val categoryHeroFilms = remember(collapsedCatalog, category, tmdbState.tmdbTop250Films) {
        BrowseHeroSelection.candidates(category, collapsedCatalog, tmdbState.tmdbTop250Films)
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
    fun displayKey(item: Film): String = TvFocusRoutes.posterKey(keyCategory, keysInBrowse, item.id, showCatalog.groupFor(item.id)?.key)
    // Playback-specific, observed Media3 track formats. Never sourced from a movie title or TMDB.
    val verifiedStreams = remember { mutableStateMapOf<String, VerifiedStreamSpec>() }
    LaunchedEffect(screen, filmId, spiderActive, showGroup?.key, detailLoadEpoch) {
        if (spiderActive && screen == "detail" && !film.id.startsWith("tmdb-") &&film.spiderVodId != null) {
            val selectionConsent=spiderBridge.configurationTicket()
            val current=withContext(Dispatchers.IO){spiderBridge.isApprovedSourceFilm(film)}
            if(current &&film.playbackEpisodes.isNotEmpty())return@LaunchedEffect
            spiderStatus = "正在读取当前授权来源的详情和选集…"
            val loaded=if(current)spiderBridge.details(film) else {
                val refreshed=withTimeoutOrNull(6_000){withContext(Dispatchers.IO+SpiderRequestBudget(1_500)+SpiderSearchAuthority(selectionConsent)){
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
        if (showGroup == null && !spiderActive && screen == "detail" && site != null &&
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
        if (screen != "detail" || selected == null || providerFilms == null) return@LaunchedEffect
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
        screen=="player" &&film.id.startsWith("tmdb-") &&tmdbState.tmdbPlaybackRoutes.forFilm(film.id)!=null &&
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
        spiderResolved = null
        spiderResolvedFor = HerrinAutomaticPlayback.Attempt(film.id, episode, line, playerRetryEpoch,
            tmdbState.tmdbFallbackIndex.selected(film.id) ?: catalogScope)
        if (spiderActive && screen == "player" && tmdbState.automaticSearch == null &&!playbackNeedsRefresh) {
            val selectionConsent=spiderBridge.configurationTicket()
            val sourceFilm = if (film.id.startsWith("tmdb-")) tmdbState.tmdbPlaybackRoutes.sourceFilm(film.id) else film
            if (sourceFilm == null) {
                spiderResolved = PlaybackResult.Unavailable("影片来源已失效，请重新查找片源")
                return@LaunchedEffect
            }
            var playableFilm: Film = sourceFilm
            if(!withContext(Dispatchers.IO){spiderBridge.isApprovedSourceFilm(playableFilm)}){
                val fresh=withTimeoutOrNull(6_000){withContext(Dispatchers.IO+SpiderRequestBudget(1_500)+SpiderSearchAuthority(selectionConsent)){
                    refreshSpiderSelection(playableFilm,tvBoxSingle,spiderBridge){site->
                        TvBoxSpiderPolicy.mayRun(false,prefs.tvBoxWarehouseUrl,site.jar?:tvBoxSingle?.spiderJar?:site.scriptApi.orEmpty(),
                            prefs.tvBoxTrustedSpiderRootUrl,prefs.tvBoxTrustedSpiderJarSpec)
                    }
                }}
                if(fresh==null ||fresh.playbackEpisodes.none{ep->ep.number==episode &&ep.lines.any{it.id==line}}){
                    spiderResolved=PlaybackResult.Unavailable("来源会话或选集已变化，请返回详情重新选择")
                    return@LaunchedEffect
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
                            return@LaunchedEffect
                        }
                        // Episode/line state changes restart this effect with the
                        // newly loaded Film, avoiding a double playerContent call.
                        if (episode != first.number || line != option.id) {
                            episode = first.number
                            line = option.id
                            return@LaunchedEffect
                        }
                    }
                    is SpiderResult.Failure -> {
                        spiderResolved = PlaybackResult.Unavailable(detailed.message)
                        return@LaunchedEffect
                    }
                }
            }
            spiderResolved = spiderBridge.play(playableFilm, episode, line, tvBoxAllowHttp)
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
                        SpiderExitDiagnostics.recent(context)?.let{"系统原因：$it"}).joinToString("；")
                }
                exitDetail.takeIf{it.isNotBlank()}?.let { reason ->
                    if (spiderStatus == failedStatus) spiderStatus =
                        "$failedStatus；$reason"
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
    fun moveCompactSettingsFocus(target: String) {
        val itemIndex = TvFocusRoutes.compactSettingsItemIndex(
            target,
            sourceStatus = vodImportFeedback.isNotBlank(),
            connectedStatus = metadataToken.isNotBlank(),
            failedStatus = metadataScanFailures > 0 && metadataScanDone >= metadataScanTotal &&
                tmdbConnectivity == TmdbProbeState.CONNECTED,
            noMatchesStatus = tmdbConnectivity == TmdbProbeState.CONNECTED &&
                metadataScanTotal > 0 && metadataScanDone >= metadataScanTotal &&
                metadataScanFailures == 0 &&
                providerFilms.orEmpty().none { it.metadataSource == "TMDB" }
        ) ?: return
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
    var recentPlaybackReturn by remember { mutableStateOf<PlaybackReturnContext?>(null) }
    var pendingHistoryFilm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(screen, pendingHistoryFilm, film.id, film.playbackEpisodes, catalogScope) {
        if (screen != "detail") {
            pendingHistoryFilm = null
            if (screen != "player") recentPlaybackReturn = null
            return@LaunchedEffect
        }
        if (pendingHistoryFilm != film.id || film.playbackEpisodes.isEmpty()) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { watchHistory.resolveIndexed(film, catalogScope) }
        if (saved != null) {
            episode = saved.first.episode; line = saved.first.line
            seasonLayout?.sections?.firstOrNull { section -> section.episodes.any { it.source.number == episode } }?.let {
                detailSelectedSeason = it.number
                detailSeasonEpisodeNumbers[it.number] = episode
                detailSeasonLines[it.number] = line
            }
            tmdbState.automaticResume = HerrinAutomaticPlayback.Resume(film.id, episode, line, saved.second)
            if (recentPlaybackReturn != null) screen = "player"
        }
        pendingHistoryFilm = null
    }
    fun details(f: Film) {
        recentPlaybackReturn = null
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
        // The user chooses a film first, then its real episode and source.
        // Never start playback merely because a poster was selected.
        details(item)
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
    fun playSeasonEpisode(entry: SeasonEpisode, preferredLineId: String? = detailSeasonLines[entry.season]) {
        val chosen = entry.preferredLine(preferredLineId)
        val request = SeasonPlayback.target(film, entry, chosen)
        detailSelectedSeason = entry.season
        detailSeasonEpisodeNumbers[entry.season] = entry.source.number
        detailSeasonLines[entry.season] = chosen.id
        episode = request.episode
        line = request.line
        restoreSeasonFocus = true
        screen = "player"
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
            RemoteTrace.event { "IME handoff search=$leavingSearchEditor settings=$leavingSettingsEditor acceptingText=${inputMethod.isAcceptingText} nativeFocus=${view.hasFocus()}" }
        }
    }
    var lastNonPlayback by remember { mutableStateOf(PlaybackReturnContext("detail", category, focusId, filmId)) }
    var playbackReturn by remember { mutableStateOf(lastNonPlayback) }
    SideEffect {
        if (screen != "player") lastNonPlayback = PlaybackReturnContext(screen, category, focusId, filmId)
    }
    LaunchedEffect(screen) {
        if (screen == "player") {
            playbackReturn = recentPlaybackReturn ?: lastNonPlayback
            recentPlaybackReturn = null
        } else recentIndexEpoch++
    }
    fun back() {
        when (screen) {
            "player" -> {
                restoreGroupFocus = showGroup != null
                restoreSeasonFocus = showGroup == null && seasonLayout != null
                category = playbackReturn.category
                focusId = playbackReturn.focus
                if (films.any { it.id == playbackReturn.film }) filmId = playbackReturn.film
                screen = playbackReturn.screen
            }
            "live-player" -> { category = "首页"; focusId = "nav-首页"; screen = "browse" }
            "detail" -> { bypassGroupFilmId = null; screen = returnScreen }
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
            else -> back()
        }
    }
    LaunchedEffect(screen, splash, category) {
        CrashJournal.location(screen, category)
        RemoteTrace.event { "ROUTE screen=$screen category=$category focusId=$focusId" }
        if (!splash) {
            // Request only attached controls; returning to a rail first composes its remembered scroll position.
            withFrameNanos { }
            val id = when (screen) {
                "browse" -> pendingToolbarFocus ?: focusId
                "search" -> focusId
                "facets" -> if (focusId.startsWith("facet-result-")) focusId else "screen-first"
                else -> "screen-first"
            }
            // A lazy search result attaches after its parent. Its item effect restores focus below.
            if (screen == "search" && id.startsWith("card-") &&
                showCatalog.search(query, searchCategory, searchNewestFirst).any {
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
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        // Compose the lightweight home structure behind the fading logo, but
        // defer the large image decode until the logo is completely gone.
        // On resource-limited TVs this avoids competing with first-frame UI.
        if (!splash && screen != "player" && screen != "live-player" &&
            (!sourceConfigured || films.isNotEmpty())) {
            HeroArtworkTransition(backdropFilm.takeIf { it.id == displayFilm.id } ?: displayFilm,
                reduced = reduceMotion, modifier = Modifier.fillMaxSize())
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
                            when {
                                site.apiUrl != null -> uiScope.launch {
                                    tvBoxStatus = "正在读取 ${site.name}…"
                                    when (val result = tvBoxVodRepo.load(site, true)) {
                                        is TvBoxVodResult.Error -> tvBoxStatus = "${site.name}：${result.reason}"
                                        is TvBoxVodResult.Ready -> {
                                            spiderActive = false
                                            activeCmsSite = site
                                            prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                                            prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, selected?.spiderJar)
                                            providerFilms = result.films
                                            providerStatus = "TVBox ${site.name} · ${result.films.size} 部"
                                            tvBoxVodScope = "tvbox:${site.apiUrl}"
                                            filmId = result.films.first().id
                                            category = "首页"
                                            screen = "browse"
                                            tvBoxStatus = "已导入 ${site.name} 的影片目录"
                                        }
                                    }
                                }
                                (site.spiderApi != null || site.scriptApi != null) && selected != null -> uiScope.launch {
                                    tvBoxStatus = "正在通过 ${site.name} 加载影片…"
                                    when (val result = spiderBridge.browse(site, selected)) {
                                        is SpiderResult.Failure -> tvBoxStatus = "${site.name}：${result.message}"
                                        is SpiderResult.Ready -> {
                                            spiderActive = true
                                            activeCmsSite = null
                                            prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                                            prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, selected.spiderJar)
                                            providerFilms = result.value
                                            providerStatus = "TVBox ${site.name} · ${result.value.size} 部"
                                            tvBoxVodScope = "spider:${site.key}:${selected.spiderJar}"
                                            filmId = result.value.first().id
                                            prefs.tvBoxTrustedSpiderRootUrl = prefs.tvBoxWarehouseUrl
                                            prefs.tvBoxTrustedSpiderJarSpec = site.jar ?: selected.spiderJar.orEmpty()
                                            category = "首页"
                                            screen = "browse"
                                            tvBoxStatus = "已导入 ${site.name} 的影片目录"
                                        }
                                    }
                                }
                                site.scriptApi != null -> tvBoxStatus = "${site.name} 属于 TVBox JS 规则，当前尚未集成对应运行时，请选择 Spider 站点"
                                else -> tvBoxStatus = "${site.name} 使用尚不支持的插件格式，无法直接解析"
                            }
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
                // Focus color animations can recompose every frame: searching,
                // regrouping and reading playback history must NOT run per frame.
                val filtered = remember(showCatalog, films, category, isSearch,
                    query, searchCategory, searchNewestFirst, favorites, searchFavoritesOnly,
                    facetSelection, tmdbState.tmdbTop250Films, screen, catalogScope,myRecent) {
                    if (isSearch) {
                        val queried = showCatalog.search(query, searchCategory, searchNewestFirst)
                        val favoritesOnly = if (searchFavoritesOnly) {
                            val liked = showCatalog.favorites(favorites).map { it.id }.toSet()
                            queried.filter { it.id in liked }
                        } else queried
                        showCatalog.collapse(FilmFacetFilters.results(favoritesOnly, facetSelection))
                    }
                    else if (category == "Top 250")
                        // TMDB Top Rated identity and order are authoritative;
                        // metadata grouping is not allowed to hide its ranks.
                        tmdbState.tmdbTop250Films.map { tmdbState.tmdbResolved[it.id] ?: it }.distinctBy(Film::id)
                    else if (category == "我的") {
                        val recent = (recentIndexFilms.map { cached -> films.firstOrNull { it.id == cached.id } ?: cached } +
                            myRecent).distinctBy(Film::id)
                        val saved = showCatalog.favorites(favorites)
                        showCatalog.collapse(HerrinMyLibrary.combine(recent, saved))
                    }
                    else showCatalog.collapse(catalogFilms.filter {
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
                val railFilms = if (!isSearch && category == "首页" && sourceConfigured) showCatalog.recentRail(recentIndexFilms, watched) else filtered
                val railState = railStates.getValue(if (isSearch) "search" else category)
                val recommendedFilms = if (!isSearch && category == "首页") { if (sourceConfigured) BrowseHeroSelection.candidates("首页", collapsedCatalog, tmdbState.tmdbTop250Films) else collapsedCatalog.asReversed() } else emptyList()
                val recommendationPrefix = if (railFilms.isEmpty()) "card-" else "recommend-"
                val recommendState = railStates.getValue("recommend")
                val browseScroll = browseScrollStates.getValue(if (isSearch) "search" else category)
                LaunchedEffect(screen, category, recentIndexFilms) {
                    if (!isSearch && category == "首页" && sourceConfigured) {
                        val index = railFilms.indexOfFirst { "card-${displayKey(it)}" == focusId }
                        if (index >= 0) {
                            railState.scrollToItem(index)
                            repeat(4) {
                                withFrameNanos { }
                                if (runCatching { requester(focusId).requestFocus() }.isSuccess) return@LaunchedEffect
                            }
                        }
                    }
                }
                val suggestions = listOf("轨道", "潮汐", "群山", "星夜", "清空")
                val headerDownTarget = when {
                    isSearch -> "search-input"
                    category == "直播" -> if (liveChannels.isEmpty()) "live-config" else "live-group-all"
                    category == "我的" -> "my-manage-personal-sources"
                    category == "Top 250" -> if (railFilms.isEmpty()) "top250-retry" else
                        "card-${displayKey(railFilms.getOrNull(railState.firstVisibleItemIndex) ?: railFilms.first())}"
                    tmdbPrimary && catalogFilms.isEmpty() -> "tmdb-home-retry"
                    sourceConfigured && catalogFilms.isEmpty() -> null
                    films.isNotEmpty() -> "play"
                    else -> null
                }?.let { requester(it) }
                // Never observe LazyRow.layoutInfo during composition: each
                // layout pass invalidates it, leaving Compose perpetually busy.
                val visibleFirstIndex by remember(railState) {
                    derivedStateOf { railState.firstVisibleItemIndex }
                }
                val firstPosterTarget = (railFilms.getOrNull(visibleFirstIndex) ?: recommendedFilms.firstOrNull())
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
                            onFocus = { focusId = it },
                            onSelect = { selected ->
                                category = selected
                                val candidates = if (selected == "Top 250") tmdbState.tmdbTop250Films else catalogFilms.filter {
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
                            label = { Text("输入片名 / 英文名（支持系统键盘）") }, singleLine = true)
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
                            TvButton("管理 NAS / 云盘",
                                modifier = Modifier.testTag("my-manage-personal-sources").focusRequester(requester("my-manage-personal-sources")),
                                reduced = reduceMotion) {
                                // NAS / cloud editors are in the existing
                                // compact Content Sources settings section.
                                settingsTvBoxOnly = true
                                screen = "settings"
                            }
                            Text("NAS ${if (prefs.nasWebDavUrl.isBlank()) "未配置" else "已配置"}" +
                                " · 云盘 ${if (prefs.cloudWebDavUrl.isBlank()) "未配置" else "已配置"}",
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
                        Text(providerStatus + "；请进入设置选择站点", fontSize = 15.sp, lineHeight = 23.sp,
                            color = Color(0xFFCDD8E6), modifier = Modifier.testTag("source-empty-status"))
                        Spacer(Modifier.height(24.dp))
                    } else {
                        Spacer(Modifier.height(132.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.width(13.dp).height(1.dp)
                                .background(Color(0xB8DCE5F0)))
                            Text(if (tmdbState.tmdbHomeFilms.isNotEmpty()) "TMDB 精选"
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(if (film.id.startsWith("tmdb-")) "搜索片源与选集" else if (providerFilms == null) "▶ 播放演示" else "选择集数与线路",
                                selected = true, reduced = reduceMotion, onFocus = { focusId = "play" },
                                modifier = Modifier.testTag("play")
                                    .then(if (firstPosterTarget != null) Modifier.focusProperties { down = firstPosterTarget } else Modifier)
                                    .focusRequester(requester("play"))) {
                                if (film.id.startsWith("tmdb-") || showGroup != null || providerFilms != null) details(film)
                                else {
                                    returnScreen = "browse"
                                    episode = film.playbackEpisodes.firstOrNull()?.number ?: 1
                                    line = film.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
                                    screen = "player"
                                }
                            }
                            TvButton("影片详情", reduced = reduceMotion, onFocus = { focusId = "details" },
                                modifier = Modifier.testTag("details")
                                    .then(if (firstPosterTarget != null) Modifier.focusProperties { down = firstPosterTarget } else Modifier)
                                    .focusRequester(requester("details"))) { details(film) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "直播" -> "直播频道"; category == "我的" -> "继续观看与收藏 · ${filtered.size} 部"; category == "首页" -> if (sourceConfigured) "最近播放" else "演示片库"; else -> if (sourceConfigured) "${category} · ${filtered.size} 部" else "精选${category} · 演示片库" },
                            modifier = Modifier.testTag("recent-heading"),
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text(if (!isSearch && category == "首页" && sourceConfigured) "" else if (sourceConfigured) "${providerStatus.take(48)}" else if (!isSearch && category == "首页") "精选首页 · 演示片库" else "DISCOVER   /   HERRIN",
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
                                    down = if (homeRail) requester("recommend-${displayKey(recommendedFilms[index.coerceAtMost(recommendedFilms.lastIndex)])}")
                                        else FocusRequester.Cancel
                                }
                                .onPreviewKeyEvent { event ->
                                    if (homeRail && event.type == KeyEventType.KeyDown &&
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                        transferPosterFocus(railState, recommendState, index,
                                            recommendedFilms, "recommend-", browseScroll, true)
                                        true
                                    } else false
                                }
                                .focusRequester(requester("card-${displayKey(item)}")), reduceMotion,
                                onFocus = { filmId = item.id; heroSelections[category] = item.id; focusId = "card-${displayKey(item)}" }, onClick = {
                                    val recent = !isSearch && sourceConfigured && category in setOf("首页", "我的") && recentIndexFilms.any { it.id == item.id }
                                    val origin = if (recent) PlaybackReturnContext(screen, category, "card-${displayKey(item)}", item.id) else null
                                    openMovie(item)
                                    recentPlaybackReturn = origin
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
                                        up = railFilms.getOrNull(index.coerceAtMost(railFilms.lastIndex))?.let { requester("card-${displayKey(it)}") } ?: requester("play")
                                        down = FocusRequester.Cancel
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            if (railFilms.isEmpty()) {
                                                uiScope.launch { browseScroll.scrollTo(0); withFrameNanos { }; requester("play").requestFocus() }
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
                        if (tmdbState.tmdbFallbackIndex.failSelected(target)) {
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
                val recoverPlayback: (FongMiPlaybackRecovery.Failure, Long) -> Boolean = recover@{ failure, position ->
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
                val cmsLine = if (screen == "player" && !playbackNeedsRefresh &&activeCmsSite != null &&
                    film.cmsVodId != null && !spiderActive)
                    film.playbackEpisodes.firstOrNull { it.number == episode }
                        ?.lines?.firstOrNull { it.id == line } else null
                val cmsJsonParser = cmsLine?.let { selected ->
                    TvBoxJsonPlayback.choose(selected.uri, selected.title,
                        tvBoxSingle?.jsonParsers.orEmpty())
                }
                val cmsBrowserParser = if (cmsJsonParser == null) cmsLine?.let { selected ->
                    FongMiBrowserPlayback.choose(selected.uri, selected.title,
                        tvBoxSingle?.playbackParsers.orEmpty(), tvBoxAllowHttp)
                } else null
                // Only playback URL resolution changes. Existing UI, TMDB,
                // film metadata and details remain completely untouched.
                val parsedCms = if (cmsLine != null && cmsJsonParser != null)
                    CmsJsonPlayback(cmsLine.uri, cmsJsonParser, tvBoxAllowHttp, playerRetryEpoch)
                else if (cmsLine != null && cmsBrowserParser != null)
                    CmsBrowserPlayback(cmsLine.uri, cmsBrowserParser, tvBoxAllowHttp, playerRetryEpoch)
                else null
                val cmsParseWaiting = (cmsJsonParser != null || cmsBrowserParser != null) && parsedCms == null
                val currentSpiderResult = spiderResolved.takeIf { spiderResolvedFor == recoveryAttempt }
                val personalMedia = if (screen == "player" && film.playbackEpisodes.any { ep ->
                    ep.number == episode && ep.lines.any { it.id == line } })
                    tmdbState.tmdbPersonalPlayback[film.id]?.get(line) else null
                val licensed = if (personalMedia != null) PlaybackResult.Ready(personalMedia)
                    else if (spiderActive && screen == "player") currentSpiderResult
                    else if (screen == "player" && film.playbackEpisodes.isNotEmpty()) {
                        if (activeCmsSite != null && film.cmsVodId != null) {
                            if (cmsJsonParser != null || cmsBrowserParser != null) parsedCms
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
                    activeCmsSite == null) AuthorizedUrls.https(prefs.testMediaUrl) else null
                val chosenMedia = if (tmdbState.automaticSearch != null ||playbackNeedsRefresh) null
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
                        media = chosenMedia,
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
                        onLineSelection = if (film.playbackEpisodes.isNotEmpty()) ({ playbackPicker = "lines" }) else null,
                        onEpisodeSelection = if (film.playbackEpisodes.isNotEmpty()) ({ playbackPicker = "episodes" }) else null,
                        onRefreshSource = if (spiderActive || cmsJsonParser != null || cmsBrowserParser != null) ({ playerRetryEpoch++ }) else null,
                        onTryOtherSource = tryOtherTmdbSource,
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
                        onBack = { if (screen == "player") back() }
                    )
                    }
                    if (playbackPicker != null) PlaybackSelectionDialog(
                        film, episode, line, playbackPicker == "lines",
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
                        }, onDismiss = { playbackPicker = null })
                } else if (screen == "player") {
                    val playbackUnavailable = tmdbState.automaticSearch == null &&
                        (currentSpiderResult is PlaybackResult.Unavailable ||
                        (!spiderActive && !cmsWaiting && !cmsParseWaiting))
                    val offlineDemonstration = !sourceConfigured && !spiderActive &&
                        film.playbackEpisodes.isEmpty()
                    PlayerStartupSurface(
                        loading = !playbackUnavailable,
                        offlineDemonstration = offlineDemonstration,
                        cmsFailed = cmsFailed,
                        retryAvailable = spiderActive || cmsFailed || cmsJsonParser != null || cmsBrowserParser != null,
                        reducedMotion = reduceMotion,
                        failureHint = (parsedCms as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure)
                            ?: (currentSpiderResult as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure)
                            ?: (licensed as? PlaybackResult.Unavailable)
                                ?.reason?.let(PlayerStartupHints::forSpiderFailure),
                        // Before Media3 starts, the parser/CMS/Spider error must
                        // be visible rather than presenting a fake decoder failure.
                        failureDiagnostic = when {
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
                        onTryOtherSource = tryOtherTmdbSource,
                        onBack = {
                            if (screen == "player") {
                                if (cmsFailed) { cmsPlaybackPendingId = null; cmsPlaybackFailedId = null }
                                back()
                            }
                        }
                    )
                } else {
                    // Isolate these large DSL blocks into independent methods.
                    // MainActivityKt.Herrin otherwise violates the JVM 64-KiB
                    // per-method bytecode limit when TVBox and player UI grow.
                    fun androidx.compose.foundation.lazy.LazyListScope.renderSettingsItems() {
                        item { Text(if (settingsTvBoxOnly) "设置 · 内容来源" else "高级设置", fontSize = 32.sp) }
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
                        item { Text(if (settingsTvBoxOnly) "影视源" else "TVBox 多仓 / 单仓 / IPTV", fontSize = 22.sp) }
                        if (!settingsTvBoxOnly) {
                            item { Text("支持 urls 多仓、sites/lives 单仓、内嵌直播、M3U 与 TVBox TXT。Java csp_* Spider 可在本次明确授权后尝试加载与解析；JS/Python、网页二次解析及部分受保护插件仍可能不兼容。失效的配置接口可从本机已缓存的有效 JSON 回退。", color = Color(0xFFBDC8D6), lineHeight = 23.sp) }
                        }
                        item {
                            if (settingsTvBoxOnly) TvSourceInput(
                                tvBoxUrlDraft, { tvBoxUrlDraft = it },
                                "影视源 · TVBox 单仓 / 多仓 HTTP(S) 地址",
                                fieldTag = "tvbox-warehouse-url", navTag = "tvbox-warehouse-nav",
                                navRequester = requester("tvbox-warehouse-url"),
                                editRequester = requester("tvbox-warehouse-editor"),
                                onNavFocus = { focusId = "tvbox-warehouse-url"; configInputFocused = false },
                                onEditingFocus = { configInputFocused = it; if (it) focusId = "tvbox-warehouse-editor" },
                                onMoveUp = { moveCompactSettingsFocus("screen-first") },
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
                                tvBoxRunSpiderAfterApply = true
                                vodImportFeedback = "读取中…"
                                tvBoxRequest = tvBoxUrlDraft.trim() to true
                                tvBoxLoadEpoch++
                            }
                            if (settingsTvBoxOnly) {
                                Spacer(Modifier.width(12.dp))
                                TvButton("选择影视点", modifier = Modifier.testTag("settings-source-sites")
                                    .focusRequester(requester("settings-source-sites")),
                                    reduced = reduceMotion, onFocus = {
                                        focusId = "settings-source-sites"
                                        lastMovieSettingsAction = "settings-source-sites"
                                        keyboard?.hide()
                                    }) { screen = "sites" }
                            }
                            Spacer(Modifier.weight(1f))
                            if (settingsTvBoxOnly && vodImportFeedback.isNotBlank()) {
                                Text(vodImportFeedback, Modifier.testTag("vod-apply-feedback"),
                                    color = if (vodImportFeedback == "成功") Color(0xFF85D9B5)
                                    else if (vodImportFeedback.contains("失败") || vodImportFeedback.contains("未获取") ||
                                        vodImportFeedback.contains("崩溃") || vodImportFeedback.contains("超时")) Color(0xFFF2A5A5)
                                    else Color(0xFFC4D3E7), fontSize = 14.sp)
                            }
                        } }
                        if (settingsTvBoxOnly && vodImportFeedback.isNotBlank()) {
                            item { Text(ConsumerSourceStatus.display(tvBoxStatus), Modifier.testTag("compact-tvbox-status"),
                                color = if (vodImportFeedback.contains("崩溃") || vodImportFeedback.contains("超时"))
                                    Color(0xFFF2A5A5) else Color(0xFFCFD7E4),
                                lineHeight = 21.sp, fontSize = 14.sp) }
                        }
                        if (!settingsTvBoxOnly) {
                        item { OutlinedTextField(tvBoxJsonDraft, { tvBoxJsonDraft = it },
                            Modifier.fillMaxWidth().testTag("tvbox-import-json")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("可选：从旧 TVBox 复制完整单仓／多仓 JSON") },
                            minLines = 2, maxLines = 5) }
                        item { TvButton("导入并缓存 TVBox JSON", modifier = Modifier.testTag("tvbox-import-json-save"),
                            reduced = reduceMotion, onFocus = { keyboard?.hide() }) {
                            tvBoxSelectedName = ""
                            tvBoxRequest = tvBoxUrlDraft.trim() to true
                            tvBoxImportJsonRequest = tvBoxJsonDraft
                            tvBoxLoadEpoch++
                        } }
                        }
                        if (settingsTvBoxOnly) {
                            item { Text("智能海报墙 · TMDB",
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                            item { Text(if (metadataToken.isBlank())
                                "未启用自动刮削：没有配置 TMDB API Read Access Token，当前显示影视源海报。" +
                                    "配置后自动匹配海报、背景、简介、评分和演员资料。"
                                else "TMDB 匹配检查：已尝试 $metadataScanDone/$metadataScanTotal，" +
                                    "成功匹配 ${providerFilms.orEmpty().count { it.metadataSource == "TMDB" }} 部，" +
                                    "未匹配 $metadataScanNoMatch 部，请求失败 $metadataScanFailures 部。" +
                                    "只有搜索接口验证通过才会开始刮削；播放地址与选集仍由影视源提供。",
                                modifier = Modifier.testTag("compact-metadata-status"),
                                color = Color(0xFFBFD6ED), lineHeight = 21.sp) }
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
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvButton("保存并开始刮削",
                                    modifier = Modifier.testTag("compact-tmdb-save")
                                        .focusRequester(requester("compact-tmdb-save")),
                                    reduced = reduceMotion,
                                    onFocus = { focusId = "compact-tmdb-save"; keyboard?.hide() }) {
                                    val value = candidateTmdbToken()
                                    if (value != null) uiScope.launch {
                                        val saved = withContext(Dispatchers.IO) { runCatching { prefs.tmdbReadToken = value }.isSuccess }
                                        if (!saved) { tmdbSettingsStatus = "安全存储不可用，令牌未保存"; return@launch }
                                        metadataResolver.clearAuthCooldown(value)
                                        metadataForceIds = providerFilms.orEmpty().map { it.id }.toSet()
                                        providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                        metadataToken = value
                                        clearTmdbInput()
                                        metadataRescanEpoch++
                                        uiScope.launch {
                                            withContext(Dispatchers.IO) { tmdbDiscovery.invalidate(metadataToken) }
                                            tmdbProbeEpoch++
                                        }
                                        tmdbSettingsStatus = "正在重新读取 TMDB 海报和影片资料"
                                    } else tmdbSettingsStatus = "需要完整的 TMDB API Read Access Token"
                                }
                                TvButton("扫码输入 Token", Modifier.testTag("tmdb-phone-config")
                                    .focusRequester(requester("tmdb-phone-config")), reduceMotion,
                                    onFocus = { focusId = "tmdb-phone-config" }) { lanConfigOpen = true }
                                TvButton("重新刮削",
                                    modifier = Modifier.testTag("compact-tmdb-rescan")
                                        .focusRequester(requester("compact-tmdb-rescan")),
                                    reduced = reduceMotion,
                                    onFocus = { focusId = "compact-tmdb-rescan"; keyboard?.hide() }) {
                                    if (metadataToken.isNotBlank()) {
                                        metadataResolver.clearAuthCooldown(metadataToken)
                                        metadataForceIds = providerFilms.orEmpty().map { it.id }.toSet()
                                        providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                        metadataRescanEpoch++
                                        uiScope.launch {
                                            withContext(Dispatchers.IO) { tmdbDiscovery.invalidate(metadataToken) }
                                            tmdbProbeEpoch++
                                        }
                                        tmdbSettingsStatus = "正在重新匹配当前影视点的元数据"
                                    } else tmdbSettingsStatus = "请先配置 TMDB Token"
                                }
                            } }
                            item { Text(tmdbSettingsStatus, color = Color(0xFFCFD7E4),
                                modifier = Modifier.testTag("compact-tmdb-feedback")) }
                            if (metadataToken.isNotBlank()) item {
                                Text(tmdbProbeMessage,
                                    modifier = Modifier.testTag("compact-tmdb-connectivity"),
                                    color = if (tmdbConnectivity == TmdbProbeState.CONNECTED)
                                        Color(0xFFB3E8D2) else Color(0xFFFFD49D))
                            }
                            if (metadataScanFailures > 0 && metadataScanDone >= metadataScanTotal &&
                                tmdbConnectivity == TmdbProbeState.CONNECTED) item {
                                Text("本轮 $metadataScanFailures 部 TMDB 请求失败，不是片名无匹配。请检查电视网络并点重新刮削。",
                                    color = Color(0xFFFFD49D))
                            }
                            if (tmdbConnectivity == TmdbProbeState.CONNECTED &&
                                metadataScanTotal > 0 && metadataScanDone >= metadataScanTotal &&
                                metadataScanFailures == 0 &&
                                providerFilms.orEmpty().none { it.metadataSource == "TMDB" }) item {
                                Text("已连通 TMDB，但本轮没有可靠的同名匹配。请检查影视源片名、电影/剧集分类，" +
                                    "或在影片详情中手动纠正匹配；不会误用其他影片的海报。",
                                    modifier = Modifier.testTag("compact-tmdb-no-matches"),
                                    color = Color(0xFFFFD49D))
                            }
                        }
                        if (settingsTvBoxOnly) {
                            item { Text("个人影片资源优先 · NAS / 云盘", fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold) }
                            item { Text("支持用户自己授权的 WebDAV 地址；NAS 可通过 WebDAV 服务共享，云盘需配置有权访问的 WebDAV 网关。SMB、阿里云盘、夸克和 115 原生 API 仍需后续单独接入。",
                                color = Color(0xFFBDC9D7), lineHeight = 20.sp) }
                            item { OutlinedTextField(nasDavUrlDraft, { nasDavUrlDraft = it.take(2048) },
                                Modifier.fillMaxWidth().testTag("nas-webdav-url"),
                                label = { Text("NAS WebDAV 根目录 URL（优先）") }, singleLine = true) }
                            item { OutlinedTextField(nasDavUserDraft, { nasDavUserDraft = it.take(256) },
                                Modifier.fillMaxWidth().testTag("nas-webdav-user"),
                                label = { Text("NAS 用户名（可留空）") }, singleLine = true) }
                            item { OutlinedTextField(nasDavPasswordDraft, { nasDavPasswordDraft = it.take(2048) },
                                Modifier.fillMaxWidth().testTag("nas-webdav-password"),
                                label = { Text("NAS 密码（不显示、不存明文）") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation()) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvButton("保存 NAS 地址", modifier = Modifier.testTag("nas-webdav-save"),
                                    reduced = reduceMotion) {
                                    val valid = nasDavUrlDraft.isBlank() || runCatching {
                                        HerrinWebDavSource(HerrinWebDavAccount("NAS", nasDavUrlDraft.trim(),
                                            category = HerrinSourceType.NAS, allowInsecureHttp = prefs.nasAllowHttp))
                                    }.isSuccess
                                    if (!valid) personalSourceFeedback = "NAS URL 必须是合法 HTTPS；HTTP 需手动授权"
                                    else {
                                        val vault = HerrinPersonalCredentialStore(context, "nas")
                                        val old = vault.load()
                                        val saved = vault.save(nasDavUserDraft.ifBlank { old.first },
                                            nasDavPasswordDraft.ifBlank { old.second })
                                        if (saved) {
                                            prefs.nasWebDavUrl = nasDavUrlDraft.trim()
                                            nasDavPasswordDraft = ""
                                            personalSourceConfigEpoch++
                                            personalSourceFeedback = "NAS 地址和加密凭据已保存；详情将优先搜索 NAS"
                                        } else personalSourceFeedback = "设备凭据加密失败，未保存 NAS 地址"
                                    }
                                }
                                TvButton(if (prefs.nasAllowHttp) "NAS HTTP 已允许" else "允许 NAS HTTP（仅可信局域网）",
                                    reduced = reduceMotion) {
                                    prefs.nasAllowHttp = !prefs.nasAllowHttp
                                    personalSourceConfigEpoch++
                                }
                            } }
                            item { OutlinedTextField(cloudDavUrlDraft, { cloudDavUrlDraft = it.take(2048) },
                                Modifier.fillMaxWidth().testTag("cloud-webdav-url"),
                                label = { Text("云盘 WebDAV 桥接根目录 URL") }, singleLine = true) }
                            item { OutlinedTextField(cloudDavUserDraft, { cloudDavUserDraft = it.take(256) },
                                Modifier.fillMaxWidth().testTag("cloud-webdav-user"),
                                label = { Text("云盘 WebDAV 用户名（可留空）") }, singleLine = true) }
                            item { OutlinedTextField(cloudDavPasswordDraft, { cloudDavPasswordDraft = it.take(2048) },
                                Modifier.fillMaxWidth().testTag("cloud-webdav-password"),
                                label = { Text("云盘 WebDAV 密码（本机加密）") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation()) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvButton("保存云盘 WebDAV", modifier = Modifier.testTag("cloud-webdav-save"),
                                    reduced = reduceMotion) {
                                    val valid = cloudDavUrlDraft.isBlank() || runCatching {
                                        HerrinWebDavSource(HerrinWebDavAccount("云盘", cloudDavUrlDraft.trim(),
                                            category = HerrinSourceType.CLOUD, allowInsecureHttp = prefs.cloudAllowHttp))
                                    }.isSuccess
                                    if (!valid) personalSourceFeedback = "云盘 WebDAV URL 无效；默认必须 HTTPS"
                                    else {
                                        val vault = HerrinPersonalCredentialStore(context, "cloud")
                                        val old = vault.load()
                                        val saved = vault.save(cloudDavUserDraft.ifBlank { old.first },
                                            cloudDavPasswordDraft.ifBlank { old.second })
                                        if (saved) {
                                            prefs.cloudWebDavUrl = cloudDavUrlDraft.trim()
                                            cloudDavPasswordDraft = ""
                                            personalSourceConfigEpoch++
                                            personalSourceFeedback = "云盘 WebDAV 已保存；优先于 TVBox 查找"
                                        } else personalSourceFeedback = "设备加密失败，未保存云盘 URL"
                                    }
                                }
                                TvButton(if (prefs.cloudAllowHttp) "云盘 HTTP 已允许" else "允许云盘 HTTP（不推荐）",
                                    reduced = reduceMotion) {
                                    prefs.cloudAllowHttp = !prefs.cloudAllowHttp
                                    personalSourceConfigEpoch++
                                }
                            } }
                            item { Text(personalSourceFeedback.ifBlank { tmdbState.tmdbDiscoveryStatus },
                                modifier = Modifier.testTag("personal-source-feedback"),
                                color = Color(0xFFBDD9EF)) }
                            item { Text("IMDb 评分 · 用户自行导入", fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold) }
                            item { TvButton("导入本地 IMDb title.ratings.tsv.gz",
                                modifier = Modifier.testTag("imdb-import"),
                                reduced = reduceMotion) {
                                    runCatching {
                                        imdbImportPicker.launch(arrayOf("application/gzip",
                                            "application/x-gzip", "application/octet-stream"))
                                    }.onFailure {
                                        imdbImportStatus = "电视未提供文件选择器，请使用支持 SAF 的文件管理器"
                                    }
                                } }
                            item { Text(imdbImportStatus, modifier = Modifier.testTag("imdb-import-status"),
                                color = Color(0xFFCBD5E4), fontSize = 13.sp) }
                        }
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
                                onMoveUp = { moveCompactSettingsFocus("compact-tmdb-save") },
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
                            if (settingsTvBoxOnly && liveImportFeedback.isNotBlank()) {
                                Text(liveImportFeedback, Modifier.testTag("live-apply-feedback"),
                                    color = if (liveImportFeedback == "成功") Color(0xFF85D9B5)
                                    else if (liveImportFeedback == "失败") Color(0xFFF2A5A5)
                                    else Color(0xFFC4D3E7), fontSize = 14.sp)
                            }
                        } }
                        if (!settingsTvBoxOnly) {
                            item { Text(tvBoxStatus, modifier = Modifier.testTag("tvbox-status"),
                                color = Color(0xFFCFD7E4), lineHeight = 23.sp) }
                        }
                        if (settingsTvBoxOnly) {
                            item { TvButton("返回首页", modifier = Modifier.testTag("settings-home")
                                .focusRequester(requester("settings-home")), reduced = reduceMotion,
                                onFocus = { focusId = "settings-home" }) {
                                category = "首页"; screen = "browse"
                            } }
                            item { TvButton("高级工具与诊断", modifier = Modifier.testTag("settings-advanced")
                                .focusRequester(requester("settings-advanced")), reduced = reduceMotion,
                                onFocus = { focusId = "settings-advanced" }) {
                                settingsTvBoxOnly = false
                                focusId = "screen-first"
                            } }
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
                                            uiScope.launch {
                                                tvBoxStatus = "正在读取 ${site.name} 的影视列表…"
                                                when (val loaded = tvBoxVodRepo.load(site, tvBoxAllowHttp)) {
                                                    is TvBoxVodResult.Error -> tvBoxStatus = "${site.name}：${loaded.reason}"
                                                    is TvBoxVodResult.Ready -> {
                                                        spiderActive = false
                                                        activeCmsSite = site
                                                        prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                                                        prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, preview.spiderJar)
                                                        providerFilms = loaded.films
                                                        providerStatus = "TVBox ${site.name} · ${loaded.films.size} 部（标准 CMS JSON）"
                                                        tvBoxVodScope = "tvbox:${site.apiUrl}"
                                                        filmId = loaded.films.first().id
                                                        category = "首页"
                                                        screen = "browse"
                                                        tvBoxStatus = "已读取 ${loaded.films.size} 部影片；点击海报即可尝试播放有权限的 HTTPS 线路"
                                                    }
                                                }
                                            }
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
                                            uiScope.launch {
                                                tvBoxStatus = "正在核验 JAR 并通过 ${site.name} 读取影视…"
                                                when (val loaded = spiderBridge.browse(site, preview)) {
                                                    is SpiderResult.Failure -> tvBoxStatus = "${site.name}：${loaded.message}"
                                                    is SpiderResult.Ready -> {
                                                        spiderActive = true
                                                        activeCmsSite = null
                                                        prefs.tvBoxLastWorkingRootUrl = prefs.tvBoxWarehouseUrl
                                                        prefs.tvBoxLastWorkingSiteId = TvBoxAutoSites.identity(site, preview.spiderJar)
                                                        providerFilms = loaded.value
                                                        providerStatus = "TVBox Spider ${site.name} · ${loaded.value.size} 部"
                                                        tvBoxVodScope = "spider:${site.key}:${preview.spiderJar}"
                                                        filmId = loaded.value.first().id
                                                        prefs.tvBoxTrustedSpiderRootUrl = prefs.tvBoxWarehouseUrl
                                                        prefs.tvBoxTrustedSpiderJarSpec = site.jar ?: preview.spiderJar.orEmpty()
                                                        category = "首页"
                                                        screen = "browse"
                                                        tvBoxStatus = "已读取 ${loaded.value.size} 部；打开详情获取选集，选择线路后解析播放"
                                                    }
                                                }
                                            }
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
                        if (film.id.startsWith("tmdb-") && screen == "detail") item {
                            SourceSearchStatus(if(tmdbState.tmdbStatusFilm==film.id)tmdbState.tmdbSourceStatus.ifBlank{"正在快速检索已配置来源…"} else "正在快速检索已配置来源…",
                                tmdbState.tmdbStageStarted,tmdbState.tmdbSearchRunning &&tmdbState.tmdbStatusFilm==film.id)
                        }
                        if (film.id.startsWith("tmdb-") && screen == "detail" &&
                            HerrinPlaybackEntry.firstPlayable(film) != null) item {
                            TvButton("查找更清晰的其他影视点",
                                modifier = Modifier.testTag("tmdb-next-source-batch"),
                                reduced = reduceMotion) {
                                // Never silently switch a working video. Explicit
                                // remote action excludes the chosen provider and
                                // examines the next bounded group of trusted nodes.
                                if (tmdbState.tmdbFallbackIndex.failSelected(film.id)) {
                                    tmdbState.tmdbPlaybackRoutes.forget(film.id)
                                    tmdbState.tmdbPersonalPlayback.remove(film.id)
                                    tmdbState.tmdbResolved[film.id]?.let { old ->
                                        tmdbState.tmdbResolved[film.id] = old.copy(
                                            playbackEpisodes = emptyList(),
                                            cmsVodId = null, spiderVodId = null)
                                    }
                                    activeCmsSite = null
                                    spiderActive = false
                                    tmdbState.tmdbSourceStatus = "正在检索下一批授权影视点；清晰度标签不等于真实解码规格"
                                    tmdbState.tmdbLookupEpoch++
                                }
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
                        if (!spiderActive && film.cmsVodId != null && cmsDetailStatus.isNotBlank())
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
                                if (HerrinPlaybackEntry.unavailableTmdb(film)) {
                                    Text("正在检索或尚未找到真实片源",
                                        modifier = Modifier.testTag("tmdb-playback-unavailable"),
                                        color = Color(0xFFD9B78A), fontSize = 14.sp)
                                    TvButton(if((tmdbState.tmdbSpiderBatchOffsets[film.id]?:0)>0 ||tmdbState.tmdbSourceStatus.contains("预算结束"))"继续搜索剩余节点" else "重新检索片源",
                                        modifier = Modifier.testTag("tmdb-source-retry"),
                                        reduced = reduceMotion) {
                                        tmdbState.tmdbExtendedRequest=film.id.takeIf{(tmdbState.tmdbSpiderBatchOffsets[film.id]?:0)>0 ||tmdbState.tmdbSourceStatus.contains("预算结束")}
                                        tmdbState.tmdbFallbackIndex.reset(film.id)
                                        tmdbState.tmdbPlaybackRoutes.forget(film.id)
                                        tmdbState.tmdbResolved[film.id]?.let { current ->
                                            tmdbState.tmdbResolved[film.id] = current.copy(
                                                playbackEpisodes = emptyList(),
                                                cmsVodId = null, spiderVodId = null)
                                        }
                                        tmdbState.tmdbPersonalPlayback.remove(film.id)
                                        tmdbState.tmdbSourceStatus = "正在重新检索 NAS／云盘／TVBox…"
                                        tmdbState.tmdbLookupEpoch++
                                    }
                                } else if (providerFilms == null || film.id.startsWith("tmdb-")) TvButton(when {
                                    showGroup != null && groupResume != null ->
                                        "▶ 继续播放 第${groupResume.first.season}季 第${groupResume.first.number}集 ${formatSeasonClock(groupResume.second.positionMs)}"
                                    showGroup != null && groupSection?.episodes?.isNotEmpty() == true ->
                                        "▶ 播放 第${groupSection.number}季 第${groupSection.episodes.first().number}集"
                                    showGroup != null -> "正在读取多季选集"
                                    spiderActive && film.playbackEpisodes.isEmpty() -> "正在获取选集"
                                    seasonResume != null ->
                                        "▶ 继续播放 第${seasonResume.entry.season}季 第${seasonResume.entry.number}集 ${formatSeasonClock(seasonResume.position.positionMs)}"
                                    seasonLayout != null && detailSeasonEntries.isNotEmpty() ->
                                        "▶ 播放 第${detailSeason!!.number}季 第${(detailSeasonSource ?: detailSeasonEntries.first()).number}集"
                                    resume != null && resume.positionMs > 0L ->
                                        "▶ 继续播放 ${resume.positionMs / 60000}:${"%02d".format(resume.positionMs / 1000 % 60)}"
                                    spiderActive -> "▶ 播放当前集"
                                    providerFilms != null && film.playbackEpisodes.isEmpty() -> "暂无可播选集"
                                    film.playbackEpisodes.isEmpty() -> "▶ 播放演示"
                                    else -> "▶ 播放授权片源"
                                }, selected = true, reduced = reduceMotion) {
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
                                val groupFavorited = showGroup?.members?.any { it.film.id in favorites }
                                    ?: (film.id in favorites)
                                TvButton(if (groupFavorited) "♥ 已收藏 · 取消" else "♡ 添加收藏", reduced = reduceMotion) {
                                    if (showGroup == null) prefs.toggleFor(catalogScope, film.id)
                                    else if (groupFavorited) showGroup.members.filter { it.film.id in favorites }
                                        .forEach { prefs.toggleFor(catalogScope, it.film.id) }
                                    else prefs.toggleFor(catalogScope, showGroup.representative.id)
                                    favorites = prefs.idsFor(catalogScope)
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
                            if (showGroup == null && !HerrinPlaybackEntry.unavailableTmdb(film))
                                item { Text("线路",
                                modifier = Modifier.testTag("detail-line-heading"),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                            if (showGroup == null && !HerrinPlaybackEntry.unavailableTmdb(film) &&
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
                                        }, onPlay = { entry -> playSeasonEpisode(entry) },
                                        onMoveToCast = ::moveToDetailCast, entryFocus = requester("detail-current-episode"), onMoveToLines = ::returnDetailLine)
                                }
                            } else if (HerrinPlaybackEntry.unavailableTmdb(film)) {
                                item { Text("尚无可播放的授权选集",
                                    modifier = Modifier.testTag("tmdb-episodes-unavailable"),
                                    fontSize = 18.sp, color = Color(0xFFD9B78A)) }
                            } else {
                                item { Text(if (providerFilms != null && film.playbackEpisodes.isEmpty())
                                    "原始条目 · 尚无可播放的真实选集"
                                    else if (film.playbackEpisodes.isEmpty()) "集数" else "集数",
                                    fontSize = 20.sp) }
                                if (providerFilms != null && film.playbackEpisodes.isEmpty()) item {
                                    Text("当前影视点未返回有效的集数或线路；不会将演示集当作真实播放地址。",
                                        modifier = Modifier.testTag("source-no-playable-episodes"),
                                        color = Color(0xFFD9B78A), fontSize = 14.sp)
                                } else item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                                episode = n
                                                screen = "player"
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
                                                episode = entry.number
                                                line = entry.lines.firstOrNull { it.id == line }?.id
                                                    ?: entry.lines.firstOrNull()?.id ?: line
                                                screen = "player"
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
                    LazyColumn(Modifier.testTag(if (screen == "detail") "film-detail-list" else "settings-list").fillMaxSize()
                    .background(Color(0xAA080B10))
                    .onPreviewKeyEvent { event ->
                        // An active TV text editor owns its arrow keys. Only the
                        // non-editing focus stops use compact-row navigation.
                        if (!CompactSettingsKeyPolicy.intercept(
                                onSettings = screen == "settings",
                                compact = settingsTvBoxOnly,
                                editorFocused = configInputFocused,
                                keyDown = event.type == KeyEventType.KeyDown,
                                code = event.nativeKeyEvent.keyCode)) false
                        else {
                            val code = event.nativeKeyEvent.keyCode
                            val target = when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                                    settingsIntent.next(focusId, false, lastMovieSettingsAction, tmdbTokenSplitMode)
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                                    settingsIntent.next(focusId, true, lastMovieSettingsAction, tmdbTokenSplitMode)
                                else -> null
                            }
                            if (target == null) false else {
                                moveCompactSettingsFocus(target)
                                true
                            }
                        }
                    }, state = settingsListState,
                    contentPadding = PaddingValues(if (screen == "settings" && settingsTvBoxOnly) 24.dp else 48.dp),
                    verticalArrangement = Arrangement.spacedBy(if (screen == "settings" && settingsTvBoxOnly) 12.dp else 18.dp)) {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton("‹ 返回", Modifier.testTag("screen-first").focusRequester(requester("screen-first")),
                            reduceMotion, onFocus = { if (screen == "settings") focusId = "screen-first" }) { back() }
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
                    personalSourceConfigEpoch++
                    configuredTvBoxUrl = prefs.tvBoxWarehouseUrl
                    if (prefs.tvBoxWarehouseUrl.isNotBlank()) {
                        tvBoxRequest = prefs.tvBoxWarehouseUrl to true
                        tvBoxLoadEpoch++
                    }
                }
                "catalogs" -> { catalogRefresh++ }
                "live" -> { liveRefresh++; epgRefresh++ }
                "nas" -> { nasDavUrlDraft = prefs.nasWebDavUrl; personalSourceConfigEpoch++ }
            }
        })
        SideEffect {
            startup.reduced = reduceMotion
            startup.homeReady = projection.second.isNotEmpty() || films.isEmpty()
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
        LazyRow(Modifier.testTag("season-cards"), state = rowState,
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
    var playerFocusEpoch by remember { mutableIntStateOf(0) }
    fun closeDrawer() { linePicker = null; drawerOpen = false; playerFocusEpoch++ }
    var drawerGroup by remember { mutableStateOf<String?>(null) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var favoriteVersion by remember { mutableIntStateOf(0) }
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
private fun Poster(film: Film, modifier: Modifier, reduced: Boolean, onFocus: () -> Unit, onClick: () -> Unit, progress: Float? = null) {
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
