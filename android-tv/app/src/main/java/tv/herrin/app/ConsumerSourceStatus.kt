package tv.herrin.app

/**
 * Consumer TV wording for source connection feedback.
 *
 * Keep the full technical detail untouched in advanced diagnostics. On the
 * ordinary remote-first source screen, do not show runtime implementation
 * names or stack/error internals in the user's main navigation flow.
 *
 * Deliberately handles only known technical-state messages; other validated
 * CMS/TVBox status messages preserve their useful names and counts.
 */
internal object ConsumerSourceStatus {
    fun display(raw: String): String = when {
        raw.contains("Spider 子进程异常退出", ignoreCase = true) ->
            "影视源已导入，但当前影视点暂时无法打开。已停止自动重试，请选择其他影视点。"
        raw.contains("正在尝试 Spider", ignoreCase = true) ->
            "正在连接影视点，请稍候…"
        raw.contains("可尝试自动加载 Java Spider", ignoreCase = true) ->
            raw.substringBefore("；可尝试自动加载") +
                "；请选择需要浏览的影视点，部分来源可能暂不支持。"
        raw.contains("JS 规则", ignoreCase = true) ->
            "该影视点暂不支持，请尝试其他影视点。"
        raw.contains("Spider", ignoreCase = true) ||
            raw.contains("JAR", ignoreCase = true) ||
            raw.contains("系统原因：") ->
            "该影视点暂时无法使用。请重试或选择其他影视点。"
        else -> raw
    }
}
