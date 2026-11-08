package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** FongMi type-0 RSS CMS data only. External entities and DTDs are prohibited. */
internal object TvBoxCmsXml {
    fun toJson(text: String): String {
        require(text.toByteArray().size <= 1_048_576)
        // Same platform policy as the already exercised DAV adapter: Android
        // DOM does not support every Xerces feature. Refuse declarations before
        // parsing, so unsupported feature switches never weaken entity safety.
        require(!Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""",RegexOption.IGNORE_CASE).containsMatchIn(text))
        val factory=DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities",false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities",false) }
            isExpandEntityReferences=false
            runCatching { isXIncludeAware=false }
        }
        val builder=factory.newDocumentBuilder()
        builder.setEntityResolver { _,_ -> InputSource(StringReader("")) }
        val document=builder.parse(InputSource(StringReader(text)))
        val videos=document.getElementsByTagName("video")
        val rows=JSONArray()
        for(index in 0 until minOf(60,videos.length)) {
            val video=videos.item(index) as? Element ?: continue
            fun value(tag:String)=video.getElementsByTagName(tag).item(0)?.textContent.orEmpty()
            val row=JSONObject().put("vod_id",value("id")).put("vod_name",value("name"))
                .put("vod_pic",value("pic")).put("vod_year",value("year")).put("type_name",value("type"))
                .put("vod_remarks",value("note")).put("vod_content",value("des"))
            val entries=video.getElementsByTagName("dd")
            val flags=mutableListOf<String>(); val playlists=mutableListOf<String>()
            for(n in 0 until minOf(12,entries.length)) {
                val dd=entries.item(n) as? Element ?: continue
                flags += dd.getAttribute("flag"); playlists += dd.textContent
            }
            row.put("vod_play_from",flags.joinToString("$$$")).put("vod_play_url",playlists.joinToString("$$$"))
            rows.put(row)
        }
        return JSONObject().put("list",rows).toString()
    }
}
