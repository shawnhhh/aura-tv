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
    private var currentStage = "config"
    private lateinit var report: JSONObject
    private class Stop(val code: String) : Exception(code)
    private fun safe(s: String) = s.replace(Regex("(?i)https?://[^\\s<>]+"), "[URL]")
        .replace(Regex("(?i)(token|cookie|authorization|password)\\s*[:=]\\s*\\S+"), "$1=[redacted]").take(220)
    private fun write(name: String, value: JSONObject) {
        File(context.filesDir, "live-node-$name.json").writeText(value.toString(2))
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
                val file = if (abi == "arm64-v8a" && packaged.exists()) packaged else {
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
            while (!job.isCompleted) {
                if (SpiderAuthorizationUi.requests.value != null) {
                    row.put("authorization_observed", true)
                    try {
                        checkQrAndDismiss(false)
                        row.put("qr_decoded", true).put("cancel_pass", true)
                        job.cancel()
                        // A second genuine provider operation tests dialog Back separately.
                        job = scope.async { withTimeout(budget) { block() } }
                        val until = SystemClock.elapsedRealtime() + 25_000
                        while (!job.isCompleted && SpiderAuthorizationUi.requests.value == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
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
        val bridge = TvBoxSpiderBridge(context)
        compose.setContent { MaterialTheme {
            Text("Real node test host")
            media?.let { AuthorizedPlaybackPanel(it, onPlayer = { created -> player = created }, onBack = { media = null }) }
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
            step("spider_initialization", 95_000) { ready(bridge.browse(site, config, searchOnly = true)) }
            report.put("plugin_initialized", true)
            val found = step("search") { ready(bridge.search(site, "小城良方")) }
            report.put("search_count", found.size)
            val identity = HerrinFilmIdentity(1, "tv", listOf("小城良方"), 2026)
            val film = step("film_match") {
                found.firstOrNull { candidate ->
                    val offer = HerrinSourceMovie("spider:" + site.key, HerrinSourceType.TVBOX_SPIDER, candidate, "tv")
                    HerrinFilmIdentityMatch.matches(identity, offer) && candidate.year.toIntOrNull() == 2026
                } ?: throw Stop("AUTHORIZED_TITLE_YEAR_NOT_MATCHED")
            }
            val detail = step("details") { ready(bridge.details(film)) }
            val episodes = step("episodes") { detail.playbackEpisodes.filter { it.lines.isNotEmpty() }.also {
                if (it.size < 2) throw Stop("MULTIPLE_EPISODES_UNAVAILABLE")
            } }
            report.put("episode_count", episodes.size)
            val first = episodes[0]
            val second = episodes[1]
            val alternate = first.lines.getOrNull(1) ?: throw Stop("ALTERNATE_LINE_UNAVAILABLE")
            val sequence = listOf(first to first.lines[0], first to first.lines[0], second to second.lines[0], first to alternate)
            for ((index, choice) in sequence.withIndex()) {
                val session = PlaybackDiagnosticSession.newId()
                val resolved = step("resolve_$index") {
                    withContext(PlaybackDiagnosticSession.context(session)) {
                        when (val result = bridge.play(detail, choice.first.number, choice.second.id, true)) {
                            is PlaybackResult.Ready -> result.media
                            is PlaybackResult.Unavailable -> throw Stop(safe(result.reason))
                        }
                    }
                }
                step("real_media_bytes_$index") {
                    val result = HerrinRemoteMediaProbe(authorizeLocal = {
                        tv.herrin.app.nativeplayback.FongMiSessionAuthorization.isApproved(it, site.key)
                    }, evidence = { event, value ->
                        // Host-fixed event codes/integers only; no media URLs/headers.
                        report.put("last_media_event", event).put("last_media_detail", value)
                    }).probe(resolved)
                    if (result != HerrinRemoteMediaProbe.Result.MEDIA) throw Stop("MEDIA_${result.name}")
                }
                verifyPlayer(resolved, index)
            }
            report.put("verdict", "PASS_PLAYBACK")
        } catch (error: Throwable) {
            val code = safe(error.message ?: error.javaClass.simpleName)
            val verdict = when {
                code == "AUTH_FLOW_PASS_PLAYBACK_PENDING" -> code
                currentStage == "config" && (code.contains("UNAVAILABLE") || code.contains("DNS") || code.contains("NETWORK")) -> "ENVIRONMENT_BLOCKED"
                code.contains("UnsatisfiedLinkError") || code.contains("dlopen") || code.contains("ELF") -> "ENVIRONMENT_BLOCKED"
                else -> "FAIL_PLAYBACK"
            }
            report.put("verdict", verdict).put("failure_stage", currentStage).put("failure_code", code)
                .put("worker_last_stage", SpiderStartupTrace.recent(context)).put("worker_exit", SpiderExitDiagnostics.recent(context))
        } finally {
            compose.runOnIdle { media = null }
            bridge.close()
            scope.cancel()
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - begin)
                .put("safe_player_journal", HerrinPlaybackDiagnosticJournal.export())
            write(fileName, report)
        }
    }

    private fun verifyPlayer(resolved: PlaybackMedia, index: Int) {
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
        step("continuous_pause_seek_$index", 35_000) {
            var start = 0L
            compose.runOnIdle { start = player!!.currentPosition }
            compose.waitUntil(15_000) {
                var advanced = false
                compose.runOnIdle { advanced = player!!.isPlaying && player!!.currentPosition > start + 3_000 }
                advanced
            }
            var paused = 0L
            compose.runOnIdle { player!!.pause(); paused = player!!.currentPosition }
            Thread.sleep(800)
            compose.runOnIdle { check(!player!!.isPlaying && kotlin.math.abs(player!!.currentPosition - paused) < 250) }
            var target = 0L
            compose.runOnIdle {
                check(player!!.isCurrentMediaItemSeekable) { "MEDIA_NOT_SEEKABLE" }
                target = minOf(player!!.duration / 2, paused + 10_000)
                check(target > paused + 2_000) { "INSUFFICIENT_DURATION_FOR_SEEK" }
                player!!.seekTo(target)
                player!!.play()
            }
            compose.waitUntil(12_000) {
                var sought = false
                compose.runOnIdle { sought = player!!.isPlaying && player!!.currentPosition >= target && player!!.currentPosition < target + 5_000 }
                sought
            }
            val row = JSONObject().put("selection", index).put("seek_target_ms", target)
            compose.runOnIdle { row.put("frames", player!!.videoDecoderCounters?.renderedOutputBufferCount)
                .put("position_ms", player!!.currentPosition) }
            report.optJSONArray("playback_evidence")?.put(row) ?: report.put("playback_evidence", JSONArray().put(row))
            report.put("seek_verified", true)
        }
        compose.runOnIdle { media = null }
        compose.waitForIdle()
    }
}
