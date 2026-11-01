package tv.herrin.app

import android.content.Context
import com.fongmi.quickjs.host.Host
import com.github.catvod.crawler.Spider
import com.github.catvod.net.Net
import com.github.catvod.runtime.NodeRuntimeApi
import com.github.catvod.runtime.PythonRuntime
import com.github.catvod.runtime.QuickJsRuntime
import com.github.catvod.utils.Local
import com.github.catvod.utils.RuntimeModule
import com.google.gson.JsonObject

/**
 * Source-compatible bridge for FongMi's optional, separately signed native
 * runtime APKs. Never treats JS/Python/Node script text as CMS JSON.
 *
 * No alternate UI, video catalog, TMDB access or credential persistence.
 * The matching runtime must be independently built with HERRIN's signer;
 * RuntimeModule verifies ABI, hashes, compatibility ID and signature.
 */
internal class FongMiScriptRuntimes(private val context: Context) : AutoCloseable {
    private var javascript: QuickJsRuntime.Session? = null
    private var node: NodeRuntimeApi.Client? = null

    fun create(site: TvBoxSite, host: SpiderHttpSessionHost): Spider {
        val api = site.scriptApi ?: error("站点没有脚本 API")
        RuntimeModule.refresh()
        val spider = when {
            api.startsWith("node:") -> {
                val runtime = RuntimeModule.NODEJS.entry(NodeRuntimeApi::class.java)
                val client = runtime.create(context, object : NodeRuntimeApi.Callback {
                    override fun onMessage(action: String, options: JsonObject): String =
                        """{"error":"Herrin Node host action is unavailable"}"""
                    override fun onPlayInfo(flag: String, id: String) {}
                })
                node = client
                client.spider(api)
            }
            api.substringBefore('?').endsWith(".js", true) -> {
                val runtime = RuntimeModule.QUICKJS.entry(QuickJsRuntime::class.java)
                val session = runtime.session()
                javascript = session
                session.spider(api, null, host)
            }
            api.substringBefore('?').endsWith(".py", true) -> {
                val runtime = RuntimeModule.PYTHON.entry(PythonRuntime::class.java)
                runtime.initialize()
                runtime.spider(api, site.key, host, host::getUserAgent)
            }
            else -> error("不支持的脚本运行环境")
        }
        // FongMi returns a standard CatVod Spider from each runtime.
        spider.siteKey = site.key
        spider.net = Net(host, site.key)
        spider.local = Local(site.key)
        return spider
    }

    override fun close() {
        runCatching { javascript?.clear() }
        javascript = null
        runCatching { node?.clear() }
        node = null
    }
}
