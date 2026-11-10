package tv.herrin.app

import android.content.Context
import com.github.catvod.crawler.Spider
import org.json.JSONObject
import java.io.File

/** Public method ownership and fixed response shapes only, never body/query/URLs. */
internal object SpiderSearchTrace {
    fun record(context: Context, spider: Spider, queryLength: Int, response: String) {
        runCatching {
            val root = runCatching { JSONObject(response) }.getOrNull()
            fun owner(vararg parameters: Class<*>) = spider.javaClass.getMethod("searchContent", *parameters)
                .declaringClass.name.takeIf { it.length <= 180 && it.matches(Regex("[A-Za-z0-9_.$]+")) }
            val row = JSONObject().put("overload", "TWO_ARGUMENT_FIRST_PAGE")
                .put("two_argument_owner", owner(String::class.java, Boolean::class.javaPrimitiveType!!))
                .put("three_argument_owner", owner(String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java))
                .put("query_length", queryLength.coerceIn(0, 120)).put("quick", false)
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
