package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Metadata expansion starts AFTER the preferred site. Never executes another root's JAR. */
internal object HerrinDiscoveryWarehouses {
    // Executable authorization is never stored here. Configuration may contain
    // API Cookie/Token fields, so new expansion data is memory-only.
    private val metadata=linkedMapOf<String,TvBoxConfig>()
    private var epoch=0L
    @Synchronized fun cached(root:String):TvBoxConfig?=metadata[root]
    @Synchronized fun clear(){epoch++;metadata.clear()}
    @Synchronized private fun generation()=epoch
    @Synchronized private fun retain(root:String,config:TvBoxConfig,ticket:Long){
        if(ticket!=epoch)return
        metadata[root]=config
        while(metadata.size>20)metadata.remove(metadata.keys.first())
    }

    suspend fun expand(warehouses: List<TvBoxWarehouse>, repository: TvBoxConfigRepository, allowHttp: Boolean,
        create: (String, TvBoxConfig.Single) -> List<HerrinSourceDiscovery.Node>,
        emit: suspend (List<HerrinSourceDiscovery.Node>) -> Unit): Int {
        val ticket=generation()
        val slots=Semaphore(2)
        val failed=AtomicInteger()
        val seen=mutableSetOf<String>()
        var pending=warehouses.distinctBy { it.url }.take(10)
        repeat(2) {
            val batch=pending.filter { seen.add(it.url) }.take(10)
            pending=coroutineScope {
                batch.map { warehouse -> async {
                    slots.withPermit {
                        val started=System.nanoTime()
                        val config=cached(warehouse.url) ?: withTimeoutOrNull(6_000) {
                            repository.forSearch(warehouse.url,allowHttp,allowNetwork=true,persistFetched=false)
                        }
                        currentCoroutineContext().ensureActive()
                        if(config is TvBoxConfig.Single ||config is TvBoxConfig.Warehouses) retain(warehouse.url,config,ticket)
                        when(config) {
                            is TvBoxConfig.Single -> { emit(create(warehouse.url,config)); emptyList() }
                            is TvBoxConfig.Warehouses -> config.entries.take(10)
                            else -> {
                                failed.incrementAndGet()
                                DiscoveryProtocolTrace.phase(warehouse.url,SourceSearchStage.DISCOVER,SourceSearchOutcome.ERROR,
                                    (System.nanoTime()-started)/1_000_000)
                                emptyList()
                            }
                        }
                    }
                } }.awaitAll().flatten().distinctBy { it.url }.take(10)
            }
        }
        return failed.get()
    }
}
