package tv.herrin.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts NAS/WebDAV credentials at rest with AndroidKeyStore AES-GCM.
 * User-facing metadata (URL, label) can be public preferences; secrets cannot.
 * Failed decryption never silently falls back to plaintext persistence.
 */
internal class HerrinPersonalCredentialStore(context: Context, private val slot: String = "nas") {
    init { require(slot in setOf("nas", "cloud", "tmdb") || slot.matches(Regex("config-[a-f0-9-]{36}"))) }
    private val prefs = context.getSharedPreferences("herrin_personal_secrets", Context.MODE_PRIVATE)
    private val alias = "herrin-webdav-aes-gcm-v1-" + slot

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = store.getKey(alias, null) as? SecretKey
        if (saved != null) return saved
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or
            KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun save(username: String, password: String): Boolean = runCatching {
        val value = (username.take(256) + "\u0000" + password.take(2048))
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value)
        prefs.edit().putString(slot, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    fun load(): Pair<String, String> = runCatching {
        val encoded = prefs.getString(slot, "").orEmpty()
        if (encoded.isBlank()) return@runCatching "" to ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 28..2400)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        plain.substringBefore('\u0000') to plain.substringAfter('\u0000')
    }.getOrDefault("" to "")

    fun clear() {
        prefs.edit().remove(slot).commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
