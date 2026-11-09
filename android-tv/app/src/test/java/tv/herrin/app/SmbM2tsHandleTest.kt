package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SmbM2tsHandleTest {
    private class Packets(override val size:Long,val stride:Int=192):SmbReadHandle {
        override val version="original-layout"
        var closed=false
        override fun read(buffer:ByteArray,position:Long,offset:Int,length:Int):Int {
            check(!closed)
            if(position>=size)return -1
            // Deliberately short transport reads, including split headers/packets.
            val n=minOf(length.toLong(),size-position,137).toInt()
            for(i in 0 until n) {
                val p=position+i;val inside=(p%stride).toInt();val prefix=stride-188
                buffer[offset+i]=if(inside==prefix)0x47 else if(inside<prefix)0 else ((p/stride+inside-prefix)%251).toByte()
            }
            return n
        }
        override fun close(){closed=true}
    }
    @Test fun arbitraryOffsetsAndLargeSeeksPreserveAllTransportPayloadBytes() {
        val physical=Packets(192L*40_000_000)
        val ts=SmbM2tsHandle.adapt(physical)
        assertEquals(188L*40_000_000,ts.size)
        for(start in listOf(0L,1L,187L,188L,189L,5L*1024*1024*1024+173,ts.size-203)) {
            val actual=ByteArray(1700){-1}
            val n=ts.read(actual,start,7,1690)
            assertTrue(n>0)
            for(i in 0 until n) {
                val p=start+i;val within=p%188
                assertEquals(if(within==0L)0x47.toByte() else ((p/188+within)%251).toByte(),actual[7+i])
            }
            assertEquals((-1).toByte(),actual[6]);assertEquals((-1).toByte(),actual[7+n])
        }
        assertEquals(-1,ts.read(ByteArray(1),ts.size,0,1))
        ts.close();assertTrue(physical.closed)
    }
    @Test fun ordinaryTsPassesThroughAndMalformedOrTruncatedBdavFailsClosed() {
        val plain=Packets(188L*20,188)
        assertSame(plain,SmbM2tsHandle.adapt(plain));plain.close()
        val bad=Packets(192L*20+1)
        assertThrows(IOException::class.java){SmbM2tsHandle.adapt(bad)}
        assertTrue(bad.closed)
        val short=Packets(192)
        assertThrows(IOException::class.java){SmbM2tsHandle.adapt(short)}
        assertTrue(short.closed)
    }
}
