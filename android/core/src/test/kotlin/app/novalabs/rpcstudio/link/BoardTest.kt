package app.novalabs.rpcstudio.link

import com.fazecast.jSerialComm.SerialPort
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real board on this computer's serial port. Runs only with RPC_PORT set (and RPC_PASSWORD
 *  for the sudo part); otherwise it is skipped, never counted as passed. */
class BoardTest {
    private class SerialTransport(path: String) : Transport {
        private val port = SerialPort.getCommPort(path).apply {
            baudRate = 115200
            setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0)
            check(openPort()) { "could not open $path" }
            setDTR()
        }
        override val name = path
        override fun read(timeoutMs: Long): ByteArray {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val n = port.bytesAvailable()
                if (n < 0) throw DeviceGone()
                if (n > 0) {
                    val b = ByteArray(n)
                    val got = port.readBytes(b, n)
                    return if (got > 0) b.copyOf(got) else ByteArray(0)
                }
                if (System.nanoTime() >= end) return ByteArray(0)
                Thread.sleep(2)
            }
        }
        override fun write(data: ByteArray) { if (port.writeBytes(data, data.size) < 0) throw DeviceGone() }
        override fun close() { port.closePort() }
    }

    @Test fun `a real board`() {
        val path = System.getProperty("rpc.port")
        assumeTrue(path != null, "RPC_PORT is not set")
        val pw = System.getProperty("rpc.password")
        Device(SerialTransport(path!!), sudoPassword = pw?.let { p -> { p } }).connect().use { d ->
            assertTrue((d.hello["os"] ?: "").startsWith("v2."), "${d.hello}")
            val r = d.exec("ver")
            assertEquals(0, r.rc)
            assertTrue("RPCortex" in r.text, r.text)

            val data = ByteArray(50_000) { (it * 7 + 3).toByte() }
            val t0 = System.nanoTime()
            d.put(data, "/tmp/kotlin-test.bin")
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("put 50 KB: $ms ms (${50_000 * 1000L / maxOf(ms, 1) / 1024} KB/s)")
            assertContentEquals(data, d.get("/tmp/kotlin-test.bin"))
            assertTrue(d.ls("/tmp").any { it.name == "kotlin-test.bin" && it.size == 50_000L })
            d.rm("/tmp/kotlin-test.bin")
            assertNull(d.stat("/tmp/kotlin-test.bin"))

            if (pw != null) {
                d.put("from kotlin\n".toByteArray(), "/etc/kotlin-test.cfg")
                assertEquals("from kotlin", String(d.get("/etc/kotlin-test.cfg")).trim())
                d.rm("/etc/kotlin-test.cfg")
            }
            val timed = d.exec("sleep 20", timeoutMs = 1500)
            assertTrue(timed.timedOut, "a command past its time is stopped")
            assertEquals("after", d.exec("echo after").text.trim())
        }
    }

    /** Pairing over USB, then link mode over SSH. RPC_PORT and RPC_SSH=1, with the board on Wi-Fi. */
    @Test fun `pairing, then link mode over ssh`() {
        val path = System.getProperty("rpc.port")
        assumeTrue(path != null && System.getProperty("rpc.ssh") != null, "RPC_PORT and RPC_SSH are not set")
        val pw = System.getProperty("rpc.password")
        val usb = {
            val t = SerialTransport(path!!)
            try { Device(t, sudoPassword = pw?.let { p -> { p } }).connect() } catch (e: Exception) { t.close(); throw e }
        }
        val key = SshKey.generate("studio-test")
        val paired = usb().use { d -> Pairing.pair(d, key) }
        println("paired: $paired")
        assertTrue(paired.fingerprint.startsWith("SHA256:"))

        Device(SshTransport(paired.host, paired.port, paired.user, key, paired.fingerprint)).connectLinked().use { d ->
            assertEquals(paired.user, d.hello["user"])
            assertTrue("RPCortex" in d.exec("ver").text)
            val data = ByteArray(50_000) { (it * 13 + 1).toByte() }
            val t0 = System.nanoTime()
            d.put(data, "/tmp/ssh-test.bin")
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("put 50 KB over ssh: $ms ms (${50_000 * 1000L / maxOf(ms, 1) / 1024} KB/s)")
            assertContentEquals(data, d.get("/tmp/ssh-test.bin"))
            d.rm("/tmp/ssh-test.bin")
            d.link!!.ping()

            // One host tool at a time: the console says so, in words.
            val busy = runCatching { usb().close() }.exceptionOrNull()
            assertTrue(busy is RpcError && "already talking" in (busy.message ?: ""), "$busy")
        }

        // A board presenting another key is refused before anything is sent.
        val wrong = runCatching { SshTransport(paired.host, paired.port, paired.user, key, "SHA256:AAAA") }.exceptionOrNull()
        assertTrue(wrong is RpcError && "not the one paired" in (wrong.message ?: ""), "$wrong")

        usb().use { d -> Pairing.unpair(d, key) }
        val gone = runCatching { SshTransport(paired.host, paired.port, paired.user, key, paired.fingerprint) }.exceptionOrNull()
        assertTrue(gone is RpcError && "turned this phone's key away" in (gone.message ?: ""), "$gone")
    }
}
