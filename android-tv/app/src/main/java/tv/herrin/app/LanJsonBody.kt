package tv.herrin.app

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object LanJsonBody {
    fun read(stream: InputStream, length: Int): String {
        require(length in 1..32768)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = stream.read(bytes, offset, length - offset)
            require(count > 0) { "INCOMPLETE_JSON_BODY" }
            offset += count
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
