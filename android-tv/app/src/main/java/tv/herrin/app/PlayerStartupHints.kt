package tv.herrin.app

/**
 * Display only fixed, allowlisted diagnostic messages, never provider-supplied
 * URLs, tokens, cookies, private hostnames or arbitrary exception text.
 */
internal object PlayerStartupHints {
    fun forSpiderFailure(reason: String): String = when {
        reason.contains("网页解析器") || reason.contains("网页解析") ->
            "当前线路只提供网页解析入口，没有可直接播放的媒体地址。请使用支持该来源解析方式的合法线路。"
        reason.contains("HTTP(S) 直链") || reason.contains("没有返回") &&
            reason.contains("直链") ->
            "当前 Spider 未返回可直接播放的视频地址。换影片也可能继续失败，请检查该站点解析能力。"
        reason.contains("授权") || reason.contains("密码") || reason.contains("登录") ->
            "影视来源需要网盘授权或访问码；请在来源提供的登录或访问码界面完成授权。"
        reason.contains("操作超时") || reason.contains("响应超时") ->
            "Spider 插件请求超时，可能是来源服务不可用或插件等待授权。"
        reason.contains("异常退出") || reason.contains("服务已断开") ->
            "Spider 插件进程退出或服务断开；重新应用来源后再解析。"
        reason.contains("尚未加载") || reason.contains("未找到 Spider 播放线路") ->
            "Spider 站点或选集尚未准备好；重新应用来源并进入影片详情。"
        reason.contains("没有可用选集") ->
            "该影片没有返回可选择的剧集或线路。"
        else -> "影视来源未能提供可用媒体地址。请检查来源授权、解析方式或站点状态。"
    }
}
