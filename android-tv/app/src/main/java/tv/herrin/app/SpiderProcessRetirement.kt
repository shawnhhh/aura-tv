package tv.herrin.app

internal data class OwnedSpiderProcess(val pid:Int,val uid:Int,val name:String)

/** Never selects the UI process, another UID, another service, or an ambiguous restart. */
internal fun ownedSpiderPid(mainPid:Int,uid:Int,packageName:String,processes:List<OwnedSpiderProcess>):Int? =
    processes.filter{it.pid>0 &&it.pid!=mainPid &&it.uid==uid &&it.name=="$packageName:spider"}
        .singleOrNull()?.pid

internal object SpiderHostRetirement {
    // Requests, not a claim that an OS process has already exited.
    val requests=java.util.concurrent.atomic.AtomicInteger()
}
