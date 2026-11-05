/* SPDX-License-Identifier: GPL-3.0-only
 * FongMi/TV 305dff233cde512e2c83f71529b7c3ecfd61fefc:
 * JarLoader.checkOpen/cacheLoader publication contract, adapted for host IPC.
 * HERRIN 2026-11-05: fence source intents across explicit session revocation.
 */
package tv.herrin.app

/** Tickets fence host work; they do not grant permission to execute a plugin. */
internal class SpiderSourceSession {
    private var epoch = 0L
    @Volatile private var applied: String? = null

    @Synchronized fun snapshot(): Long = epoch
    fun descriptor(): String? = applied
    @Synchronized fun isCurrent(ticket: Long): Boolean = ticket == epoch

    @Synchronized fun beginBrowse(expected: Long): Long? {
        if (expected != epoch) return null
        epoch++
        applied = null
        return epoch
    }

    @Synchronized fun publish(ticket: Long, descriptor: String): Boolean {
        if (ticket != epoch) return false
        applied = descriptor
        return true
    }

    @Synchronized fun revoke() {
        epoch++
        applied = null
    }
}
