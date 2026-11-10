package tv.herrin.app

import java.io.EOFException
import java.io.IOException

/** BDAV has a four-byte arrival timestamp before every 188-byte MPEG-TS packet.
 * Present a seekable TS byte view to the existing Media3 extractor. No remux,
 * whole-file download, new player, or timestamp/payload modification is involved.
 */
internal class SmbM2tsHandle private constructor(private val file:SmbReadHandle):SmbReadHandle {
    override val size=file.size/192*188
    override val version=file.version+":bdav-ts-v1"
    private val packets=ByteArray(192*512)
    override fun read(buffer:ByteArray,position:Long,offset:Int,length:Int):Int {
        require(position>=0 && offset>=0 && length>=0 && offset<=buffer.size-length)
        if(length==0)return 0
        if(position>=size)return -1
        val packet=position/188
        val within=(position%188).toInt()
        val wanted=minOf(length.toLong(),size-position,(188L*512)-within).toInt()
        val count=(within+wanted+187)/188
        fill(file,packets,packet*192,count*192)
        var written=0;var skip=within
        for(index in 0 until count) {
            if(packets[index*192+4]!=0x47.toByte())throw IOException("SMB_BDAV_SYNC_INVALID")
            val n=minOf(188-skip,wanted-written)
            System.arraycopy(packets,index*192+4+skip,buffer,offset+written,n)
            written+=n;skip=0
        }
        return written
    }
    override fun close()=file.close()
    companion object {
        fun adapt(file:SmbReadHandle):SmbReadHandle {
            try {
                val header=ByteArray(minOf(file.size,960).toInt())
                if(header.size<384)throw IOException("SMB_BDAV_TOO_SHORT")
                fill(file,header,0,header.size)
                if(file.size%192==0L && (0 until minOf(5,header.size/192)).all{header[it*192+4]==0x47.toByte()})
                    return SmbM2tsHandle(file)
                // Some servers use .m2ts for ordinary TS. Keep that stream intact.
                if(file.size%188==0L && (0 until minOf(5,header.size/188)).all{header[it*188]==0x47.toByte()})return file
                throw IOException("SMB_BDAV_PACKET_LAYOUT_INVALID")
            }catch(error:Exception){file.close();throw error}
        }
        private fun fill(file:SmbReadHandle,target:ByteArray,position:Long,length:Int) {
            var done=0
            while(done<length) {
                if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("SMB_READ_CANCELLED")
                val n=file.read(target,position+done,done,length-done)
                if(n<=0)throw EOFException("SMB_BDAV_TRUNCATED")
                done+=n
            }
        }
    }
}
