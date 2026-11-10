package tv.herrin.app

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Bound TCP connection establishment as well as SMB request/read timeouts. */
internal class SmbSocketFactory:SocketFactory() {
    override fun createSocket():Socket=Socket().apply{soTimeout=3000}
    private fun connected(remote:InetSocketAddress,local:InetSocketAddress?=null):Socket {
        val socket=createSocket()
        try {
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            local?.let{socket.bind(it)}
            socket.connect(remote,3000)
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            return socket
        }catch(error:Exception){socket.close();throw error}
    }
    override fun createSocket(host:String,port:Int)=connected(InetSocketAddress(host,port))
    override fun createSocket(host:InetAddress,port:Int)=connected(InetSocketAddress(host,port))
    override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=connected(InetSocketAddress(host,port),InetSocketAddress(local,localPort))
    override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=connected(InetSocketAddress(host,port),InetSocketAddress(local,localPort))
}
