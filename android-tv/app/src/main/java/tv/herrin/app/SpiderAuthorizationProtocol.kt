package tv.herrin.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/** Documented subset of CatVod Runtime.dialog, matching FongMi SpiderDialog's QR/button wire format.
 * No URL polling, cookie/token extraction, input forms, browser injection or platform login bypass.
 */
internal object SpiderAuthorizationProtocol {
    const val MAX_CHARS = 350_000
    const val TIMEOUT_MS = 120_000L
    class Button(val id: String, val label: String, val close: Boolean, val enabled: Boolean)
    // Deliberately not data classes: toString must never expose the QR payload.
    class Options(val qr: String?, val image: String?, val buttons: List<Button>, val busy: Boolean = false)

    fun parse(raw: String): Options {
        require(raw.length <= MAX_CHARS) { "Authorization payload too large" }
        val json = JSONObject(raw)
        require((json.optJSONArray("inputs")?.length() ?: 0) == 0) { "Authorization input forms are unsupported" }
        val qr = json.optString("qrcode").takeIf { it.isNotEmpty() }
        val image = json.optString("image").takeIf { it.isNotEmpty() }
        require(qr == null || qr.length <= 4096) { "Authorization QR payload too large" }
        require(image == null || image.startsWith("data:image/") && image.contains(";base64,")) { "Authorization image must be embedded" }
        val array = json.optJSONArray("buttons") ?: JSONArray().put(JSONObject().put("id", "ok").put("label", "确认"))
        require(array.length() in 1..3) { "Authorization requires 1 to 3 buttons" }
        val buttons = (0 until array.length()).map { index ->
            val button = array.getJSONObject(index)
            val id = button.getString("id")
            require(id.length in 1..64) { "Invalid authorization action" }
            // Plugin messages and arbitrary labels can contain credentials. Render fixed captions only.
            val label = when (button.optString("label").lowercase()) {
                "刷新", "refresh", "重试", "retry" -> "刷新"
                "注销", "logout", "退出登录" -> "注销"
                "取消", "cancel", "关闭", "close" -> "取消"
                else -> if (index == 0) "确认" else "操作 ${index + 1}"
            }
            Button(id, label, button.optBoolean("close", true), button.optBoolean("enabled", true))
        }
        require(buttons.map { it.id }.distinct().size == buttons.size) { "Duplicate authorization action" }
        return Options(qr, image, buttons)
    }

    fun publicPayload(raw: String): String {
        val options = parse(raw)
        return JSONObject().apply {
            options.qr?.let { put("qrcode", it) }
            options.image?.let { put("image", it) }
            put("buttons", JSONArray().apply { options.buttons.forEach { button ->
                put(JSONObject().put("id", button.id).put("label", button.label)
                    .put("close", button.close).put("enabled", button.enabled))
            } })
        }.toString()
    }

    fun event(id: String): String = JSONObject().put("button", id).put("values", JSONObject()).toString()
}

/** Process-local broker. Only the current same-app IPC operation owns the callback and result. */
internal class SpiderAuthorizationSession(private val publish: (Int, String?) -> Unit) {
    private var sequence = 0
    private var active: Active? = null
    private class Active(val id: Int, var raw: String, var options: SpiderAuthorizationProtocol.Options,
                         val callback: Function<String, CompletableFuture<String>>?,
                         val result: CompletableFuture<String>) {
        var busy = false
        var action: CompletableFuture<String>? = null
    }
    @Synchronized fun show(raw: String, callback: Function<String, CompletableFuture<String>>?): CompletableFuture<String> {
        cancel()
        val options = SpiderAuthorizationProtocol.parse(raw)
        require(callback != null || options.buttons.all { it.close }) { "Nonclosing actions require a callback" }
        val current = Active(++sequence, raw, options, callback, CompletableFuture())
        active = current
        current.result.whenComplete { _, _ -> synchronized(this) {
            if (active === current) { active = null; publish(current.id, null) }
            current.action?.cancel(true)
        } }
        publish(current.id, SpiderAuthorizationProtocol.publicPayload(raw))
        return current.result
    }
    @Synchronized fun action(id: Int, buttonId: String?) {
        val current = active?.takeIf { it.id == id } ?: return
        if (buttonId == null) { cancel(); return }
        val button = current.options.buttons.firstOrNull { it.id == buttonId && it.enabled } ?: return
        if (current.busy) return
        val event = SpiderAuthorizationProtocol.event(button.id)
        if (current.callback == null) { current.result.complete(event); return }
        current.busy = true
        val future = try { current.callback.apply(event) } catch (_: Exception) {
            current.result.completeExceptionally(IllegalStateException("Authorization action failed")); return
        }
        current.action = future
        future.whenComplete { patch, error -> synchronized(this) {
            if (active !== current || current.result.isDone) return@synchronized
            current.action = null
            if (error != null) {
                current.result.completeExceptionally(IllegalStateException("Authorization action failed")); return@synchronized
            }
            try {
                val update = patch?.let(::JSONObject) ?: JSONObject()
                val merged = JSONObject(current.raw)
                for (field in listOf("qrcode", "image", "message")) if (update.has(field)) merged.put(field, update.get(field))
                // Forms/returned credentials stay in the plugin callback; the UI never receives them.
                val invalid = (update.optJSONObject("errors")?.length() ?: 0) > 0
                if (update.optBoolean("close", button.close) && !invalid) current.result.complete(event)
                else {
                    current.raw = merged.toString()
                    current.options = SpiderAuthorizationProtocol.parse(current.raw)
                    current.busy = false
                    publish(current.id, SpiderAuthorizationProtocol.publicPayload(current.raw))
                }
            } catch (_: Exception) { current.result.completeExceptionally(IllegalStateException("Invalid authorization update")) }
        } }
    }
    @Synchronized fun cancel() { active?.result?.complete(null) }
    @Synchronized fun cancel(id: Int) { if (active?.id == id) cancel() }
}
