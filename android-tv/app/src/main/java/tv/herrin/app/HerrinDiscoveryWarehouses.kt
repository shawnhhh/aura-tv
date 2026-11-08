package tv.herrin.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Metadata expansion starts AFTER the preferred site. Never executes another root's JAR. */
internal object HerrinDiscoveryWarehouses {
    suspend fun expand(warehouses: List<TvBoxWarehouse>, repository: TvBoxConfigRepository, allowHttp: Boolean,
        create: (String, TvBoxConfig.Single) -> List<HerrinSourceDiscovery.Node>,
        emit: suspend (List<HerrinSourceDiscovery.Node>) -> Unit): Int {
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
                        val config=withTimeoutOrNull(6_000) { repository.forSearch(warehouse.url,allowHttp,allowNetwork=true) }
                        when(config) {
                            is TvBoxConfig.Single -> { emit(create(warehouse.url,config)); emptyList() }
                            is TvBoxConfig.Warehouses -> config.entries.take(10)
                            else -> {
                                failed.incrementAndGet()
                                DiscoveryProtocolTrace.phase(warehouse.url,SourceSearchStage.DISCOVER,SourceSearchOutcome.NETWORK,
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
