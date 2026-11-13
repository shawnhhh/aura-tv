package tv.herrin.app

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Real MainActivity/warehouse/search/details/player. No injected film, server, URI or player. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class FanTaiYingRealMainUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val warehouse = "http://www.饭太硬.net/tv"
    private val title = InstrumentationRegistry.getArguments().getString("herrinFilmTitle", "小城良方")
        .takeIf { it == "小城良方" || it == "蜡笔小新" } ?: "小城良方"
    private val expectedYear: Int? = if (title == "小城良方") 2026 else null
    private val stages = JSONArray()
    private lateinit var report: JSONObject
    private var currentStage = "config"
    private var siteKey: String? = null
    private val bytes = AtomicLong()
    private val observed = java.util.IdentityHashMap<ExoPlayer, androidx.media3.exoplayer.analytics.AnalyticsListener>()
    private var lastVerifiedPlayer: ExoPlayer? = null
    private class Stop(val code: String) : Exception(code)

    @get:Rule(order = 0) val disposableDevice = object : ExternalResource() {
        override fun before() {
            check(InstrumentationRegistry.getArguments().getString("herrinDisposableDevice") == "true") {
                "DISPOSABLE_DEVICE_CONFIRMATION_REQUIRED"
            }
            context.getSharedPreferences("herrin", 0).edit().clear().commit()
            MetadataFetchers.overrideForTests = null
            File(context.filesDir, "tvbox-config").deleteRecursively()
            for (name in listOf("spider-search-shape.json", "spider-proxy-shapes.json", "spider-failure-structure.txt", "spider-network-shape.json"))
                File(context.filesDir, name).delete()
            HerrinPlaybackDiagnosticJournal.clear()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun exists(tag: String) = runCatching { compose.onNodeWithTag(tag).assertExists() }.isSuccess
    private fun displayed(tag: String) = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
    private fun key(code: Int) = inst.sendKeyDownUpSync(code)
    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        try { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess } }
        catch (_: ComposeTimeoutException) { throw Stop("DRIVER_CONTROL_FOCUS_NOT_RETAINED") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
    private fun awaitIme(shown: Boolean) {
        compose.waitUntil(5_000) {
            val state = inst.uiAutomation.executeShellCommand("dumpsys input_method").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
            }.substringAfter("Input method service state for ", "")
            state.contains("mInputViewStarted=$shown") && state.contains("mWindowVisible=$shown")
        }
    }
    private fun hideIme() {
        compose.runOnUiThread { compose.activity.window.decorView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        awaitIme(false)
    }
    private fun journalCount(code: String): Int? = Regex("SPIDER $code COUNT=([0-9]+)")
        .findAll(HerrinPlaybackDiagnosticJournal.export()).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private fun qr(back: Boolean) {
        compose.waitUntil(5_000) { displayed("spider-authorization-qr") }
        val request = SpiderAuthorizationUi.requests.value ?: throw Stop("AUTH_REQUEST_NOT_CURRENT")
        val pixels = compose.onNodeWithTag("spider-authorization-qr").captureToImage().toPixelMap()
        val colors = IntArray(pixels.width*pixels.height) { i ->
            val c = pixels[i%pixels.width,i/pixels.width]
            android.graphics.Color.rgb((c.red*255).toInt(),(c.green*255).toInt(),(c.blue*255).toInt())
        }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(pixels.width,pixels.height,colors))))
        check(decoded.barcodeFormat == BarcodeFormat.QR_CODE && decoded.text.isNotBlank())
        check(SpiderAuthorizationUi.requests.value === request)
        request.options.qr?.let { check(decoded.text == it) }
        if (back) key(KeyEvent.KEYCODE_BACK) else compose.onNodeWithTag("spider-authorization-cancel").performClick()
        compose.waitUntil(5_000) { SpiderAuthorizationUi.requests.value == null }
    }

    private fun step(name: String, budget: Long = 45_000, afterCancelTag: String,
                     action: () -> Unit = {}, retry: () -> Unit = action, done: () -> Boolean) {
        currentStage = name
        val start = SystemClock.elapsedRealtime()
        val row = JSONObject().put("stage",name)
        stages.put(row)
        try {
            action()
            // Compose LaunchedEffect delays use the test frame clock. A raw
            // Thread.sleep loop freezes the production search debounce even
            // while wall time passes. Media3/60s evidence still uses real time.
            val remaining = budget - (SystemClock.elapsedRealtime()-start)
            if (remaining <= 0) throw Stop("STAGE_TIMEOUT")
            try { compose.waitUntil(remaining) {
                if (SpiderAuthorizationUi.requests.value != null) {
                    qr(false)
                    row.put("qr_decoded",true).put("cancel_pass",true)
                    restoreAfterAuthorization(afterCancelTag)
                    retry()
                    try { compose.waitUntil(30_000) { SpiderAuthorizationUi.requests.value != null } }
                    catch (_: ComposeTimeoutException) { throw Stop("AUTH_BACK_REOPEN_NOT_OBSERVED") }
                    qr(true)
                    restoreAfterAuthorization(afterCancelTag)
                    row.put("back_pass",true).put("result","AUTH_FLOW_PASS_PLAYBACK_PENDING")
                    throw Stop("AUTH_FLOW_PASS_PLAYBACK_PENDING")
                }
                done()
            } } catch (_: ComposeTimeoutException) { throw Stop("STAGE_TIMEOUT") }
            row.put("result","PASS")
        } catch (error: Throwable) {
            if (!row.has("result")) row.put("result","FAILED").put("failure_code",if(error is Stop)error.code else error.javaClass.simpleName)
            throw error
        } finally { row.put("elapsed_ms",SystemClock.elapsedRealtime()-start) }
    }

    private fun findPlayer(view: View): ExoPlayer? {
        if (view is PlayerView) return view.player as? ExoPlayer
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
        return null
    }
    /** Read the existing production PlayerView; never construct, prepare, seek or release a player. */
    private fun <T> playerRead(read: (ExoPlayer) -> T): T? {
        var answer: T? = null
        var failure: Throwable? = null
        compose.runOnUiThread {
            try {
            val player = findPlayer(compose.activity.window.decorView) ?: return@runOnUiThread
            report.put("media3_created",true)
            if (!observed.containsKey(player)) {
                val listener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onLoadCompleted(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
                        if (mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA && loadEventInfo.bytesLoaded > 0)
                            bytes.addAndGet(loadEventInfo.bytesLoaded)
                    }
                }
                player.addAnalyticsListener(listener)
                observed[player] = listener
            }
            player.playerError?.let { throw Stop("MEDIA3_"+it.errorCodeName) }
            player.videoDecoderCounters?.ensureUpdated()
            answer = read(player)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { if(it is Stop)throw it else throw Stop("PLAYER_OBSERVATION_"+it.javaClass.simpleName) }
        return answer
    }
    private fun failProductionGate() {
        val code = Regex("SOURCE (MEDIA_PROBE_[A-Z_]+)").findAll(HerrinPlaybackDiagnosticJournal.export())
            .lastOrNull()?.groupValues?.get(1)
        if (code != null && code != "MEDIA_PROBE_MEDIA") {
            report.put("pipeline_failure_component","MEDIA_VALIDATION_BEFORE_MEDIA3")
            throw Stop(code)
        }
    }
    private fun recordEpisodeLabel(number: Int, tag: String) {
        val selections = report.optJSONArray("episode_selections") ?: JSONArray().also { report.put("episode_selections",it) }
        selections.put(JSONObject().put("source_ordinal",number).put("ui_label",text(tag).take(120)))
    }
    private fun episode(number: Int) {
        val list = compose.onNodeWithTag("film-detail-list")
        report.put("episode_selection_attempt",number).put("episode_driver_action","SCROLL_TO_EPISODES")
        list.performScrollToNode(hasTestTag("episode-$number") or hasTestTag("season-browser") or hasTestTag("group-season-browser"))
        if (exists("episode-$number")) {
            report.put("episode_ui_kind","SOURCE_EPISODE_BUTTON").put("episode_driver_action","ACTIVATE_EPISODE_BUTTON")
            recordEpisodeLabel(number,"episode-$number")
            activate("episode-$number"); return
        }
        val grouped = exists("group-season-browser")
        val row = if(grouped) "group-season-cards" else "season-cards"
        val prefix = if(grouped) "group-season-card-" else "season-card-"
        report.put("episode_ui_kind",if(grouped) "GROUPED_SEASON" else "SOURCE_SEASON")
            .put("episode_driver_action","SCROLL_EPISODE_ROW")
        compose.onNodeWithTag(row).performScrollToIndex(number-1)
        val cards = compose.onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes()
        val tag = cards.mapNotNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }
            .singleOrNull { it.endsWith("-${number-1}") } ?: throw Stop("REAL_EPISODE_UI_NOT_FOUND")
        report.put("episode_driver_action","ACTIVATE_SEASON_EPISODE_CARD")
        recordEpisodeLabel(number,tag)
        activate(tag)
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
    private fun authorizedTitleCard(): SemanticsMatcher {
        var matcher = hasTestTagPrefix("card-") and hasText(title, substring = true) and hasClickAction()
        if (title == "蜡笔小新") {
            for (excluded in listOf("剧场", "劇場", "电影", "電影", "Movie", "movie", "特别篇", "特別篇"))
                matcher = matcher and !hasText(excluded, substring = true)
        }
        return matcher
    }
    private fun returnDetails() {
        repeat(3) {
            if (displayed("film-detail-list")) return
            key(KeyEvent.KEYCODE_BACK)
            Thread.sleep(200)
        }
        compose.waitUntil(5_000) { displayed("film-detail-list") }
    }
    private fun restoreAfterAuthorization(tag: String) {
        if (displayed(tag)) return
        when (tag) {
            "film-detail-list" -> returnDetails()
            "site-list" -> {
                if (displayed("settings")) activate("settings")
                compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-source-sites"))
                activate("settings-source-sites")
            }
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun cheeseRealNode() = node("奶酪","cheese")
    @Test fun wanouGegeRealNode() = node("玩偶哥哥","wanou")

    private fun node(name: String, fileName: String) {
        val start = SystemClock.elapsedRealtime()
        report = JSONObject().put("node",name).put("warehouse",warehouse).put("authorized_title",title)
            .put("authorized_year",expectedYear ?: JSONObject.NULL).put("verdict","FAIL_PLAYBACK").put("stages",stages)
            .put("playback_host_scope","PRODUCTION_MAIN_ACTIVITY").put("first_frame",false)
            .put("media3_created",false)
            .put("seek_verified",false).put("details_ui_verified",false).put("plugin_initialized",false)
            .put("device",JSONObject().put("sdk",Build.VERSION.SDK_INT).put("supported_abis",JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("os_arch",System.getProperty("os.arch")).put("process_64bit",android.os.Process.is64Bit())
                .put("production_selected_abi",SpiderRuntimeCompat.processAbi()))
        try {
            step("config",afterCancelTag="settings-list",action={
                compose.waitUntil(15_000) { displayed("settings") && !exists("splash-curtain") }
                activate("settings")
                compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-warehouse-url"))
                compose.onNodeWithTag("tvbox-warehouse-url").performTextReplacement(warehouse)
                compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tvbox-warehouse-load"))
                HerrinPlaybackDiagnosticJournal.clear()
                activate("tvbox-warehouse-load")
            },done={
                val code=Regex("WAREHOUSE_HTTP_RESPONSE STATUS=([0-9]+)")
                    .findAll(HerrinPlaybackDiagnosticJournal.export()).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                if(code!=null&&code !in 200..299)throw Stop("WAREHOUSE_HTTP_$code")
                exists("vod-apply-feedback") && text("vod-apply-feedback")=="已导入配置"
            })
            val status = Regex("WAREHOUSE_HTTP_RESPONSE STATUS=([0-9]+)")
                .findAll(HerrinPlaybackDiagnosticJournal.export()).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            report.put("warehouse_http_status",status)
            if (status == null || status !in 200..299) throw Stop("REAL_WAREHOUSE_HTTP_NOT_VERIFIED")
            val config = runBlocking { TvBoxConfigRepository(context).loadUserWarehouse(warehouse,preferCached=true) }
                as? TvBoxConfig.Single ?: throw Stop("MAIN_IMPORTED_CONFIG_NOT_SAVED")
            val indexed = config.sites.withIndex().singleOrNull { it.value.name.contains(name) }
                ?: throw Stop("TARGET_SITE_NOT_UNIQUE")
            val site = indexed.value
            siteKey = site.key
            report.put("site_key",site.key).put("spider_api",site.spiderApi).put("node_count",config.sites.size)
            report.put("config_saved",true).put("declared_jar_md5",(site.jar?:config.spiderJar).orEmpty().substringAfter(";md5;","").take(32))
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-source-sites"))
            activate("settings-source-sites")
            val selectSite = {
                compose.onNodeWithTag("site-list").performScrollToNode(hasTestTag("site-select-${indexed.index}"))
                activate("site-select-${indexed.index}")
            }
            step("spider_initialization",95_000,"site-list",action=selectSite,done={
                val p=Preferences(context)
                p.tvBoxLastWorkingSiteId==TvBoxAutoSites.identity(site,config.spiderJar) && exists("search")
            })
            report.put("plugin_initialized",true)
            check(Preferences(context).tmdbReadToken.isBlank())
            activate("search")
            compose.onNodeWithTag("search-input").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle(); awaitIme(true)
            step("search",30_000,"search-input",action={
                compose.onNodeWithTag("search-input").performTextReplacement(title)
                compose.onNodeWithTag("search-input").assertTextContains(title, substring = true)
            },retry={compose.onNode(hasText("重试") and hasClickAction()).performClick()},done={
                val journal=HerrinPlaybackDiagnosticJournal.export()
                for (code in listOf("SOURCE_EMPTY_RESPONSE","SOURCE_AUTH_REQUIRED","SOURCE_ACCESS_DENIED","SOURCE_SESSION_EXPIRED","SOURCE_SEARCH_FAILED"))
                    if (journal.contains("SOURCE $code")) throw Stop(code)
                compose.onAllNodes(authorizedTitleCard())
                    .fetchSemanticsNodes().isNotEmpty()
            })
            hideIme()
            val openTitle = {
                val target=compose.onAllNodes(authorizedTitleCard()).onFirst()
                target.performSemanticsAction(SemanticsActions.RequestFocus)
                target.assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            step("details_and_episodes",45_000,"film-detail-list",action=openTitle,retry={
                key(KeyEvent.KEYCODE_BACK); compose.waitUntil(5_000){displayed("search-input")}; openTitle()
            },done={displayed("film-detail-list") && (journalCount("DETAILS_RETURNED")?:0)>=2})
            compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("detail-metadata"))
            if (expectedYear != null && !Regex("(?<![0-9])${expectedYear}(?![0-9])").containsMatchIn(text("detail-metadata")))
                throw Stop("AUTHORIZED_YEAR_NOT_VERIFIED")
            if (title == "蜡笔小新" && (journalCount("DETAILS_RETURNED") ?: 0) < 3)
                throw Stop("SERIES_EPISODES_NOT_VERIFIED")
            if (title == "蜡笔小新") {
                val metadata = text("detail-metadata")
                if (!(metadata.contains("动漫") || metadata.contains("剧集"))) throw Stop("TV_ANIMATION_CATEGORY_NOT_VERIFIED")
                report.put("tv_animation_category_verified",true)
            }
            report.put("film_identity_verified",true).put("episode_count",journalCount("DETAILS_RETURNED"))
                .put("details_ui_verified",true)
            val lines=journalCount("DETAIL_LINES_RETURNED")?:0
            report.put("alternate_line_available",lines>1)
            val operations=mutableListOf("first_episode","replay_same_episode","next_episode","return_first_episode","replay_after_details")
            if(title=="蜡笔小新")operations.add(2,"third_episode")
            if(lines>1)operations+="alternate_line"
            for ((index,op) in operations.withIndex()) {
                if(index>0) {
                    step("return_to_details_$index",15_000,"film-detail-list",action=::returnDetails,done={displayed("film-detail-list")})
                    report.put("returned_to_details",true)
                }
                if(op=="alternate_line") {
                    compose.onNodeWithTag("film-detail-list").performScrollToNode(hasTestTag("line-spider-1"))
                    activate("line-spider-1")
                }
                val number=when(op) { "next_episode" -> 2; "third_episode" -> 3; else -> 1 }
                val play = { episode(number) }
                step("first_frame_$index",55_000,"film-detail-list",action=play,retry={returnDetails();play()},done={
                    failProductionGate()
                    playerRead { it !== lastVerifiedPlayer && (it.videoDecoderCounters?.renderedOutputBufferCount?:0)>3 } == true
                })
                lastVerifiedPlayer=playerRead{it}
                val uri=playerRead { it.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty() }.orEmpty()
                if(!uri.startsWith("http://")&&!uri.startsWith("https://"))throw Stop("NOT_REAL_NETWORK_MEDIA")
                if(tv.herrin.app.nativeplayback.NativeFongMiRoute.isNativeProxy(uri) &&
                    !tv.herrin.app.nativeplayback.FongMiSessionAuthorization.isApproved(uri,requireNotNull(siteKey)))throw Stop("PLAYER_URI_NOT_OWNED_BY_SELECTED_SITE")
                report.put("first_frame",true).put("media_bytes_read_proven_by_decoding",true)
                controls(index,number,op)
            }
            report.put("verdict","REAL_PLAYBACK_PASS")
        } catch(error:Throwable) {
            val code=if(error is Stop)error.code else error.javaClass.simpleName
            report.put("verdict",if(code=="AUTH_FLOW_PASS_PLAYBACK_PENDING")code else "FAIL_PLAYBACK")
                .put("failure_stage",currentStage).put("failure_code",code)
                .put("driver_failure_structure",SpiderFailureTrace.structure(error))
                .put("worker_exit",SpiderExitDiagnostics.recent(context))
        } finally {
            if(report.optString("failure_code").startsWith("MEDIA_PROBE_") && !report.optBoolean("media3_created")) {
                // Observe the EXISTING server callback after the failed caller.
                // No retry, new request, timeout change or verdict promotion.
                val observationStart=SystemClock.elapsedRealtime()
                while(SystemClock.elapsedRealtime()-observationStart<8_000) {
                    val rows=runCatching{JSONArray(File(context.filesDir,"spider-proxy-shapes.json").readText())}.getOrNull()
                    if(rows!=null && (0 until rows.length()).any {
                        val row=rows.optJSONObject(it)
                        row!=null && row.optString("outcome")=="HOST_PROXY_RESPONSE" && row.optInt("status")!=204
                    })break
                    Thread.sleep(100)
                }
                report.put("post_failure_observation_only",true)
                    .put("post_failure_observation_ms",SystemClock.elapsedRealtime()-observationStart)
            }
            runCatching {
                val input = compose.onNodeWithTag("search-input").fetchSemanticsNode().config
                    .getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }
                report.put("search_input_length",input.length).put("search_input_matches_authorized_title",input.text==title)
            }
            compose.runOnUiThread { report.put("main_activity_lifecycle",compose.activity.lifecycle.currentState.name) }
            Regex("WAREHOUSE_HTTP_RESPONSE STATUS=([0-9]+)").findAll(HerrinPlaybackDiagnosticJournal.export())
                .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let{report.put("warehouse_http_status",it)}
            compose.runOnUiThread { observed.forEach { (p,l)->runCatching{p.removeAnalyticsListener(l)} }; observed.clear() }
            runCatching {
                val proxy=JSONArray(File(context.filesDir,"spider-proxy-shapes.json").readText())
                report.put("proxy_callback_shapes",proxy)
                if((0 until proxy.length()).any{proxy.optJSONObject(it)?.optString("code") in setOf("HLS_REFERENCE_HTTP_401","HLS_REFERENCE_HTTP_403")})
                    report.put("blocked",true).put("blocker","UPSTREAM_MEDIA_ACCESS_DENIED")
            }
            runCatching{report.put("search_response_shape",JSONObject(File(context.filesDir,"spider-search-shape.json").readText()))}
            runCatching{report.put("sdk_network_scope","PUBLIC_SDK_CALLS_ONLY_NATIVE_NOT_OBSERVED")
                .put("sdk_network_evidence",JSONArray(File(context.filesDir,"spider-network-shape.json").readText()))}
            report.put("safe_player_journal",HerrinPlaybackDiagnosticJournal.export())
                .put("observed_network_media_bytes_after_listener",bytes.get()).put("elapsed_ms",SystemClock.elapsedRealtime()-start)
            File(context.filesDir,"live-node-$fileName.json").writeText(report.toString(2)+"\n")
            android.util.Log.i("HerrinLiveNode",report.toString())
            runCatching{hideIme()}
        }
    }

    private fun controls(index:Int,episode:Int,operation:String) {
        val row=JSONObject().put("selection",index).put("episode",episode).put("operation",operation)
        val first=playerRead{it.currentPosition to (it.videoDecoderCounters?.renderedOutputBufferCount?:0)} ?:throw Stop("PRODUCTION_PLAYER_NOT_FOUND")
        val wall=SystemClock.elapsedRealtime()
        val required=if(index==0)60_000L else 10_000L
        step("continuous_$index",required+20_000,"player-root-focus",done={
            val current=playerRead{Triple(it.currentPosition,it.videoDecoderCounters?.renderedOutputBufferCount?:0,it.isPlaying)}
                ?:throw Stop("PRODUCTION_PLAYER_DISAPPEARED")
            current.third &&current.first-first.first>=required&&SystemClock.elapsedRealtime()-wall>=required&&current.second-first.second>=required/1000
        })
        row.put("continuous_ms_actual",(playerRead{it.currentPosition}?:0)-first.first)
            .put("continuous_wall_ms_actual",SystemClock.elapsedRealtime()-wall)
            .put("continuous_frame_delta",(playerRead{it.videoDecoderCounters?.renderedOutputBufferCount?:0}?:0)-first.second)
        compose.onNodeWithTag("player-root-focus").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        val paused=playerRead{it.currentPosition}?:throw Stop("PRODUCTION_PLAYER_NOT_FOUND")
        Thread.sleep(800)
        check(playerRead{!it.isPlaying&&kotlin.math.abs(it.currentPosition-paused)<250}==true)
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        step("resume_$index",8_000,"player-root-focus",done={playerRead{it.isPlaying&&it.currentPosition>paused+500}==true})
        if(playerRead{it.isCurrentMediaItemSeekable}!=true)throw Stop("MEDIA_NOT_SEEKABLE")
        val oldCounters=playerRead{it.videoDecoderCounters to (it.videoDecoderCounters?.renderedOutputBufferCount?:0)}
            ?:throw Stop("PRODUCTION_PLAYER_NOT_FOUND")
        val oldPosition=playerRead{it.currentPosition}?:0
        val speed=playerRead{it.playbackParameters.speed}?:1f
        val seekWall=SystemClock.elapsedRealtime()
        var seekFrameDelta=0
        var seekJumpMs=0L
        key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        step("seek_$index",15_000,"player-root-focus",done={
            playerRead{
                val counters=it.videoDecoderCounters
                seekFrameDelta=(counters?.renderedOutputBufferCount?:0)-if(counters===oldCounters.first)oldCounters.second else 0
                seekJumpMs=it.currentPosition-oldPosition-((SystemClock.elapsedRealtime()-seekWall)*speed).toLong()
                it.isPlaying&&seekJumpMs>=4_000&&seekFrameDelta>=3
            }==true
        })
        row.put("seek_rendered_frame_delta",seekFrameDelta).put("seek_position_jump_ms",seekJumpMs)
            .put("seek_verified",true).put("pause_resume_verified",true).put("frames",playerRead{it.videoDecoderCounters?.renderedOutputBufferCount?:0})
        report.optJSONArray("playback_evidence")?.put(row)?:report.put("playback_evidence",JSONArray().put(row))
        report.put("seek_verified",true)
    }
}
