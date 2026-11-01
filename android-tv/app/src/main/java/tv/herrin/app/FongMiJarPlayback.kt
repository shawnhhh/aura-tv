package tv.herrin.app

import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.util.LinkedHashMap

/**
 * FongMi JarLoader's playback-only JSON extensions:
 * type=2: com.github.catvod.parser.Json<name>.parse(LinkedHashMap, String)
 * type=3: com.github.catvod.parser.Mix<name>.parse(LinkedHashMap, String, String, String)
 *
 * Classes are loaded ONLY from the already user-applied Spider JAR; never
 * downloaded here. No UI, catalog or scraping mutations.
 */
internal object FongMiJarPlayback {
    fun resolve(
        loader: DexClassLoader?, parser: TvBoxPlaybackParser,
        source: String, flag: String, parsers: List<TvBoxPlaybackParser>,
        allowHttp: Boolean
    ): PlaybackResult {
        if (loader == null) return PlaybackResult.Unavailable("影视源插件尚未加载")
        if (parser.type !in 2..3) return PlaybackResult.Unavailable("解析器插件类型无效")
        // Name supplied by the source selects a class in its own JAR only.
        val className = parser.url
        if (!className.matches(Regex("[A-Za-z0-9_]{1,80}")))
            return PlaybackResult.Unavailable("插件解析器名称无效")
        return try {
            val prefix = if (parser.type == 2) "Json" else "Mix"
            val type = loader.loadClass("com.github.catvod.parser.$prefix$className")
            val result = if (parser.type == 2) {
                val options = LinkedHashMap<String, String>()
                parsers.filter { it.type == 1 }.take(16).forEach {
                    options[it.name] = it.url
                }
                type.getMethod("parse", LinkedHashMap::class.java, String::class.java)
                    .invoke(null, options, source)
            } else {
                val options = LinkedHashMap<String, HashMap<String, String>>()
                parsers.take(24).forEach {
                    options[it.name] = hashMapOf("type" to it.type.toString(),
                        "url" to it.url,
                        "ext" to JSONObject().put("flag", org.json.JSONArray(it.flags))
                            .put("header", JSONObject(it.headers)).toString())
                }
                type.getMethod("parse", LinkedHashMap::class.java, String::class.java,
                    String::class.java, String::class.java)
                    .invoke(null, options, parser.name, flag, source)
            }
            val json = when (result) {
                is JSONObject -> result.toString()
                is String -> result
                else -> return PlaybackResult.Unavailable("插件解析器未返回媒体 JSON")
            }
            // Reuse the same media URL and credential boundary as JSON parsers.
            TvBoxJsonPlayback.decode(json,
                TvBoxJsonParse(parser.name, "https://invalid.invalid/"), allowHttp)
        } catch (_: ClassNotFoundException) {
            PlaybackResult.Unavailable("影视源插件缺少指定的解析器组件")
        } catch (_: NoSuchMethodException) {
            PlaybackResult.Unavailable("影视源插件解析器接口不兼容")
        } catch (_: InvocationTargetException) {
            PlaybackResult.Unavailable("影视源插件解析器运行失败")
        } catch (_: Exception) {
            PlaybackResult.Unavailable("影视源插件解析器调用失败")
        } catch (_: LinkageError) {
            PlaybackResult.Unavailable("影视源插件运行环境不兼容")
        }
    }
}
