package tv.herrin.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.tls.HeldCertificate
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object LanConfigIdentityPolicy {
    fun address(host: String): String {
        val parts=host.split('.')
        require(parts.size==4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true })
        require(parts.joinToString("."){it.toInt().toString()}==host)
        val address=InetAddress.getByAddress(parts.map{it.toInt().toByte()}.toByteArray())
        require(address.isSiteLocalAddress || address.isLoopbackAddress)
        return host
    }
}

/** Device-generated TLS key material, AES-GCM wrapped by a non-exportable AndroidKeyStore key.
 * The envelope lives in noBackupFilesDir; neither the APK, preferences nor logs contain its private key.
 * One current IP identity is retained. A SAN change, expiry or invalid envelope creates a fresh identity.
 */
internal class LanTlsIdentity(
    context: Context,
    internal val directory: File=File(context.noBackupFilesDir,"lan-tls-v1"),
    private val clock: ()->Long=System::currentTimeMillis
) {
    companion object {
        private val lock=Any()
        private const val ALIAS="herrin-lan-tls-envelope-v1"
        private data class Cached(val path: String, val host: String, val envelope: ByteArray, val identity: HeldCertificate)
        private var cached: Cached? = null // One current identity; no private material in preferences or logs.
    }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun load(host: String): HeldCertificate = synchronized(lock) {
        LanConfigIdentityPolicy.address(host)
        directory.mkdirs()
        val file=AtomicFile(File(directory,"identity.gcm"))
        val path = directory.canonicalPath
        val envelope = runCatching { file.openRead().use { input ->
            require(input.available() in 29..32768); input.readBytes()
        } }.getOrNull()
        cached?.takeIf { it.path == path && it.host == host && envelope != null &&
            java.security.MessageDigest.isEqual(it.envelope, envelope) }?.let { current ->
            if (runCatching { current.identity.certificate.checkValidity(Date(clock())) }.isSuccess)
                return@synchronized current.identity
        }
        val wrapping=key() // Failure is surfaced to the manual-configuration fallback, never plaintext storage.
        val saved=runCatching {
            val bytes=requireNotNull(envelope)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,wrapping,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            val identity=HeldCertificate.decode(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
            identity.certificate.checkValidity(Date(clock()))
            require(identity.certificate.subjectAlternativeNames.orEmpty().any { it[0]==7 && it[1]==host })
            identity
        }.getOrNull()
        if(saved!=null) { cached=Cached(path,host,requireNotNull(envelope),saved); return@synchronized saved }
        val now=clock()
        val fresh=HeldCertificate.Builder().commonName("HERRIN local configuration")
            .addSubjectAlternativeName(host).validityInterval(now-300_000L,now+365L*24*60*60*1000).build()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,wrapping)
        val encrypted=cipher.iv+cipher.doFinal((fresh.certificatePem()+fresh.privateKeyPkcs8Pem()).toByteArray(Charsets.UTF_8))
        val output=file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch(error: Exception){file.failWrite(output);throw error}
        cached=Cached(path,host,encrypted,fresh)
        fresh
    }
}
