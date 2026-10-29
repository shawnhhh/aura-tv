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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
internal fun TvButton(label: String, modifier: Modifier = Modifier, reduced: Boolean = false,
                     selected: Boolean = false, quiet: Boolean = false, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    // Native focus switches immediately. Only the *visual transform* animates,
    // using an interruptible spring in a GPU layer (no relayout or D-pad delay).
    val focusedScale = if (quiet) 1.028f else 1.042f
    val scale by animateFloatAsState(
        targetValue = if (focused && !reduced) focusedScale else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.84f, stiffness = 460f),
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
    val fill by animateColorAsState(targetFill, if (reduced) snap() else tween(190),
        label = "tv-pill-fill")
    val contentColor by animateColorAsState(if (focused) Color(0xFF111318) else Color.White,
        if (reduced) snap() else tween(190), label = "tv-pill-content")
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

/** Compact D-pad navigation: focused pill alone marks the active target. */
@Composable
private fun TvNavTabs(
    category: String,
    isSearch: Boolean,
    focusId: String,
    reducedMotion: Boolean,
    downTarget: FocusRequester?,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val categories = remember { listOf("首页", "电影", "剧集", "动漫", "综艺", "纪录片", "直播", "我的") }
    Row(
        Modifier.background(Color(0xE910131A), RoundedCornerShape(50.dp))
            .border(1.dp, Color(0x393B414B), RoundedCornerShape(50.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        categories.forEach { cat ->
            TvButton(cat,
                Modifier.testTag("nav-$cat")
                    .then(if (downTarget != null) Modifier.focusProperties { down = downTarget }
                        else Modifier)
                    .focusRequester(requester("nav-$cat")),
                reducedMotion, category == cat && !isSearch,
                quiet = true, onFocus = { onFocus("nav-$cat") }
            ) { onSelect(cat) }
        }
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
    val uiScope = rememberCoroutineScope()
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
    val liveFavoriteScope = if (prefs.tvBoxLiveActive) "tvbox-selected-live" else prefs.livePlaylistUrl
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
        if (prefs.tvBoxLiveActive) {
            liveChannels = tvBoxLiveStore.load()
            liveStatus = if (liveChannels.isEmpty()) "上次导入的 TVBox 频道已失效，请重新导入"
                else "已从本地加载 ${liveChannels.size} 个 TVBox 直播频道"
        } else if (endpoint.isBlank()) {
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
    var filmId by rememberSaveable { mutableStateOf(demoFilms.first().id) }
    var configuredTvBoxUrl by remember { mutableStateOf(prefs.tvBoxWarehouseUrl) }
    // Default settings are deliberately just two source inputs. Legacy diagnostics
    // and import tools remain behind an explicit advanced page.
    var settingsTvBoxOnly by rememberSaveable { mutableStateOf(true) }
    var playbackPicker by remember { mutableStateOf<String?>(null) }
    var savedProfiles by remember { mutableStateOf(profileStore.list()) }
    var activeProfileId by remember { mutableStateOf(profileStore.activeId()) }
    var sourceNameDraft by rememberSaveable { mutableStateOf("") }
    // Treat the private TMDB token as a password: never display a saved token,
    // log it, or put the value into Android saved-instance-state bundles.
    var tmdbTokenDraft by remember { mutableStateOf("") }
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
    var spiderActive by remember { mutableStateOf(false) }
    var spiderStatus by remember { mutableStateOf("尚未选择 Spider 站点") }
    var playerRetryEpoch by remember { mutableIntStateOf(0) }
    var spiderResolved by remember { mutableStateOf<PlaybackResult?>(null) }
    var tvBoxVodScope by remember { mutableStateOf<String?>(null) }
    var activeCmsSite by remember { mutableStateOf<TvBoxSite?>(null) }
    var cmsDetailStatus by remember { mutableStateOf("") }
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
    LaunchedEffect(Unit) {
        if (configuredTvBoxUrl.isNotBlank()) {
            tvBoxRequest = configuredTvBoxUrl to true
            tvBoxLoadEpoch++
        }
    }
    LaunchedEffect(tvBoxLoadEpoch) {
        val (address, isRoot) = tvBoxRequest ?: return@LaunchedEffect
        val runSpiderAfterApply = tvBoxRunSpiderAfterApply
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
        when (val result = if (imported != null) tvBoxRepository.importJson(imported, address, tvBoxAllowHttp)
            else tvBoxRepository.load(address, tvBoxAllowHttp)) {
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
                site.spiderApi != null && jar != null && TvBoxSpiderPolicy.mayRun(
                    runSpiderAfterApply, prefs.tvBoxWarehouseUrl, jar,
                    prefs.tvBoxTrustedSpiderRootUrl, prefs.tvBoxTrustedSpiderJarSpec)
            }
            for ((candidateIndex, site) in candidates.withIndex()) {
                val elapsed = SystemClock.elapsedRealtime() - scanStarted
                if (elapsed >= TvBoxAutoScanPolicy.TOTAL_BUDGET_MS) {
                    scanBudgetExceeded = true
                    break
                }
                val timeout = TvBoxAutoScanPolicy.timeoutMs(
                    elapsed, cmsElapsed, hasRunnableSpider, spider = site.spiderApi != null)
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
                } else if (site.spiderApi != null) {
                    val jarSpec = site.jar ?: config.spiderJar ?: continue
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
            // TVBox single warehouses can carry their own live channel groups.
            // Respect an independently configured live source if one exists.
            if (prefs.tvBoxLiveSourceUrl.isBlank() && prefs.livePlaylistUrl.isBlank() &&
                !prefs.tvBoxLiveActive) {
                for (live in tvBoxSingle!!.lives.take(4)) {
                    val loaded = if (live.channels.isNotEmpty()) {
                        val channels = live.channels.filter { TvBoxUrl.normalize(it.url, true) != null }
                        if (channels.isEmpty()) TvBoxLiveResult.Error("频道地址无效")
                        else TvBoxLiveResult.Ready(channels)
                    } else if (live.sourceUrl != null) tvBoxLiveRepo.load(live.sourceUrl, true)
                    else continue
                    if (loaded is TvBoxLiveResult.Ready && tvBoxLiveStore.save(loaded.channels, true)) {
                        prefs.tvBoxLiveActive = true
                        liveRefresh++
                        tvBoxStatus += "；直播页已导入 ${loaded.channels.size} 个频道"
                        break
                    }
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
    val sourceConfigured = configuredTvBoxUrl.isNotBlank()
    val films = providerFilms ?: if (sourceConfigured) emptyList() else demoFilms
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
    var category by rememberSaveable { mutableStateOf("首页") }
    var screen by rememberSaveable { mutableStateOf("browse") }
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
    val catalogScope = tvBoxVodScope ?: prefs.catalogUrl.ifBlank { "herrin-offline-demo" }
    // Only the source's playback identity survives enrichment. Every network
    // result modifies display fields in-place and never alters episode links,
    // favorites IDs or history keys.
    val metadataResolver = remember(context) {
        ArtworkMetadataResolver(java.io.File(context.cacheDir, "tmdb-artwork"))
    }
    // Reactive token: saving a key starts matching without relaunching the app.
    var metadataToken by remember { mutableStateOf(prefs.tmdbReadToken) }
    var metadataRescanEpoch by remember { mutableIntStateOf(0) }
    val catalogIdentity = providerFilms?.joinToString("|") { "${it.id}:${it.originalDisplay?.title ?: it.title}" }
    var metadataForceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var metadataCorrection by remember { mutableStateOf<Film?>(null) }
    var metadataScanDone by remember { mutableIntStateOf(0) }
    var metadataScanTotal by remember { mutableIntStateOf(0) }
    LaunchedEffect(catalogScope) {
        facetActor = null; facetDirector = null; facetRegion = null
        facetYear = null; facetCategory = null; facetRating = null
    }
    LaunchedEffect(catalogScope, catalogIdentity, metadataToken, metadataRescanEpoch) {
        val eligible = providerFilms.orEmpty().take(240)
        metadataScanTotal = eligible.size
        metadataScanDone = eligible.count { it.metadataSource == "TMDB" }
        if (metadataToken.isBlank()) return@LaunchedEffect
        val unprocessed = eligible.filter { it.metadataSource != "TMDB" }
        for (batch in unprocessed.chunked(3)) {
            coroutineScope {
                batch.forEach { original ->
                    launch(Dispatchers.IO) {
                        val info = runCatching { metadataResolver.lookup(original, metadataToken, forceRefresh = original.id in metadataForceIds) }
                            .getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            if (info != null) {
                                providerFilms = providerFilms?.map { current ->
                                    if (current.id == original.id && current.metadataSource != "TMDB")
                                        FilmMetadataEnrichment.apply(current, info)
                                    else current
                                }
                            }
                            metadataForceIds = metadataForceIds - original.id
                            metadataScanDone++
                        }
                    }
                }
            }
        }
    }
    var favorites by remember(catalogScope) { mutableStateOf(prefs.idsFor(catalogScope)) }
    var reduced by remember { mutableStateOf(prefs.reducedMotion) }
    val systemReduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val reduceMotion = reduced || systemReduced
    var splash by rememberSaveable { mutableStateOf(true) }
    // Keep the finished H mark above the first home frames while it dissolves;
    // removing it before the hero is drawn causes a visible black flash.
    var splashExiting by remember { mutableStateOf(false) }
    val homeVisible = !splash || splashExiting
    val sceneReveal = remember { Animatable(0f) }
    var hasEnteredHome by remember { mutableStateOf(false) }
    LaunchedEffect(homeVisible, screen, category, reduceMotion) {
        if (!homeVisible) {
            sceneReveal.snapTo(0f)
        } else {
            val firstEntrance = !hasEnteredHome
            sceneReveal.snapTo(if (firstEntrance || reduceMotion) {
                if (reduceMotion) 1f else 0f
            } else 0.72f)
            if (!reduceMotion) sceneReveal.animateTo(1f, tween(
                durationMillis = if (firstEntrance) 460 else 260,
                easing = FastOutSlowInEasing))
            hasEnteredHome = true
        }
    }
    val railStates = (listOf("首页", "电影", "剧集", "动漫", "综艺", "纪录片", "直播", "我的", "search", "recommend")).associateWith { rememberLazyListState() }
    val facetResultState = rememberLazyListState()
    // M4.25: only the active, already-loaded catalog is grouped. This is a
    // display projection; Film.id and the licensed playback paths stay intact.
    val showCatalog = remember(catalogScope, films) { ShowCatalog.build(films, catalogScope) }
    val browseFilms = remember(showCatalog, films) { showCatalog.collapse(films) }
    val film = films.firstOrNull { it.id == filmId } ?: films.firstOrNull() ?: demoFilms.first()
    // Single-source fallback is deliberately presentation-only: the original
    // Film remains the same, and legacy detail/selection paths stay usable.
    val showGroup = showCatalog.groupFor(film.id).takeUnless {
        screen == "detail" && film.id == bypassGroupFilmId
    }
    val displayFilm = showGroup?.displayFilm ?: film
    fun displayKey(item: Film): String = showCatalog.groupFor(item.id)?.key ?: item.id
    // Playback-specific, observed Media3 track formats. Never sourced from a movie title or TMDB.
    val verifiedStreams = remember { mutableStateMapOf<String, VerifiedStreamSpec>() }
    LaunchedEffect(screen, filmId, spiderActive, showGroup?.key) {
        if (showGroup == null && spiderActive && screen == "detail" &&
            film.spiderVodId != null && film.playbackEpisodes.isEmpty()) {
            spiderStatus = "正在读取 ${film.title} 的详情和选集…"
            when (val loaded = spiderBridge.details(film)) {
                is SpiderResult.Ready -> {
                    providerFilms = providerFilms?.map { if (it.id == film.id) loaded.value else it }
                    spiderStatus = if (loaded.value.playbackEpisodes.isEmpty()) "该影片没有可用选集" else "已加载选集，请选择播放"
                }
                is SpiderResult.Failure -> spiderStatus = loaded.message
            }
        }
    }
    // Standard CMS responses often list only title/poster on ac=detail&pg=1.
    // Like TVBox, fetch ac=detail&ids=<vod_id> only when entering that film's
    // detail surface; no stream URL is inferred from the listing itself.
    LaunchedEffect(screen, filmId, activeCmsSite?.apiUrl, showGroup?.key) {
        val site = activeCmsSite
        if (showGroup == null && !spiderActive && screen == "detail" && site != null &&
            film.cmsVodId != null && film.playbackEpisodes.isEmpty()) {
            cmsDetailStatus = "正在读取 ${film.title} 的完整选集…"
            when (val result = tvBoxVodRepo.details(site, film, tvBoxAllowHttp)) {
                is TvBoxVodDetailResult.Ready -> {
                    providerFilms = providerFilms?.map { if (it.id == film.id) result.film else it }
                    cmsDetailStatus = if (result.film.playbackEpisodes.isEmpty())
                        "该节点未提供可直接播放的媒体地址" else "已取得完整选集"
                }
                is TvBoxVodDetailResult.Error -> cmsDetailStatus = "影视详情：${result.reason}"
            }
        }
    }
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
                        providerFilms = providerFilms?.map { if (it.id == current.id) loaded.value else it }
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
                        providerFilms = providerFilms?.map { if (it.id == current.id) loaded.film else it }
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
    LaunchedEffect(screen, film.id, episode, line, spiderActive, playerRetryEpoch) {
        spiderResolved = null
        if (spiderActive && screen == "player") {
            var playableFilm = film
            // Load missing episode metadata IN the full-screen loading surface.
            // Do not bounce a film-card click to a technical Spider detail page.
            if (film.playbackEpisodes.isEmpty() && film.spiderVodId != null) {
                when (val detailed = spiderBridge.details(film)) {
                    is SpiderResult.Ready -> {
                        playableFilm = detailed.value
                        providerFilms = providerFilms?.map {
                            if (it.id == film.id) detailed.value else it
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
                SpiderExitDiagnostics.recent(context)?.let { reason ->
                    if (spiderStatus == failedStatus) spiderStatus =
                        "$failedStatus；系统原因：$reason"
                }
            }
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
    var transferringSettingsFocus by remember { mutableStateOf(false) }
    fun moveCompactSettingsFocus(target: String) {
        val itemIndex = TvFocusRoutes.compactSettingsItemIndex(target) ?: return
        if (transferringSettingsFocus) return
        // Ordinary adjacent transitions stay synchronous and respond to a single D-pad press.
        if (settingsListState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex } &&
            runCatching { requester(target).requestFocus() }.isSuccess) return
        transferringSettingsFocus = true
        uiScope.launch {
            try {
                // Explicitly compose distant rows first; requesting an unattached field
                // otherwise drops the press on smaller television viewports.
                withTimeoutOrNull(1_100) {
                    settingsListState.animateScrollToItem(itemIndex)
                } ?: settingsListState.scrollToItem(itemIndex)
                withFrameNanos { }
                runCatching { requester(target).requestFocus() }
            } finally {
                transferringSettingsFocus = false
            }
        }
    }
    var transferringPosterFocus by remember { mutableStateOf(false) }
    fun transferPosterFocus(from: LazyListState, to: LazyListState,
                            fromIndex: Int, destination: List<Film>, prefix: String) {
        if (transferringPosterFocus || destination.isEmpty()) return
        transferringPosterFocus = true
        uiScope.launch {
            try {
                val index = fromIndex.coerceIn(0, destination.lastIndex)
                val sourceOffset = from.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == fromIndex }?.offset?.coerceAtLeast(0) ?: 0
                // Keep the destination at approximately the same horizontal screen
                // position. LazyRow may not have composed an off-screen target yet.
                val alreadyAligned = to.layoutInfo.visibleItemsInfo.any {
                    it.index == index && it.offset - sourceOffset in -24..24
                }
                if (!alreadyAligned) {
                    if (reduceMotion) to.scrollToItem(index, -sourceOffset)
                    else {
                        // Some TV Compose versions cannot settle an unattainable
                        // edge offset. Bound the animation and finish deterministically.
                        val reached = withTimeoutOrNull(1100) {
                            to.animateScrollToItem(index, -sourceOffset)
                            true
                        } ?: false
                        if (!reached) to.scrollToItem(index, -sourceOffset)
                    }
                }
                val target = "$prefix${displayKey(destination[index])}"
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { requester(target).requestFocus() }.isSuccess) {
                        RemoteTrace.event("RAIL transfer target=$target x=$sourceOffset")
                        return@launch
                    }
                }
                RemoteTrace.event("RAIL target unattached=$target")
            } finally {
                transferringPosterFocus = false
            }
        }
    }
    fun details(f: Film) {
        RemoteTrace.event("DETAILS invoked film=${f.id} from=$screen")
        cmsDetailStatus = ""
        filmId = f.id
        bypassGroupFilmId = null
        detailSelectedSeason = 0
        restoreSeasonFocus = false
        restoreGroupFocus = false
        groupFocusedCoordinates = null
        detailSeasonLines.clear()
        detailSeasonEpisodeNumbers.clear()
        episode = f.playbackEpisodes.firstOrNull()?.number ?: 1
        line = f.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
        returnScreen = screen
        screen = "detail"
    }
    fun openMovie(item: Film) {
        // Catalog artwork is now the primary "play" action, as on consumer TVs.
        // Grouped seasons retain a details/season-picker entry.
        if (providerFilms == null || showCatalog.groupFor(item.id) != null) {
            details(item)
            return
        }
        filmId = item.id
        val first = item.playbackEpisodes.firstOrNull()
        val option = first?.lines?.firstOrNull()
        when {
            first != null && option != null -> {
                episode = first.number
                line = option.id
                screen = "player"
            }
            spiderActive && item.spiderVodId != null -> {
                // Resolve missing episodes while the fullscreen loader is shown.
                episode = 1
                line = "pending"
                screen = "player"
            }
            else -> details(item)
        }
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
        playGroupSource(entry.preferred(mostRecent ?: selectedGroupOption))
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
            RemoteTrace.event("IME handoff search=$leavingSearchEditor settings=$leavingSettingsEditor acceptingText=${inputMethod.isAcceptingText} nativeFocus=${view.hasFocus()}")
        }
    }
    fun back() {
        when (screen) {
            "player" -> {
                restoreGroupFocus = showGroup != null
                restoreSeasonFocus = showGroup == null && seasonLayout != null
                screen = "detail"
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
            splash -> splashExiting = true
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
                RemoteTrace.event("RESTORE target=$id completed")
            }.onFailure {
                if (screen == "browse") runCatching { requester("nav-$category").requestFocus() }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        // Defer decoding and drawing the large hero until after splash: loading the
        // artwork underneath an opaque splash causes avoidable startup frame stalls.
        if (homeVisible && (!sourceConfigured || films.isNotEmpty())) {
            Crossfade(targetState = displayFilm, animationSpec = if (reduceMotion) tween(0)
                else tween(360, easing = FastOutSlowInEasing), label = "hero-art-crossfade") { hero ->
                FilmArtwork(hero, Modifier.fillMaxSize())
            }
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
            "facets" -> {
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
            "sites" -> {
                val selected = tvBoxSingle
                LazyColumn(Modifier.fillMaxSize().testTag("site-list"),
                    contentPadding = PaddingValues(horizontal = 44.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    item { TvButton("‹ 返回设置", Modifier.testTag("screen-first").focusRequester(requester("screen-first")), reduceMotion) { back() } }
                    item { Text("影视站点", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                    item { Text("已导入全部影视点列表；不在导入时逐个启动插件。选择任意影视点后只加载该站点。Spider 站点会执行来源的第三方插件。", color = Color(0xFFBDC8D6)) }
                    item { Text(tvBoxStatus, Modifier.testTag("source-site-status"), color = Color(0xFFDEE5EE)) }
                    item { Text("说明：影视点包含云盘扫码或弹幕插件时，可能依赖 TVBox 专有的交互页面与服务。" +
                        "HERRIN 当前 Spider 子进程没有这些原生授权弹窗；不应把提示当成已登录。" +
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
                                site.spiderApi != null && selected != null -> uiScope.launch {
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
            "browse", "search" -> {
                val isSearch = screen == "search"
                val filtered = if (isSearch) showCatalog.search(
                    query, searchCategory, searchNewestFirst
                ) else if (category == "我的") showCatalog.favorites(favorites)
                else showCatalog.collapse(films.filter { category == "首页" || it.category == category })
                // History drives the first shelf only for films with actual saved playback.
                // On a fresh installation the same rail shows browseable demo suggestions.
                val watched = if (!isSearch && category == "首页") filtered.mapNotNull { shown ->
                    showCatalog.watchProgress(shown) { source, entry, option ->
                        watchHistory.load(catalogScope, source.id, entry.number, option.id)
                    }?.let { shown to it.updatedAtMs }
                }.sortedByDescending { it.second }.map { it.first }
                    else emptyList()
                val railFilms = if (watched.isNotEmpty()) watched else filtered
                val railState = railStates.getValue(if (isSearch) "search" else category)
                val recommendedFilms = if (!isSearch && category == "首页") browseFilms.asReversed() else emptyList()
                val recommendState = railStates.getValue("recommend")
                val browseScroll = rememberScrollState()
                LaunchedEffect(category) { browseScroll.scrollTo(0) }
                val suggestions = listOf("轨道", "潮汐", "群山", "星夜", "清空")
                val headerDownTarget = when {
                    isSearch -> "search-input"
                    category == "直播" -> if (liveChannels.isEmpty()) "live-config" else "live-group-all"
                    sourceConfigured && films.isEmpty() -> null
                    films.isNotEmpty() -> "play"
                    else -> null
                }?.let { requester(it) }
                // Never observe LazyRow.layoutInfo during composition: each
                // layout pass invalidates it, leaving Compose perpetually busy.
                val visibleFirstIndex by remember(railState) {
                    derivedStateOf { railState.firstVisibleItemIndex }
                }
                val firstPosterTarget = railFilms.getOrNull(visibleFirstIndex)
                    ?.let { requester("card-${displayKey(it)}") }
                val sceneLift = with(LocalDensity.current) { 9.dp.toPx() }
                Column(Modifier.fillMaxSize().testTag("browse-scroll").graphicsLayer {
                    alpha = sceneReveal.value
                    translationY = (1f - sceneReveal.value) * sceneLift
                }.verticalScroll(browseScroll).padding(horizontal = 40.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(34.dp).border(1.dp, Color(0xA5EFF2F8), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center) {
                                Text("H", fontSize = 21.sp, fontWeight = FontWeight.Light, color = Color.White)
                            }
                            Text("HERRIN", letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        }
                        TvNavTabs(category, isSearch, focusId, reduceMotion, headerDownTarget,
                            { id -> requester(id) },
                            onFocus = { focusId = it }) { selected ->
                            category = selected
                            if (selected == "直播" && liveChannels.isNotEmpty()) {
                                if (activeLiveChannel?.url !in liveChannels.map { it.url }) {
                                    activeLiveChannel = liveChannels.first()
                                }
                                screen = "live-player"
                            } else screen = "browse"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TvButton("搜索", Modifier.testTag("search")
                                .then(if (headerDownTarget != null) Modifier.focusProperties { down = headerDownTarget } else Modifier)
                                .focusRequester(requester("search")), reduceMotion, quiet = true,
                                onFocus = { focusId = "search" }) { screen = "search" }
                            TvButton("筛选", Modifier.testTag("facets")
                                .then(if (headerDownTarget != null) Modifier.focusProperties { down = headerDownTarget } else Modifier)
                                .focusRequester(requester("facets")), reduceMotion, quiet = true,
                                onFocus = { focusId = "facets" }) {
                                facetsReturnScreen = screen
                                focusId = "screen-first"
                                screen = "facets"
                            }
                            TvButton("设置", Modifier.testTag("settings")
                                .then(if (headerDownTarget != null) Modifier.focusProperties { down = headerDownTarget } else Modifier)
                                .focusRequester(requester("settings")), reduceMotion,
                                quiet = true, onFocus = { focusId = "settings" }) {
                                settingsTvBoxOnly = true
                                screen = "settings"
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (isSearch) {
                        Text(if (providerFilms == null) "搜索 · 离线演示片库" else "搜索 · 当前授权目录", fontSize = 24.sp)
                        OutlinedTextField(query, { query = it }, Modifier.testTag("search-input")
                            .focusProperties { down = requester("search-filter-全部") }
                            .focusRequester(requester("search-input"))
                            .onFocusChanged { searchInputFocused = it.isFocused; RemoteTrace.event("INPUT focus=${it.isFocused}") }
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
                        }
                        Text("当前筛选：$searchCategory · ${filtered.size} 个结果",
                            modifier = Modifier.testTag("search-filter-status"),
                            fontSize = 12.sp, color = Color(0xFFC4CDDC))
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
                    } else if (sourceConfigured && films.isEmpty()) {
                        Spacer(Modifier.height(135.dp))
                        Text("影视源已添加", fontSize = 38.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("source-empty-title"))
                        Text(providerStatus + "；请进入设置选择站点", fontSize = 15.sp, lineHeight = 23.sp,
                            color = Color(0xFFCDD8E6), modifier = Modifier.testTag("source-empty-status"))
                        Spacer(Modifier.height(24.dp))
                    } else {
                        Spacer(Modifier.height(132.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(18.dp).height(2.dp).background(Color.White))
                            Text(if (providerFilms == null) "HERRIN  ·  ORIGINAL PREMIERE" else "HERRIN  ·  USER CATALOG", fontSize = 12.sp, letterSpacing = 2.sp,
                                fontWeight = FontWeight.Medium, color = Color(0xFFE5EAF2))
                        }
                        Spacer(Modifier.height(11.dp))
                        Text(displayFilm.title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp, color = Color.White, lineHeight = 49.sp)
                        Text(displayFilm.subtitle, fontSize = 13.sp, letterSpacing = 3.sp,
                            color = Color(0xFFE4E7EF))
                        Spacer(Modifier.height(12.dp))
                        Text("${displayFilm.year}   ·   ${displayFilm.category}   ·   ${if (providerFilms == null) "ORIGINAL UI DEMO" else "USER-SUPPLIED CATALOG"}",
                            fontSize = 13.sp, color = Color(0xFFF3F5F8))
                        if (displayFilm.ratingValue != null || displayFilm.genres.isNotEmpty())
                            Text(listOfNotNull(FilmMetadataEnrichment.ratingLabel(displayFilm),
                                displayFilm.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() })
                                .joinToString("   ·   "), fontSize = 13.sp,
                                color = Color(0xFFE8CDA0), modifier = Modifier.testTag("hero-metadata"))
                        Text(displayFilm.synopsis, Modifier.widthIn(max = 445.dp).padding(vertical = 6.dp),
                            fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2,
                            color = Color(0xFFE0E4EA))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(if (providerFilms == null) "▶ 播放演示" else if (film.playbackEpisodes.isNotEmpty()) "▶ 播放" else "查看选集",
                                selected = true, reduced = reduceMotion, onFocus = { focusId = "play" },
                                modifier = Modifier.testTag("play")
                                    .then(if (firstPosterTarget != null) Modifier.focusProperties { down = firstPosterTarget } else Modifier)
                                    .focusRequester(requester("play"))) {
                                if (showGroup != null || providerFilms != null && film.playbackEpisodes.isEmpty()) details(film)
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
                        Text(when { isSearch -> "搜索结果 · ${filtered.size}"; category == "直播" -> "直播频道"; category == "我的" -> "我的收藏"; category == "首页" -> if (sourceConfigured) "来自影视源 · ${filtered.size} 部" else "继续观看"; else -> if (sourceConfigured) "${category} · ${filtered.size} 部" else "精选${category} · 演示片库" },
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text(if (sourceConfigured) "${providerStatus.take(48)}" else if (!isSearch && category == "首页") "精选首页 · 演示片库" else "DISCOVER   /   HERRIN",
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
                    else if (filtered.isEmpty()) Text(if (isSearch) "没有找到匹配影片" else "暂无收藏，进入影片详情添加", Modifier.padding(24.dp))
                    else LazyRow(state = railState, contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(railFilms, key = { _, item -> displayKey(item) }) { index, item ->
                            // Runs after this lazy item's focus target is attached, including Back from detail.
                            LaunchedEffect(item.id) {
                                if (isSearch && focusId == "card-${displayKey(item)}") {
                                    requester("card-${displayKey(item)}").requestFocus()
                                    RemoteTrace.event("RESTORE search card=${displayKey(item)} attached")
                                }
                            }
                            val homeRail = !isSearch && category == "首页" && recommendedFilms.isNotEmpty()
                            Poster(item, Modifier.testTag("card-${displayKey(item)}")
                                .focusProperties {
                                    up = requester(when {
                                        isSearch -> "search-suggest-${suggestions[index.coerceAtMost(suggestions.lastIndex)]}"
                                        index == 0 -> "play"
                                        else -> "details"
                                    })
                                    down = if (homeRail) requester("recommend-${displayKey(recommendedFilms[index.coerceAtMost(recommendedFilms.lastIndex)])}")
                                        else FocusRequester.Cancel
                                }
                                .onPreviewKeyEvent { event ->
                                    if (homeRail && event.type == KeyEventType.KeyDown &&
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                        uiScope.launch {
                                            if (reduceMotion) browseScroll.scrollTo(browseScroll.maxValue)
                                            else browseScroll.animateScrollTo(browseScroll.maxValue)
                                            transferPosterFocus(railState, recommendState, index,
                                                recommendedFilms, "recommend-")
                                        }
                                        true
                                    } else false
                                }
                                .focusRequester(requester("card-${displayKey(item)}")), reduceMotion,
                                onFocus = { filmId = item.id; focusId = "card-${displayKey(item)}" }, onClick = { openMovie(item) },
                                progress = if (!isSearch && category == "首页") {
                                    showCatalog.watchProgress(item) { source, part, option ->
                                        watchHistory.load(catalogScope, source.id, part.number, option.id)
                                    }?.fraction
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
                                Poster(item, Modifier.testTag("recommend-${displayKey(item)}")
                                    .focusProperties {
                                        up = requester("card-${displayKey(railFilms[index.coerceAtMost(railFilms.lastIndex)])}")
                                        down = FocusRequester.Cancel
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            uiScope.launch {
                                                if (reduceMotion) browseScroll.scrollTo(0)
                                                else browseScroll.animateScrollTo(0)
                                                transferPosterFocus(recommendState, railState, index,
                                                    railFilms, "card-")
                                            }
                                            true
                                        } else false
                                    }
                                    .focusRequester(requester("recommend-${displayKey(item)}")), reduceMotion,
                                    onFocus = { filmId = item.id; focusId = "recommend-${displayKey(item)}" },
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
            "detail", "player", "settings" -> {
                val licensed = if (spiderActive && screen == "player") spiderResolved
                    else if (screen == "player" && film.playbackEpisodes.isNotEmpty())
                    LicensedPlaybackResolver.resolve(film, episode, line) else null
                val licensedMedia = (licensed as? PlaybackResult.Ready)?.media
                val testMedia = if (screen == "player" && !spiderActive) AuthorizedUrls.https(prefs.testMediaUrl) else null
                val chosenMedia = licensedMedia ?: testMedia?.let { PlaybackMedia(it) }
                if (chosenMedia != null) {
                    val historyLine = if (licensedMedia != null) line else "independent-test"
                    val previous = watchHistory.load(catalogScope, film.id, episode, historyLine)
                    AuthorizedPlaybackPanel(
                        media = chosenMedia,
                        startPositionMs = previous?.positionMs ?: 0L,
                        onLineSelection = if (film.playbackEpisodes.isNotEmpty()) ({ playbackPicker = "lines" }) else null,
                        onEpisodeSelection = if (film.playbackEpisodes.isNotEmpty()) ({ playbackPicker = "episodes" }) else null,
                        onMediaSpecDetected = { verifiedStreams[streamEvidenceKey] = it },
                        onPlaybackProgress = { position, duration, finished ->
                            watchHistory.update(catalogScope, film.id, episode, historyLine,
                                position, duration, finished)
                        },
                        onBack = {
                            restoreGroupFocus = showGroup != null
                            restoreSeasonFocus = showGroup == null && seasonLayout != null
                            screen = "detail"
                        }
                    )
                    if (playbackPicker != null) PlaybackSelectionDialog(
                        film, episode, line, playbackPicker == "lines",
                        onSelect = { selectedEpisode, selectedLine ->
                            if (selectedEpisode == episode && selectedLine != line) {
                                watchHistory.load(catalogScope, film.id, episode, line)?.let { saved ->
                                    watchHistory.update(catalogScope, film.id, episode, selectedLine,
                                        saved.positionMs, saved.durationMs)
                                }
                            }
                            episode = selectedEpisode
                            line = selectedLine
                            playbackPicker = null
                        }, onDismiss = { playbackPicker = null })
                } else if (screen == "player") {
                    // Playback-first: never expose Spider/JAR resolution as an intermediate detail view.
                    val playbackUnavailable = spiderResolved is PlaybackResult.Unavailable || !spiderActive
                    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))
                        .testTag("player-startup-surface"),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (!playbackUnavailable) {
                                CircularProgressIndicator(color = Color.White,
                                    modifier = Modifier.size(40.dp))
                                Text("正在打开影片…", fontSize = 21.sp,
                                    modifier = Modifier.testTag("player-startup-label"))
                            } else {
                                Text("暂时无法播放", fontSize = 24.sp,
                                    modifier = Modifier.testTag("player-startup-error"))
                                Text("请重试，或返回选集切换播放线路。",
                                    color = Color(0xFFBCCADB), fontSize = 16.sp)
                                if (spiderActive) {
                                    TvButton("重新播放", modifier = Modifier.testTag("player-startup-retry"),
                                        reduced = reduceMotion) { playerRetryEpoch++ }
                                }
                            }
                            TvButton("返回选集", modifier = Modifier.testTag("player-startup-back"),
                                reduced = reduceMotion) { screen = "detail" }
                        }
                    }
                } else LazyColumn(Modifier.testTag("settings-list").fillMaxSize()
                    .background(Color(0xAA080B10))
                    .onPreviewKeyEvent { event ->
                        if (screen != "settings" || !settingsTvBoxOnly ||
                            event.type != KeyEventType.KeyDown) false
                        else {
                            val code = event.nativeKeyEvent.keyCode
                            val target = when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                                    TvFocusRoutes.compactSettingsNeighbor(focusId, false, lastMovieSettingsAction)
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                                    TvFocusRoutes.compactSettingsNeighbor(focusId, true, lastMovieSettingsAction)
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
                    item { TvButton("‹ 返回", Modifier.testTag("screen-first").focusRequester(requester("screen-first")),
                        reduceMotion, onFocus = { if (screen == "settings") focusId = "screen-first" }) { back() } }
                    if (screen == "settings") {
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
                            item { Text(tvBoxStatus, Modifier.testTag("compact-tvbox-status"),
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
                                else "已开启 TMDB 智能刮削：已处理 $metadataScanDone/$metadataScanTotal，" +
                                    "匹配 ${providerFilms.orEmpty().count { it.metadataSource == "TMDB" }} 部。" +
                                    "保留原影视源的播放地址和选集。",
                                modifier = Modifier.testTag("compact-metadata-status"),
                                color = Color(0xFFBFD6ED), lineHeight = 21.sp) }
                            item { OutlinedTextField(tmdbTokenDraft, { tmdbTokenDraft = it.take(500) },
                                Modifier.fillMaxWidth().testTag("compact-tmdb-token"),
                                label = { Text("TMDB API Read Access Token（粘贴后保存）") },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvButton("保存并开始刮削",
                                    modifier = Modifier.testTag("compact-tmdb-save"), reduced = reduceMotion) {
                                    val value = tmdbTokenDraft.trim()
                                    if (value.length >= 40 && !value.any(Char::isWhitespace)) {
                                        prefs.tmdbReadToken = value
                                        metadataToken = value
                                        tmdbTokenDraft = ""
                                        metadataRescanEpoch++
                                        tmdbSettingsStatus = "TMDB 已保存并开始匹配影视资料"
                                    } else tmdbSettingsStatus = "需要完整的 TMDB API Read Access Token"
                                }
                                TvButton("重新刮削",
                                    modifier = Modifier.testTag("compact-tmdb-rescan"), reduced = reduceMotion) {
                                    if (metadataToken.isNotBlank()) {
                                        metadataForceIds = providerFilms.orEmpty().map { it.id }.toSet()
                                        providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                        metadataRescanEpoch++
                                        tmdbSettingsStatus = "正在重新匹配当前影视点的元数据"
                                    } else tmdbSettingsStatus = "请先配置 TMDB Token"
                                }
                            } }
                            item { Text(tmdbSettingsStatus, color = Color(0xFFCFD7E4),
                                modifier = Modifier.testTag("compact-tmdb-feedback")) }
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
                                onMoveUp = { moveCompactSettingsFocus(lastMovieSettingsAction) },
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
                                                        tvBoxStatus = "已读取 ${loaded.films.size} 部影片；可直接播放的 HTTPS 线路会显示在影片详情中"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } }
                            }
                            val spiderSites = preview.sites.filter { it.spiderApi != null }
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
                        item { OutlinedTextField(tmdbTokenDraft, { tmdbTokenDraft = it.take(500) },
                            Modifier.fillMaxWidth().testTag("tmdb-token-input")
                                .onFocusChanged { configInputFocused = it.isFocused },
                            label = { Text("TMDB API Read Access Token（留空则不修改）") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("保存 TMDB Token", modifier = Modifier.testTag("tmdb-token-save"),
                                reduced = reduceMotion, onFocus = { keyboard?.hide() }) {
                                val value = tmdbTokenDraft.trim()
                                if (value.isNotEmpty() && value.length >= 40 && !value.any(Char::isWhitespace)) {
                                    prefs.tmdbReadToken = value
                                    metadataToken = value
                                    tmdbTokenDraft = ""
                                    tmdbSettingsStatus = "已保存；正在匹配海报与影视资料"
                                } else tmdbSettingsStatus = "请输入完整的 TMDB API Read Access Token"
                            }
                            TvButton("停用高清刮削", modifier = Modifier.testTag("tmdb-token-clear"),
                                reduced = reduceMotion) {
                                prefs.tmdbReadToken = ""
                                metadataToken = ""
                                providerFilms = providerFilms?.map(FilmMetadataEnrichment::restore)
                                tmdbTokenDraft = ""
                                tmdbSettingsStatus = "已停用 TMDB 网络匹配，改用源内海报"
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

                        item { Text("HERRIN M4.25 Alpha · 原生 Android TV\n影视播放源只提供播放线路，TMDB 独立补充海报、简介、评分、演职员头像、上映日期、片长与影片分类。\n影视首页右上角「筛选」可按 TMDB 评分、演员、导演、地区及年份组合筛选。\n当前影视点内，同一作品被拆成多个季条目时可整合为统一海报、季标签和16:9单集卡；原始来源和播放线路均保留。\n跨不同影视点的内容尚未统一扫描整合；缺乏可靠季集标签时可进入原始条目。\n详情页媒体规格严格区分影视源标称与播放器已识别格式，未知内容播放后再识别。\n在高级设置配置个人 TMDB API Token 后启用刮削；不采集未经授权的豆瓣评分。\n直播上下键换台，选频道即播；TVBox 网页解析/受保护线路尚未保证兼容。\n方向键移动 · 确定选择 · 返回上一级", lineHeight = 28.sp) }
                        }
                    } else {
                        item { Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text(if (screen == "player" && !spiderActive) "播放演示"
                                else if (screen == "detail") displayFilm.title else film.title,
                                fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            Text(buildString {
                                FilmMetadataEnrichment.ratingLabel(displayFilm)?.let { append(it).append("  ·  ") }
                                append(displayFilm.releaseDate ?: displayFilm.year).append("  ·  ")
                                append(displayFilm.category)
                                displayFilm.runtimeMinutes?.let {
                                    append("  ·  ")
                                    append(if (displayFilm.category == "剧集" || displayFilm.category == "动漫") "单集约 " else "资料片长 ")
                                    append(it).append(" 分钟")
                                }
                                if (displayFilm.genres.isNotEmpty()) append("\n类型：").append(displayFilm.genres.joinToString(" · "))
                                if (displayFilm.regions.isNotEmpty()) append("\n国家 / 地区：").append(displayFilm.regions.joinToString(" · "))
                                if (displayFilm.directors.isNotEmpty()) append("\n导演 / 主创：").append(displayFilm.directors.joinToString(" · "))
                            }, lineHeight = 25.sp, modifier = Modifier.testTag("detail-metadata"))
                        } }
                        if (providerFilms != null) item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvButton("纠正影片资料", Modifier.testTag("metadata-correct")) { metadataCorrection = film }
                                TvButton("重新刮削本片", Modifier.testTag("metadata-rescrape-film")) {
                                    metadataForceIds = setOf(film.id)
                                    providerFilms = providerFilms?.map { if (it.id == film.id) FilmMetadataEnrichment.restore(it) else it }
                                    metadataRescanEpoch++
                                }
                            }
                        }
                        item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("画质与音效", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            val spec = verifiedStreams[streamEvidenceKey]
                            val claims = SourceMediaClaims.labels(film.title,
                                currentLines.firstOrNull { it.id == line }?.title.orEmpty())
                            if (spec?.isKnown == true) Text(
                                "播放器已识别轨道 · ${spec.labels().joinToString("  ·  ")}",
                                modifier = Modifier.testTag("detail-media-spec"),
                                color = Color(0xFFBCE2EF), fontSize = 17.sp)
                            if (claims.isNotEmpty()) Text(
                                "影视源标称：${claims.joinToString(" · ")}（未经播放验证）",
                                modifier = Modifier.testTag("detail-source-claims"),
                                color = Color(0xFFE5C58E), fontSize = 16.sp)
                            if (spec?.isKnown != true && claims.isEmpty()) Text(
                                "清晰度 / HDR / 杜比音效：播放后识别",
                                modifier = Modifier.testTag("detail-media-spec"),
                                color = Color(0xFFBCE2EF), fontSize = 17.sp)
                            Text("规格以实际选中媒体轨道为依据，不代表电视已成功硬解或面板最终输出；未知时不猜测。",
                                color = Color(0xFF8FA3B9), fontSize = 13.sp)
                        } }
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
                                            .onFocusChanged { personFocused = it.isFocused }
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
                                modifier = Modifier.testTag("detail-people"), color = Color(0xFFD7E0EA))
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
                            item { Text(cmsDetailStatus, color = Color(0xFFE2C38B),
                                modifier = Modifier.testTag("cms-detail-status")) }
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
                                TvButton(when {
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
                            if (showGroup == null && resume != null && seasonLayout == null) {
                                item { Text("继续播放 · 已观看 ${resume.positionMs / 1000} 秒 / ${resume.durationMs / 1000} 秒",
                                    fontSize = 17.sp, color = Color(0xFFD4E6FD)) }
                                item { TvButton("从头播放", reduced = reduceMotion) {
                                    watchHistory.clear(catalogScope, film.id, episode,
                                        if (currentLines.isEmpty()) "independent-test" else line)
                                    screen = "player"
                                } }
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
                                        onLinePlay = ::playGroupSource)
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
                                        }, onPlay = { entry -> playSeasonEpisode(entry) })
                                }
                            } else {
                                item { Text(if (providerFilms != null && film.playbackEpisodes.isEmpty())
                                    "原始条目 · 尚无可播放的真实选集"
                                    else if (film.playbackEpisodes.isEmpty()) "演示选集" else "授权选集",
                                    fontSize = 20.sp) }
                                if (providerFilms != null && film.playbackEpisodes.isEmpty()) item {
                                    Text("当前影视点未返回有效的集数或线路；不会将演示集当作真实播放地址。",
                                        modifier = Modifier.testTag("source-no-playable-episodes"),
                                        color = Color(0xFFD9B78A), fontSize = 14.sp)
                                } else item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (film.playbackEpisodes.isEmpty()) {
                                        (1..film.episodes).forEach { n ->
                                            TvButton("第 $n 集", modifier = Modifier.testTag("episode-$n"), reduced = reduceMotion,
                                                selected = episode == n) {
                                                episode = n
                                                screen = "player"
                                            }
                                        }
                                    } else {
                                        film.playbackEpisodes.forEach { entry ->
                                            TvButton("第 ${entry.number} 集 · ${entry.title}",
                                                modifier = Modifier.testTag("episode-${entry.number}")
                                                    .onPreviewKeyEvent { event ->
                                                        if (event.type == KeyEventType.KeyDown &&
                                                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                                                            runCatching { requester("detail-first-line").requestFocus() }.isSuccess
                                                        else false
                                                    },
                                                reduced = reduceMotion, selected = episode == entry.number) {
                                                episode = entry.number
                                                line = entry.lines.first().id
                                                screen = "player"
                                            }
                                        }
                                    }
                                } }
                            }
                            item { Text("播放线路 · 按方向键下键选择",
                                modifier = Modifier.testTag("detail-line-heading"),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                            if (showGroup == null && (providerFilms == null || displayedLines.isNotEmpty())) item {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (displayedLines.isEmpty()) {
                                    listOf("演示线路 A", "演示线路 B").forEachIndexed { index, name ->
                                        TvButton(name, modifier = Modifier.testTag("line-$name")
                                            .then(if (index == 0) Modifier.focusRequester(requester("detail-first-line")) else Modifier),
                                            reduced = reduceMotion, selected = line == name) { line = name; screen = "player" }
                                    }
                                } else {
                                    displayedLines.forEachIndexed { index, option ->
                                        TvButton(option.title, modifier = Modifier.testTag("line-${option.id}")
                                            .then(if (index == 0) Modifier.focusRequester(requester("detail-first-line")) else Modifier), reduced = reduceMotion,
                                            selected = line == option.id) {
                                            line = option.id
                                            detailSeason?.let { detailSeasonLines[it.number] = option.id }
                                            screen = "player"
                                        }
                                    }
                                }
                                }
                            }
                            item { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("剧情简介", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                Text(displayFilm.synopsis.ifBlank { "暂未取得可靠剧情简介" },
                                    modifier = Modifier.testTag("detail-synopsis"), lineHeight = 26.sp,
                                    color = Color(0xFFE1E8F2))
                                if (displayFilm.metadataSource == "TMDB") Text("资料由 TMDB 提供；播放线路由你添加的影视源提供。",
                                    fontSize = 13.sp, color = Color(0xFF9BAEC2))
                            } }
                            if (showGroup != null && unmappedGroupMembers.isNotEmpty()) item {
                                GroupRawSourceChooser(unmappedGroupMembers, reduceMotion) { original ->
                                    bypassGroupFilmId = original.id
                                    filmId = original.id
                                    episode = original.playbackEpisodes.firstOrNull()?.number ?: 1
                                    line = original.playbackEpisodes.firstOrNull()?.lines?.firstOrNull()?.id ?: "演示线路 A"
                                    restoreGroupFocus = false
                                }
                            }

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
        if (splash) {
            SplashExitCurtain(reduceMotion, splashExiting,
                onReady = { splashExiting = true }, onFinished = { splash = false })
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
    onPlay: (SeasonEpisode) -> Unit
) {
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
                                    rowState.scrollToItem(0)
                                    repeat(3) {
                                        withFrameNanos { }
                                        if (runCatching {
                                                focusAt("season-card-${section.number}-0").requestFocus()
                                            }.isSuccess) return@launch
                                    }
                                }
                                true
                            } else false
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
                        .focusRequester(focusAt("season-card-${entry.season}-$index"))
                        .focusProperties { up = focusAt("season-tab-${entry.season}") },
                    onFocus = { onEpisodeFocus(entry) },
                    onClick = { onPlay(entry) })
            }
        }
    }
}

/** The static H remains on top until the destination has had time to render. */
@Composable
internal fun SplashExitCurtain(reduced: Boolean, exiting: Boolean,
                               onReady: () -> Unit, onFinished: () -> Unit) {
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(exiting, reduced) {
        if (exiting) {
            if (reduced) opacity.snapTo(0f)
            else opacity.animateTo(0f, tween(460, easing = FastOutSlowInEasing))
            onFinished()
        }
    }
    Box(Modifier.fillMaxSize().testTag("splash-curtain").graphicsLayer { alpha = opacity.value }) {
        Splash(reduced, onReady)
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
        if (reduced) snap() else tween(200, easing = FastOutSlowInEasing),
        label = "poster-focus-lift")
    val stroke by animateColorAsState(if (focused) Color(0xFFF6F7FA) else Color(0x665E6A78),
        if (reduced) snap() else tween(190), label = "poster-focus-outline")
    val shape = RoundedCornerShape(13.dp)
    val liftPx = with(LocalDensity.current) { 1.dp.toPx() }
    Box(modifier.width(217.dp).height(116.dp)
        .graphicsLayer { translationY = -lift * liftPx }
        .onFocusChanged {
            focused = it.isFocused
            RemoteTrace.event("POSTER ${film.id} focus=$focused")
            if (it.isFocused) onFocus()
        }
        .border(if (focused) 2.dp else 1.dp, stroke, shape)
        .clip(shape).background(Color(0xFF101821))
        .onPreviewKeyEvent { event ->
            // Keep native Compose clickable as the single source of key activation.
            RemoteTrace.event("POSTER ${film.id} key=${event.nativeKeyEvent.keyCode} type=${event.type} focus=$focused")
            false
        }
        .clickable { RemoteTrace.event("POSTER ${film.id} CLICK clickable"); onClick() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("poster-image-${film.id}"),
                contentAlignment = Alignment.Center) {
                PosterArtwork(film, Modifier.fillMaxSize(), reduced)
            }
            Row(Modifier.fillMaxWidth().height(29.dp)
                .background(Color(0xF0121822))
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(film.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                Text(film.year, fontSize = 10.sp, color = Color(0xFFD3D8E1), maxLines = 1)
            }
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

@Composable
internal fun Splash(reduced: Boolean, done: () -> Unit) {
    val collapse = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(reduced) {
        focus.requestFocus()
        if (reduced) { done(); return@LaunchedEffect }
        // A gentle wordmark reveal precedes a staggered, eased collapse to H.
        // Total is roughly 2.7 s, with no sudden full-screen replacement.
        reveal.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        delay(150)
        collapse.animateTo(1f, tween(durationMillis = 1550, easing = FastOutSlowInEasing))
        delay(650)
        done()
    }
    val collapsed by remember { derivedStateOf { collapse.value >= 0.999f } }
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
        Canvas(Modifier.size(330.dp)) {
            drawCircle(Brush.radialGradient(listOf(
                Color(0x1DFFFFFF).copy(alpha = 0.11f * collapse.value), Color.Transparent)),
                radius = size.minDimension / 2f)
        }
        // All movement is in GPU layers; H settles at the true center without
        // relayout. Trailing letters fade in reverse order as the mark closes.
        val cell = minOf(94.dp, (maxWidth - 80.dp) / 6)
        val stepPx = with(LocalDensity.current) { cell.toPx() }
        "HERRIN".forEachIndexed { index, letter ->
            if (index == 0 || !collapsed) {
                Text(letter.toString(), modifier = Modifier.width(cell).graphicsLayer {
                    val progress = collapse.value
                    translationX = (index - (5f - 5f * progress) / 2f) * stepPx
                    alpha = if (index == 0) 1f else reveal.value *
                        (1f - ((5f * progress - (5f - index)) / 1.1f).coerceIn(0f, 1f))
                    if (index == 0) {
                        scaleX = 1f + 0.035f * progress
                        scaleY = scaleX
                    }
                }, textAlign = TextAlign.Center, fontSize = (cell.value * 0.91f).sp,
                    fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}
