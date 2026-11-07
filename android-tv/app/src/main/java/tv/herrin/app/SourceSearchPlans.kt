package tv.herrin.app

/** A cursor indexes an immutable, authorization-scoped plan, never a re-sorted live list. */
internal class SourceSearchPlans {
    data class Plan(val revision:String,val sites:List<TvBoxSite>,val completed:MutableSet<String> = mutableSetOf())
    private val plans=linkedMapOf<String,Plan>()
    @Synchronized fun reset(film:String){plans.remove(film)}
    @Synchronized fun complete(film:String,revision:String,source:String){
        plans[film]?.takeIf{it.revision==revision}?.completed?.add(source)
    }
    @Synchronized fun completed(film:String,revision:String):Set<String> =
        plans[film]?.takeIf{it.revision==revision}?.completed?.toSet().orEmpty()
    @Synchronized fun get(film:String,revision:String,sites:List<TvBoxSite>,rank:(String)->Long):Pair<Plan,Boolean> {
        val old=plans[film]
        if(old?.revision==revision)return old to false
        val next=Plan(revision,sites.distinctBy{it.key}.sortedByDescending{rank("spider:"+it.key)})
        plans[film]=next
        while(plans.size>32)plans.remove(plans.keys.first())
        return next to true
    }
}

internal fun SourceSearchStage.label()=when(this){
    SourceSearchStage.DISCOVER->"正在快速检索"
    SourceSearchStage.ACTIVATE->"正在激活授权节点"
    SourceSearchStage.SEARCH->"正在快速检索"
    SourceSearchStage.DETAILS->"正在读取真实选集"
    SourceSearchStage.PARSE->"正在解析播放线路"
    SourceSearchStage.MEDIA->"正在验证播放线路"
}
internal fun SourceSearchOutcome.label()=when(this){
    SourceSearchOutcome.CHECKING->"检查中"
    SourceSearchOutcome.VERIFIED->"该阶段完成"
    SourceSearchOutcome.NO_MATCH->"影片未匹配"
    SourceSearchOutcome.NO_EPISODES->"没有有效选集"
    SourceSearchOutcome.TIMEOUT->"节点超时"
    SourceSearchOutcome.ACTIVATE_FAILED->"插件激活失败"
    SourceSearchOutcome.PARSE_FAILED->"播放解析失败"
    SourceSearchOutcome.MEDIA_FAILED->"媒体验证失败"
    SourceSearchOutcome.AUTH_REQUIRED->"需要更新来源授权"
    SourceSearchOutcome.RATE_LIMITED->"服务限流，等待后重试"
    SourceSearchOutcome.EXPIRED->"当前资源已失效"
    SourceSearchOutcome.NETWORK->"网络请求失败"
    SourceSearchOutcome.ERROR->"来源执行失败"
}
