package tv.herrin.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StartupBackgroundWorkTest {
    @Test fun overlappingCurtainsReleaseOnlyAfterBothActualOwnersFinish() = runBlocking {
        val first = StartupBackgroundWork.hold()
        val second = StartupBackgroundWork.hold()
        try {
            val work = async(start=CoroutineStart.UNDISPATCHED) { StartupBackgroundWork.awaitReady(); true }
            assertFalse(work.isCompleted)
            first.close(); first.close()
            assertFalse(work.isCompleted)
            second.close()
            assertTrue(withTimeout(1000) { work.await() })
        } finally { first.close(); second.close() }
    }
    @Test fun newCurtainDoesNotCancelWorkAlreadyInFlightAndCancellationCannotLeakALease() = runBlocking {
        val releaseNetwork = CompletableDeferred<Unit>()
        val network = async(start=CoroutineStart.UNDISPATCHED) {
            StartupBackgroundWork.awaitReady()
            releaseNetwork.await()
            true
        }
        val lease = StartupBackgroundWork.hold()
        try {
            val nextFile = launch(start=CoroutineStart.UNDISPATCHED) { StartupBackgroundWork.awaitReady() }
            assertTrue(network.isActive); assertFalse(nextFile.isCompleted)
            releaseNetwork.complete(Unit)
            assertTrue(network.await())
            nextFile.cancelAndJoin()
        } finally { lease.close() }
        withTimeout(1000) { StartupBackgroundWork.awaitReady() }
    }
}
