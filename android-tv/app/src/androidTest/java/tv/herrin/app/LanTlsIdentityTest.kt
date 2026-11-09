package tv.herrin.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LanTlsIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun reopeningAndReconstructingStoreKeepsIdentityWithoutPlaintextPrivateKey() {
        val directory=File(context.noBackupFilesDir,"tls-test-"+java.util.UUID.randomUUID())
        try {
            val first=LanTlsIdentity(context,directory).load("127.0.0.1")
            val second=LanTlsIdentity(context,directory).load("127.0.0.1")
            assertArrayEquals(first.certificate.encoded,second.certificate.encoded)
            assertArrayEquals(first.keyPair.private.encoded,second.keyPair.private.encoded)
            val bytes=File(directory,"identity.gcm").readBytes()
            assertFalse(String(bytes,Charsets.ISO_8859_1).contains("PRIVATE KEY"))
            assertFalse(String(bytes,Charsets.ISO_8859_1).contains(first.privateKeyPkcs8Pem()))
            val raw=first.keyPair.private.encoded.toList()
            assertFalse(bytes.toList().windowed(raw.size).any{it==raw})
            val store=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            assertNull(store.getKey("herrin-lan-tls-envelope-v1",null).encoded)
            assertTrue(directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        }finally{directory.deleteRecursively()}
    }
    @Test fun addressChangeExpiryAndTamperingRotateCertificateWithCorrectSan() {
        val directory=File(context.noBackupFilesDir,"tls-test-"+java.util.UUID.randomUUID())
        try {
            val first=LanTlsIdentity(context,directory).load("127.0.0.1")
            val changed=LanTlsIdentity(context,directory).load("127.0.0.2")
            assertFalse(first.certificate.encoded.contentEquals(changed.certificate.encoded))
            assertTrue(changed.certificate.subjectAlternativeNames.any{it[0]==7 && it[1]=="127.0.0.2"})
            val later=System.currentTimeMillis()+366L*24*60*60*1000
            val renewed=LanTlsIdentity(context,directory){later}.load("127.0.0.2")
            assertFalse(renewed.certificate.encoded.contentEquals(changed.certificate.encoded))
            renewed.certificate.checkValidity(java.util.Date(later))
            val file=File(directory,"identity.gcm")
            file.writeBytes(file.readBytes().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
            val recovered=LanTlsIdentity(context,directory){later}.load("127.0.0.2")
            assertFalse(renewed.certificate.encoded.contentEquals(recovered.certificate.encoded))
        }finally{directory.deleteRecursively()}
    }
}
