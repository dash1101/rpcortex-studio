package app.novalabs.rpcstudio.link

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A console relayed over a plain TCP socket: for development only (a relay on a computer that has
 * the board on its serial port, reached from an emulator). Nothing on it is encrypted or
 * authenticated beyond the device's own login and sudo, so it is not how the app connects to a
 * board on a network; the wireless link uses SSH for that.
 */
class TcpTransport(host: String, port: Int, connectTimeoutMs: Int = 5000) : Transport {
    private val sock = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), connectTimeoutMs) }
    private val inbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lost = false
    @Volatile private var closed = false
    override val name = "$host:$port"

    init {
        Thread({
            val buf = ByteArray(8192)
            val inp = sock.getInputStream()
            while (!closed) {
                val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) { lost = true; break }
                if (n > 0) inbox.put(buf.copyOf(n))
            }
        }, "tcp-console").apply { isDaemon = true; start() }
    }

    override fun read(timeoutMs: Long): ByteArray {
        if (closed) throw DeviceGone("closed")
        val first = (if (timeoutMs <= 0) inbox.poll() else inbox.poll(timeoutMs, TimeUnit.MILLISECONDS))
        if (first == null) {
            if (lost) throw DeviceGone("The connection to $name closed.")
            return ByteArray(0)
        }
        var out = first
        while (true) out += inbox.poll() ?: break
        return out
    }

    override fun write(data: ByteArray) {
        if (closed || lost) throw DeviceGone("The connection to $name closed.")
        try { sock.getOutputStream().apply { write(data); flush() } } catch (e: Exception) {
            lost = true; throw DeviceGone("The connection to $name closed.")
        }
    }

    override fun close() {
        closed = true
        try { sock.close() } catch (_: Exception) { }
    }
}
