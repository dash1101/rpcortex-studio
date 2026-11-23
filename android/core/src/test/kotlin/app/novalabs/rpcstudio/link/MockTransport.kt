package app.novalabs.rpcstudio.link

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Studio's own mock device (tests/mockdev.py), run in Python as a child process and spoken to over
 * its stdin and stdout: the Kotlin client is tested against exactly what the desktop tool is.
 */
class MockTransport(options: String = "{}") : Transport {
    private val root = System.getProperty("studio.root") ?: error("studio.root is not set")
    private val script = File("src/test/python/mock_pipe.py").absolutePath
    private val proc: Process = ProcessBuilder("python3", script, root, options).start()
    private val inbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    var state: String = ""; private set
    override val name = "mock"

    init {
        Thread {
            val buf = ByteArray(4096)
            val inp = proc.inputStream
            while (true) {
                val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n > 0) inbox.put(buf.copyOf(n))
            }
        }.apply { isDaemon = true; start() }
    }

    override fun read(timeoutMs: Long): ByteArray {
        if (closed) throw DeviceGone("closed")
        val first = (if (timeoutMs <= 0) inbox.poll() else inbox.poll(timeoutMs, TimeUnit.MILLISECONDS))
            ?: return ByteArray(0)
        var out = first
        while (true) out += inbox.poll() ?: break
        return out
    }

    override fun write(data: ByteArray) {
        if (closed) throw DeviceGone("closed")
        proc.outputStream.write(data)
        proc.outputStream.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { proc.outputStream.close() } catch (_: Exception) { }
        proc.waitFor(5, TimeUnit.SECONDS)
        state = proc.errorStream.bufferedReader().readText().trim().lines().lastOrNull() ?: ""
        proc.destroy()
    }
}
