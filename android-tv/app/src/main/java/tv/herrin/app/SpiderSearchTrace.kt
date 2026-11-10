package tv.herrin.app

import android.content.Context
import com.github.catvod.crawler.Spider
import org.json.JSONObject
import java.io.File

/** Public method ownership and fixed response shapes only, never body/query/URLs. */
internal object SpiderSearchTrace {
    fun record(context: Context, spider: Spider, site: TvBoxSite, queryLength: Int, invocation: SpiderSearchInvocation.Reply) {
        runCatching {
            val response=invocation.body
            val root = runCatching { JSONObject(response) }.getOrNull()
            fun owner(vararg parameters: Class<*>) = spider.javaClass.getMethod("searchContent", *parameters)
                .declaringClass.name.takeIf { it.length <= 180 && it.matches(Regex("[A-Za-z0-9_.$]+")) }
            val extension = site.extension.trim()
            val extJson = runCatching { JSONObject(extension) }.getOrNull()
            val knownFields = listOf("site","server","api","url","search","cookie","token","quark","uc","ali","pwd")
                .filter { extJson?.has(it) == true }
            val row = JSONObject().put("overload", "TWO_ARGUMENT_FIRST_PAGE")
                .put("declared_searchable", site.searchable).put("declared_quick_search", site.quickSearch)
                .put("init_extension_chars", site.extension.length)
                .put("init_extension_kind", when { extension.isEmpty() -> "EMPTY"; extJson!=null -> "JSON";
                    extension.startsWith("https://") -> "HTTPS_URL"; extension.startsWith("http://") -> "HTTP_URL"; else -> "OTHER" })
                .put("init_extension_known_fields", org.json.JSONArray(knownFields))
                .put("two_argument_owner", owner(String::class.java, Boolean::class.javaPrimitiveType!!))
                .put("three_argument_owner", owner(String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java))
                .put("query_length", queryLength.coerceIn(0, 120)).put("quick", invocation.quick)
                .put("attempts", org.json.JSONArray(invocation.attempts.map { attempt ->
                    JSONObject().put("quick",attempt.quick).put("response_chars",attempt.body.length)
                }))
                .put("response_chars", response.length).put("shape", when {
                    response.isBlank() -> "EMPTY_BODY"
                    root?.optJSONArray("list") != null -> "JSON_LIST"
                    root != null -> "JSON_NO_LIST"
                    else -> "NON_JSON"
                })
            File(context.filesDir, "spider-search-shape.json").writeText(row.toString())
        }
    }
}
