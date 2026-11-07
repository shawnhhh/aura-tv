package tv.herrin.app

/** Reuse the existing FongMi ParseJob for configured passive JSON parsers.
 * Automatic discovery never starts a new browser/JAR script runtime. */
internal suspend fun resolveCmsCandidate(context:android.content.Context,source:HerrinTvBoxCmsSource,
    film:Film,number:Int,lineId:String,config:TvBoxConfig.Single?,allowHttp:Boolean):PlaybackResult {
    val line=film.playbackEpisodes.firstOrNull{it.number==number}?.lines?.firstOrNull{it.id==lineId}
        ?:return PlaybackResult.Unavailable("当前候选没有有效选集")
    val parser=TvBoxJsonPlayback.choose(line.uri,line.title,config?.jsonParsers.orEmpty())
    if(parser!=null){
        val safe=FongMiParsePlayback.safeHeaders(source.site.headers,source.site.apiUrl.orEmpty(),parser.prefix)
        val failure=java.util.concurrent.atomic.AtomicReference<SourceHttpFailure?>()
        val result=FongMiParsePlayback.resolve(context,org.json.JSONObject().put("parse",1)
            .put("playUrl","parse:"+parser.name).put("url",line.uri)
            .put("header",org.json.JSONObject(safe)).toString(),line.title,listOf(parser),allowHttp,siteHeaders=safe,
            onHttpFailure={code,retry->failure.set(SourceHttpFailure(code,retry))})
        failure.get()?.let{if(result is PlaybackResult.Unavailable ||it.code in setOf(401,403,429))throw it}
        return result
    }
    if(FongMiBrowserPlayback.choose(line.uri,line.title,config?.playbackParsers.orEmpty(),allowHttp)!=null)
        return PlaybackResult.Unavailable("需要浏览器解析；自动快速检索未启动第三方网页脚本")
    return source.play(film,number,lineId)
}
