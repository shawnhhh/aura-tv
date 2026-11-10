package tv.herrin.app

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real providers, real IPC worker, production media gate and production FongMi player panel. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class FanTaiYingRealNodesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var media by mutableStateOf<PlaybackMedia?>(null)
    @Volatile private var player: ExoPlayer? = null
    private val stages = JSONArray()
    private val productionHttpFirst = InstrumentationRegistry.getArguments().getString("p0ProductionHttpFirst") == "true"
    private var currentStage = "config"
    private lateinit var report: JSONObject
    private class Stop(val code: String) : Exception(code)
    private fun safe(s: String) = s.replace(Regex("(?i)https?://[^\\s<>]+"), "[URL]")
        .replace(Regex("(?i)(token|cookie|authorization|password)\\s*[:=]\\s*\\S+"), "$1=[redacted]").take(220)
    private fun write(name: String, value: JSONObject) {
        File(context.filesDir, "live-node-$name.json").writeText(value.toString(2) + "\n")
        android.util.Log.i("HerrinLiveNode", value.toString())
    }
    private fun device() = JSONObject().put("sdk", Build.VERSION.SDK_INT)
        .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("supported_64", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
        .put("supported_32", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
        .put("os_arch", System.getProperty("os.arch")).put("build_cpu_abi", Build.CPU_ABI)
        .put("process_64bit", Process.is64Bit()).put("production_selected_abi", SpiderRuntimeCompat.processAbi())
        .put("native_library_dir", context.applicationInfo.nativeLibraryDir)

    @Test fun actualArmLibraryCapabilities() {
        val data = device()
        for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
            val item = JSONObject()
            val begin = SystemClock.elapsedRealtime()
            try {
                val packaged = File(context.applicationInfo.nativeLibraryDir, "libherrinarmprobe.so")
                val matchesProcess = (abi == "arm64-v8a") == Process.is64Bit()
                val file = if (matchesProcess && packaged.exists()) packaged else {
                    val out = File(context.codeCacheDir, "canary-$abi.so")
                    if (out.exists()) check(out.delete())
                    inst.context.assets.open("native-probe/$abi/libherrinarmprobe.so").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    out.setReadOnly()
                    out
                }
                System.load(file.absolutePath)
                val result = if (abi == "arm64-v8a") HerrinArmCanary.arm64() else HerrinArmCanary.arm32()
                check(result == if (abi == "arm64-v8a") 183 else 40)
                item.put("jni_executed", true).put("machine", result)
            } catch (error: LinkageError) {
                item.put("jni_executed", false).put("error", error.javaClass.simpleName).put("detail", safe(error.message.orEmpty()))
            } catch (error: Exception) {
                item.put("jni_executed", false).put("error", error.javaClass.simpleName).put("detail", safe(error.message.orEmpty()))
            }
            data.put(abi, item.put("elapsed_ms", SystemClock.elapsedRealtime() - begin))
        }
        write("capabilities", data)
    }

    /** Decode the actual displayed QR in memory; never persist QR pixels or content. */
    private fun checkQrAndDismiss(back: Boolean) {
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("spider-authorization-qr").assertIsDisplayed() }.isSuccess }
        val image = compose.onNodeWithTag("spider-authorization-qr").captureToImage().toPixelMap()
        val pixels = IntArray(image.width * image.height) { i ->
            val c = image[i % image.width, i / image.width]
            android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
        check(decoded.barcodeFormat == BarcodeFormat.QR_CODE && decoded.text.isNotBlank())
        if (back) inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        else compose.onNodeWithTag("spider-authorization-cancel").performClick()
        compose.waitUntil(5_000) { SpiderAuthorizationUi.requests.value == null }
        compose.onNodeWithText("Real node test host").assertExists()
    }

    private fun <T> step(label: String, budget: Long = 65_000, block: suspend () -> T): T {
        currentStage = label
        val row = JSONObject().put("stage", label)
        stages.put(row)
        val start = SystemClock.elapsedRealtime()
        var job: Deferred<T>? = null
        try {
            job = scope.async { withTimeout(budget) { block() } }
            while (!requireNotNull(job).isCompleted || SpiderAuthorizationUi.requests.value != null) {
                if (SpiderAuthorizationUi.requests.value != null) {
                    row.put("authorization_observed", true)
                    try {
                        checkQrAndDismiss(false)
                        row.put("qr_decoded", true).put("cancel_pass", true)
                        job?.cancel()
                        // A second genuine provider operation tests dialog Back separately.
                        job = scope.async { withTimeout(budget) { block() } }
                        val until = SystemClock.elapsedRealtime() + 25_000
                        while (!requireNotNull(job).isCompleted && SpiderAuthorizationUi.requests.value == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                        if (SpiderAuthorizationUi.requests.value == null) throw Stop("AUTH_BACK_REOPEN_NOT_OBSERVED")
                        checkQrAndDismiss(true)
                        row.put("back_pass", true)
                        throw Stop("AUTH_FLOW_PASS_PLAYBACK_PENDING")
                    } catch (error: Stop) { throw error }
                    catch (error: Exception) { throw Stop("AUTH_QR_VERIFICATION_FAILED_${error.javaClass.simpleName}") }
                }
                if (SystemClock.elapsedRealtime() - start > budget + 5_000) throw Stop("OPERATION_TIMEOUT")
                Thread.sleep(100)
            }
            val completed = requireNotNull(job)
            val value = runBlocking { completed.await() }
            row.put("result", "PASS")
            return value
        } catch (error: Throwable) {
            row.put("result", "FAILED_OR_PENDING").put("error", safe(error.message.orEmpty()))
            throw error
        } finally {
            job?.cancel()
            row.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
        }
    }
    private fun <T> ready(result: SpiderResult<T>): T = when (result) {
        is SpiderResult.Ready -> result.value
        is SpiderResult.Failure -> throw Stop(safe(result.message))
    }
    @Test fun wanouGegeRealNode() = node("玩偶哥哥", "wanou")
    @Test fun cheeseRealNode() = node("奶酪", "cheese")

    private fun node(name: String, fileName: String) {
        val begin = SystemClock.elapsedRealtime()
        report = JSONObject().put("node", name).put("verdict", "FAIL_PLAYBACK")
            .put("device", device()).put("stages", stages)
            .put("authorized_title", "小城良方").put("authorized_year", 2026)
            .put("first_frame", false).put("seek_verified", false).put("plugin_initialized", false)
            .put("playback_host_scope", "PRODUCTION_PANEL_WITH_TEST_CATALOG_HOST")
            .put("details_ui_verified", false)
            .put("diagnostic_only", productionHttpFirst)
        val bridge = TvBoxSpiderBridge(context)
        for (file in listOf("spider-failure-structure.txt", "spider-proxy-shapes.json", "spider-startup-stage.txt", "spider-search-shape.json", "spider-network-shape.json"))
            File(context.filesDir, file).delete()
        HerrinPlaybackDiagnosticJournal.clear()
        var backRequests = 0
        compose.setContent { MaterialTheme {
            Text("Real node test host")
            media?.let { AuthorizedPlaybackPanel(it, onPlayer = { created -> player = created }, onBack = {
                media = null
                backRequests++
                bridge.releasePlayback()
            }) }
            SpiderAuthorizationHost()
        } }
        try {
            val config = step("config") {
                when (val loaded = TvBoxConfigRepository(context).loadUserWarehouse("http://www.饭太硬.net/tv")) {
                    is TvBoxConfig.Error -> throw Stop(loaded.reason)
                    else -> loaded
                }
            } as? TvBoxConfig.Single ?: throw Stop("REAL_SINGLE_CONFIG_UNAVAILABLE")
            if (config.fromCache) throw Stop("CACHE_NOT_REAL_NETWORK")
            val site = config.sites.singleOrNull { it.name.contains(name) } ?: throw Stop("TARGET_SITE_NOT_UNIQUE")
            report.put("site_key", site.key).put("site_name", site.name)
            report.put("spider_api", site.spiderApi).put("node_count", config.sites.size)
            report.put("declared_jar_md5", (site.jar ?: config.spiderJar).orEmpty().substringAfter(";md5;", "").take(32))
            // No ABI guard here: ALWAYS attempt the production worker on every image.
            val initialHome = step("spider_initialization", 95_000) {
                ready(bridge.browse(site, config, searchOnly = name != "玩偶哥哥"))
            }
            report.put("plugin_initialized", true).put("home_film_count", initialHome.size)
                .put("active_site_after_home", bridge.approvedActiveSiteKey() == site.key)
            val found = step("search") { ready(bridge.search(site, "小城良方")) }
            report.put("search_count", found.size)
            val identity = HerrinFilmIdentity(1, "tv", listOf("小城良方"), 2026)
            val film = step("film_match") {
                found.firstOrNull { candidate ->
                    val offer = HerrinSourceMovie("spider:" + site.key, HerrinSourceType.TVBOX_SPIDER, candidate, "tv")
                    HerrinFilmIdentityMatch.matches(identity, offer) && candidate.year.toIntOrNull().let { it == null || it == 2026 }
                } ?: throw Stop("AUTHORIZED_TITLE_YEAR_NOT_MATCHED")
            }
            val detail = step("details") { ready(bridge.details(film)).also { loaded ->
                if(loaded.year.toIntOrNull()!=2026 || !HerrinFilmIdentityMatch.matches(identity,
                    HerrinSourceMovie("spider:"+site.key,HerrinSourceType.TVBOX_SPIDER,loaded,"tv")))
                    throw Stop("AUTHORIZED_TITLE_YEAR_NOT_VERIFIED_IN_DETAILS")
                report.put("film_identity_verified",true)
            } }
            val episodes = step("episodes") { detail.playbackEpisodes.filter { it.lines.isNotEmpty() }.also {
                if (it.size < 2) throw Stop("MULTIPLE_EPISODES_UNAVAILABLE")
            } }
            report.put("episode_count", episodes.size)
            val first = episodes[0]
            val second = episodes[1]
            val alternate = first.lines.getOrNull(1)
            report.put("alternate_line_available", alternate != null)
            val sequence = mutableListOf(first to first.lines[0], first to first.lines[0], second to second.lines[0],
                first to first.lines[0], first to first.lines[0])
            val operations = mutableListOf("first_episode", "replay_same_episode", "next_episode", "return_first_episode", "replay_after_details")
            if(alternate!=null) { sequence+=first to alternate; operations+="alternate_line" }
            var currentDetail = detail
            val selectionFailures = JSONArray()
            report.put("selection_failures", selectionFailures)
            for ((index, choice) in sequence.withIndex()) {
                try {
                if (index == 4) {
                    step("return_to_details", 15_000) {
                        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        Thread.sleep(300)
                        if (backRequests == 0) inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        compose.waitUntil(5_000) { backRequests > 0 && media == null }
                        bridge.awaitPlaybackReleaseForInstrumentation()
                        compose.waitForIdle()
                        report.put("player_back_callback_verified", true)
                        // A panel callback alone is NOT proof of the production
                        // details screen. Full UI navigation remains required.
                        if(runCatching { compose.onNodeWithTag("film-detail-list").assertIsDisplayed() }.isFailure)
                            throw Stop("PRODUCTION_DETAILS_UI_NOT_VERIFIED")
                        report.put("returned_to_details", true).put("details_ui_verified",true)
                    }
                    step("reapply_after_details") { ready(bridge.browse(site, config, searchOnly = true)) }
                    val fresh = step("search_after_details") { ready(bridge.search(site, "小城良方")) }
                        .firstOrNull { candidate ->
                            HerrinFilmIdentityMatch.matches(identity,
                                HerrinSourceMovie("spider:" + site.key, HerrinSourceType.TVBOX_SPIDER, candidate, "tv")) &&
                                candidate.year.toIntOrNull().let { it == null || it == 2026 }
                        } ?: throw Stop("AUTHORIZED_TITLE_YEAR_NOT_MATCHED_AFTER_DETAILS")
                    currentDetail = step("details_after_return") { ready(bridge.details(fresh)).also {
                        if(it.year.toIntOrNull()!=2026) throw Stop("AUTHORIZED_YEAR_NOT_VERIFIED_AFTER_DETAILS")
                    } }
                }
                val session = PlaybackDiagnosticSession.newId()
                val resolved = step("resolve_$index") {
                    withContext(PlaybackDiagnosticSession.context(session)) {
                        when (val result = bridge.play(currentDetail, choice.first.number, choice.second.id, true)) {
                            is PlaybackResult.Ready -> result.media
                            is PlaybackResult.Unavailable -> throw Stop(safe(result.reason))
                        }
                    }
                }
                if (productionHttpFirst && index == 0) {
                    step("diagnostic_production_http_first", 18_000) {
                        if (!tv.herrin.app.nativeplayback.NativeFongMiRoute.isNativeProxy(resolved.uri))
                            throw Stop("PRODUCTION_HTTP_FIRST_REQUIRES_OWNED_PROXY")
                        productionHttpDiagnostic(resolved, site.key)
                    }
                    // One isolated read only: do not prime the session then call that
                    // normal acceptance. A genuine frame test is a separate invocation.
                    throw Stop("DIAGNOSTIC_MANIFEST_READ_NOT_PLAYBACK_ACCEPTANCE")
                }
                fun probeClient(extended: Boolean): HerrinRemoteMediaProbe {
                    val events = JSONArray()
                    report.put(if (extended) "extended_media_events" else "production_media_events", events)
                    val evidence: (String, String) -> Unit = { event, value ->
                        if (events.length() < 48) events.put(JSONObject().put("event", event).put("detail", value))
                    }
                    val authorize: suspend (String) -> Boolean = {
                        val health = tv.herrin.app.nativeplayback.FongMiSessionAuthorization.inspect(it, site.key)
                        events.put(JSONObject().put("event", "LOCAL_AUTH_" + health.name)
                            .put("current_site_matches", bridge.approvedActiveSiteKey() == site.key))
                        health == tv.herrin.app.nativeplayback.NativeFongMiRoute.Health.READY
                    }
                    if (!extended) return HerrinRemoteMediaProbe(authorizeLocal = authorize, evidence = evidence)
                    // Diagnostic-only budget. The normal production gate failure remains a failure.
                    val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(18, java.util.concurrent.TimeUnit.SECONDS).build()
                    return HerrinRemoteMediaProbe(client = client, authorizeLocal = authorize, evidence = evidence)
                }
                try {
                    step("real_media_bytes_$index") {
                        val result = probeClient(false).probe(resolved)
                        if (result != HerrinRemoteMediaProbe.Result.MEDIA) throw Stop("MEDIA_${result.name}")
                    }
                } catch (error: Stop) {
                    if (!error.code.startsWith("MEDIA_")) throw error
                    report.put("production_media_gate_failed", true).put("production_media_failure", error.code)
                    try {
                        step("diagnostic_extended_media_$index", 25_000) {
                            val result = probeClient(true).probe(resolved)
                            if (result != HerrinRemoteMediaProbe.Result.MEDIA) throw Stop("EXTENDED_MEDIA_${result.name}")
                        }
                    } catch (extended: Stop) {
                        if (!extended.code.startsWith("EXTENDED_MEDIA_")) throw extended
                        report.put("extended_media_failure", extended.code)
                        if (!tv.herrin.app.nativeplayback.NativeFongMiRoute.isNativeProxy(resolved.uri)) throw extended
                        // A different client must not turn the original gate failure into PASS.
                        // Compare the ACTUAL production Media3 HTTP factory, including platform
                        // defaults, rather than guessing a browser/device User-Agent.
                        step("diagnostic_production_http_$index", 18_000) {
                            productionHttpDiagnostic(resolved, site.key)
                        }
                    }
                    report.put("player_is_extended_budget_diagnostic", true)
                }
                verifyPlayer(resolved, index, choice.first.number, operations[index])
                } catch (error: Stop) {
                    if (error.code == "AUTH_FLOW_PASS_PLAYBACK_PENDING" || error.code.startsWith("AUTH_")) throw error
                    selectionFailures.put(JSONObject().put("selection", index).put("episode", choice.first.number)
                        .put("stage", currentStage).put("code", error.code))
                    compose.runOnIdle { media = null }
                    compose.waitForIdle()
                    // Fix the first failing stage before testing the next acceptance stage.
                    throw error
                }
            }
            if (selectionFailures.length() > 0) {
                val firstFailure = selectionFailures.getJSONObject(0)
                currentStage = firstFailure.getString("stage")
                throw Stop(firstFailure.getString("code"))
            }
            if (report.optBoolean("production_media_gate_failed")) {
                currentStage = "production_media_gate"
                throw Stop("PRODUCTION_MEDIA_GATE_FAILED_EXTENDED_DIAGNOSTIC_ONLY")
            }
            report.put("verdict", "REAL_PLAYBACK_PASS")
        } catch (error: Throwable) {
            val code = safe(error.message ?: error.javaClass.simpleName)
            val verdict = when {
                code == "AUTH_FLOW_PASS_PLAYBACK_PENDING" -> code
                currentStage == "config" && (code.contains("UNAVAILABLE") || code.contains("DNS") || code.contains("NETWORK")) -> "ENVIRONMENT_BLOCKED"
                code.contains("UnsatisfiedLinkError") || code.contains("dlopen") || code.contains("ELF") -> "ENVIRONMENT_BLOCKED"
                else -> "FAIL_PLAYBACK"
            }
            report.put("verdict", verdict).put("failure_stage", currentStage).put("failure_code", code)
                .put("safe_failure_structure", SpiderFailureTrace.recent(context))
                .put("worker_last_stage", SpiderStartupTrace.recent(context)).put("worker_exit", SpiderExitDiagnostics.recent(context))
        } finally {
            compose.runOnIdle { media = null }
            bridge.close()
            scope.cancel()
            runCatching {
                val shapes = JSONArray(File(context.filesDir, "spider-proxy-shapes.json").readText())
                report.put("proxy_callback_shapes", shapes)
                if ((0 until shapes.length()).any { shapes.optJSONObject(it)?.optString("code") in
                    setOf("HLS_REFERENCE_HTTP_401", "HLS_REFERENCE_HTTP_403") }) {
                    report.put("blocked", true).put("blocker", "UPSTREAM_MEDIA_ACCESS_DENIED")
                }
            }
            runCatching { report.put("sdk_network_scope", "SDK_OKHTTP_ONLY").put("sdk_network_evidence", JSONArray(File(context.filesDir, "spider-network-shape.json").readText())) }
            runCatching { report.put("search_response_shape", JSONObject(File(context.filesDir, "spider-search-shape.json").readText())) }
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - begin)
                .put("safe_player_journal", HerrinPlaybackDiagnosticJournal.export())
            write(fileName, report)
        }
    }

    private suspend fun productionHttpDiagnostic(resolved: PlaybackMedia, siteKey: String) {
        val evidence = JSONObject().put("scope", "PRODUCTION_FONGMI_HTTP_DATASOURCE_DIAGNOSTIC_ONLY")
        report.put("production_http_diagnostic", evidence)
        val start = SystemClock.elapsedRealtime()
        val health = tv.herrin.app.nativeplayback.FongMiSessionAuthorization.inspect(resolved.uri, siteKey)
        evidence.put("local_health", health.name)
        if (health != tv.herrin.app.nativeplayback.NativeFongMiRoute.Health.READY)
            throw Stop("PRODUCTION_HTTP_LOCAL_${health.name}")
        try {
            runInterruptible(Dispatchers.IO) {
                val source = FongMiExoPlayerFactory.httpDataSourceFactory(resolved).createDataSource()
                try {
                    source.open(androidx.media3.datasource.DataSpec.Builder().setUri(resolved.uri).build())
                    evidence.put("http_status", source.responseCode)
                    val prefix = ByteArray(16)
                    var count = 0
                    while (count < prefix.size) {
                        val n = source.read(prefix, count, prefix.size-count)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                    }
                    evidence.put("prefix_bytes", count)
                    val text = String(prefix, 0, count, Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
                    val manifest = text.startsWith("#EXTM3U\n") || text.startsWith("#EXTM3U\r\n")
                    evidence.put("hls_prefix", manifest)
                    if (!manifest) throw Stop("PRODUCTION_HTTP_MANIFEST_PREFIX_INVALID")
                    evidence.put("result", "MANIFEST_PREFIX_READ_NOT_VIDEO_OR_PLAYBACK_PASS")
                } finally { source.close() }
            }
        } catch (failure: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            evidence.put("http_status", failure.responseCode).put("result", "HTTP_ERROR")
            throw Stop("PRODUCTION_HTTP_${failure.responseCode.coerceIn(100,599)}")
        } catch (failure: java.io.IOException) {
            evidence.put("result", "IO_ERROR").put("exception_type", failure.javaClass.simpleName)
            throw Stop("PRODUCTION_HTTP_IO_ERROR")
        } finally { evidence.put("elapsed_ms",SystemClock.elapsedRealtime()-start) }
    }

    private fun verifyPlayer(resolved: PlaybackMedia, index: Int, episode: Int, operation: String) {
        // AuthorizedPlaybackPanel owns HerrinPlaybackAdapter -> pinned FongMi PlayerManager -> Media3.
        // No test-created ExoPlayer, alternative surface, rewritten stream or fixture media.
        step("first_frame_$index", 55_000) {
            compose.runOnIdle { player = null; media = resolved }
            compose.waitUntil(45_000) {
                var ready = false
                compose.runOnIdle {
                    player?.playerError?.let { throw Stop("MEDIA3_${it.errorCodeName}") }
                    ready = (player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 3
                }
                ready
            }
            report.put("first_frame", true)
        }
        step("continuous_pause_seek_$index", 110_000) {
            var start = 0L
            var initialFrames = 0
            compose.runOnIdle {
                start = player!!.currentPosition
                initialFrames = player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            }
            val requiredMs = if (index == 0) 60_000L else 10_000L
            val continuousWallStart=SystemClock.elapsedRealtime()
            compose.waitUntil(requiredMs + 15_000) {
                var advanced = false
                compose.runOnIdle { advanced = player!!.isPlaying && player!!.currentPosition > start + requiredMs &&
                    SystemClock.elapsedRealtime()-continuousWallStart>=requiredMs }
                advanced
            }
            var actualContinuousMs = 0L
            var continuousFrameDelta = 0
            compose.runOnIdle {
                actualContinuousMs = player!!.currentPosition - start
                continuousFrameDelta = (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) - initialFrames
                check(continuousFrameDelta >= (requiredMs / 1_000).toInt()) { "VIDEO_FRAMES_DID_NOT_CONTINUE" }
            }
            val continuousWallElapsed=SystemClock.elapsedRealtime()-continuousWallStart
            var paused = 0L
            compose.runOnIdle { player!!.pause(); paused = player!!.currentPosition }
            Thread.sleep(800)
            compose.runOnIdle { check(!player!!.isPlaying && kotlin.math.abs(player!!.currentPosition - paused) < 250) }
            compose.runOnIdle { player!!.play() }
            compose.waitUntil(8_000) {
                var resumed = false
                compose.runOnIdle { resumed = player!!.isPlaying && player!!.currentPosition > paused + 500 }
                resumed
            }
            var target = 0L
            var beforeSeekFrames=0
            var beforeSeekCounters: Any?=null
            var seekRenderedFrameDelta=0
            compose.runOnIdle {
                beforeSeekCounters=player!!.videoDecoderCounters
                beforeSeekFrames=player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                check(player!!.isCurrentMediaItemSeekable) { "MEDIA_NOT_SEEKABLE" }
                target = minOf(player!!.duration / 2, paused + 10_000)
                check(target > paused + 2_000) { "INSUFFICIENT_DURATION_FOR_SEEK" }
                player!!.seekTo(target)
                player!!.play()
            }
            compose.waitUntil(12_000) {
                var sought = false
                compose.runOnIdle {
                    val counters=player!!.videoDecoderCounters
                    seekRenderedFrameDelta=(counters?.renderedOutputBufferCount ?: 0)-if(counters===beforeSeekCounters) beforeSeekFrames else 0
                    sought = player!!.isPlaying && player!!.currentPosition >= target && player!!.currentPosition < target + 5_000 && seekRenderedFrameDelta>=3
                }
                sought
            }
            val row = JSONObject().put("selection", index).put("episode", episode).put("operation", operation)
                .put("seek_target_ms", target).put("continuous_ms_minimum", requiredMs)
                .put("continuous_ms_actual", actualContinuousMs).put("continuous_frame_delta", continuousFrameDelta)
                .put("continuous_wall_ms_actual",continuousWallElapsed)
                .put("seek_rendered_frame_delta",seekRenderedFrameDelta)
                .put("pause_resume_verified", true).put("seek_verified", true)
            compose.runOnIdle { row.put("frames", player!!.videoDecoderCounters?.renderedOutputBufferCount)
                .put("position_ms", player!!.currentPosition) }
            report.optJSONArray("playback_evidence")?.put(row) ?: report.put("playback_evidence", JSONArray().put(row))
            report.put("seek_verified", true)
        }
        // Keep the actual player mounted until the next selection or real Back action.
        compose.waitForIdle()
    }
}
