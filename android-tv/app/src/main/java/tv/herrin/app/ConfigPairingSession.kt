package tv.herrin.app

import java.security.MessageDigest
import java.security.SecureRandom

/** Monotonic, single-use pairing grant and independently expiring management capability. */
internal class ConfigPairingSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
    val pairing = random()
    private val deadline = clock() + 180_000
    private var pending = false
    private var approved = false
    private var consumed = false
    private var management: String? = null
    private var lastUsed = 0L
    @Synchronized fun request(value: String): Boolean {
        if (consumed || clock() >= deadline || !equal(value, pairing)) return false
        pending = true
        return true
    }
    @Synchronized fun pending(): Boolean = pending && !consumed && clock() < deadline
    @Synchronized fun confirm() { if (pending()) approved = true }
    @Synchronized fun claim(value: String): String? {
        if (!approved || !request(value)) return null
        consumed = true
        return random().also { management = it; lastUsed = clock() }
    }
    @Synchronized fun authorized(value: String): Boolean {
        val saved = management ?: return false
        if (clock() - lastUsed >= 30 * 60_000 || !equal(value, saved)) return false
        lastUsed = clock()
        return true
    }
    @Synchronized fun expired(): Boolean = if (!consumed) clock() >= deadline
        else management == null || clock() - lastUsed >= 30 * 60_000
    @Synchronized fun close() { management = null; consumed = true; pending = false }
    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
