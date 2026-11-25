package app.novalabs.rpcstudio.link

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin client against Studio's mock device: the same device the desktop tool is tested on. */
class LinkMockTest {
    private val opened = ArrayList<MockTransport>()

    private fun mock(opts: String = "{}"): MockTransport = MockTransport(opts).also { opened.add(it) }

    private fun connect(t: Transport, sudo: (() -> String?)? = null, user: String? = null,
                        password: String? = null): Device =
        Device(t, user = user, password = password?.let { p -> { p } }, timing = Timing.fast(),
               sudoPassword = sudo).connect()

    @AfterTest fun closeAll() { opened.forEach { it.close() } }

    @Test fun `connects and reads HELLO`() {
        val d = connect(mock())
        assertEquals("pico2_w", d.hello["board"])
        assertEquals("root", d.hello["user"])
        assertTrue(d.proto >= 2, "protocol 2 or later")
    }

    @Test fun `exec returns output and exit status`() {
        val d = connect(mock())
        val r = d.exec("echo hello there")
        assertEquals(0, r.rc)
        assertEquals("hello there", r.text.trim())
        assertTrue(d.exec("fail").rc != 0, "a failing command reports it")
    }

    @Test fun `exec times out with a Ctrl+C and the link survives`() {
        val d = connect(mock())
        val r = d.exec("sleep 30", timeoutMs = 300)
        assertTrue(r.timedOut, "timed out")
        assertEquals("still here", d.exec("echo still here").text.trim())
    }

    @Test fun `put and get round trip, binary, across many chunks, a path with a space`() {
        val d = connect(mock())
        val data = ByteArray(10_000) { (it * 31 + 7).toByte() }
        var last = 0L
        d.put(data, "/home/root/a file.bin") { done, _ -> last = done }
        assertEquals(data.size.toLong(), last, "progress reached the end")
        assertContentEquals(data, d.get("/home/root/a file.bin"))
        assertEquals("-" to 10_000L, d.stat("/home/root/a file.bin")?.let { (it.first.take(1)) to it.second }?.let { (t, s) -> (if (t == "f") "-" else t) to s })
    }

    @Test fun `ls and stat`() {
        val d = connect(mock())
        d.put("x".toByteArray(), "/home/root/one.txt")
        d.mkdir("/home/root/sub/deeper", parents = true)
        val names = d.ls("/home/root").map { it.name to it.isDir }
        assertTrue("one.txt" to false in names, "$names")
        assertTrue("sub" to true in names, "$names")
        assertNull(d.stat("/home/root/nothing-here"))
        assertTrue(d.isDir("/home/root/sub/deeper"))
    }

    @Test fun `sudo is asked for once, after a wrong password`() {
        val tries = ArrayDeque(listOf("nope", "rootpw"))
        var asked = 0
        val t = mock()
        val d = connect(t, sudo = { asked++; tries.removeFirstOrNull() })
        d.put("cfg".toByteArray(), "/etc/test.cfg")
        assertEquals(2, asked, "asked twice: wrong, then right")
        assertEquals(1, d.sudoAttempt, "the second try is the one that worked")
        d.put("again".toByteArray(), "/etc/test2.cfg")
        assertEquals(2, asked, "the grace covers the next one")
        d.close()
        assertTrue("\"nope\"" in t.state && "\"rootpw\"" in t.state, t.state)
    }

    @Test fun `sudo without a password says so`() {
        val d = connect(mock())
        val e = assertFailsWith<RpcError> { d.put("x".toByteArray(), "/etc/x.cfg") }
        assertTrue("sudo" in (e.message ?: ""), e.message)
    }

    @Test fun `logs in at a login prompt`() {
        val d = connect(mock("""{"login": true}"""), user = "root", password = "rootpw")
        assertEquals("root", d.hello["user"])
        assertEquals(0, d.exec("echo in").rc)
    }

    @Test fun `a login prompt without an account is a clear error`() {
        assertFailsWith<LoginRequired> { connect(mock("""{"login": true}""")) }
    }

    @Test fun `picks up a device another client left in link mode`() {
        val t = mock()
        val first = Device(t, timing = Timing.fast()).connect()
        first.exec("echo one")
        // The first client goes away without BYE; a new one connects on the same port.
        val second = Device(t, timing = Timing.fast()).connect()
        assertEquals("two", second.exec("echo two").text.trim())
    }

    @Test fun `a corrupted upload is caught`() {
        val d = connect(mock("""{"_attrs": {"corrupt_put": true}}"""))
        assertFailsWith<RpcError> { d.put(ByteArray(3000) { 1 }, "/home/root/bad.bin") }
    }

    @Test fun `an upload failing midway, with a piece sent ahead, leaves the link in step`() {
        val d = connect(mock("""{"_attrs": {"put_fail_at": 2304}}"""))
        assertFailsWith<RpcError> { d.put(ByteArray(8000) { 3 }, "/home/root/f.bin") }
        assertEquals("in step", d.exec("echo in step").text.trim())
    }

    @Test fun `uploads one piece at a time to firmware without a window`() {
        val d = connect(mock("""{"_attrs": {"put_window": null}}"""))
        val data = ByteArray(5000) { (it * 7).toByte() }
        d.put(data, "/home/root/w.bin")
        assertContentEquals(data, d.get("/home/root/w.bin"))
    }

    @Test fun `a corrupted download is caught`() {
        val t = mock("""{"_attrs": {"corrupt_get": true}}""")
        val d = connect(t)
        d.put(ByteArray(3000) { 2 }, "/home/root/g.bin")
        assertFailsWith<RpcError> { d.get("/home/root/g.bin") }
    }

    @Test fun `background noise between frames is not mistaken for answers`() {
        val seen = ArrayList<String>()
        val d = Device(mock("""{"noise": 0.3}"""), timing = Timing.fast(), onNoise = { seen.add(it) }).connect()
        repeat(10) { assertEquals("n$it", d.exec("echo n$it").text.trim()) }
        val data = ByteArray(5000) { it.toByte() }
        d.put(data, "/home/root/n.bin")
        assertContentEquals(data, d.get("/home/root/n.bin"))
    }

    @Test fun `close returns the device to its prompt`() {
        val t = mock()
        val d = Device(t, timing = Timing.fast()).connect()
        d.close()
        // A fresh connection finds a prompt, not link mode, and enters again.
        val t2 = mock()
        assertEquals(0, connect(t2).exec("echo ok").rc)
    }

    @Test fun `big output arrives whole`() {
        val d = connect(mock())
        val r = d.exec("big")
        assertTrue(r.output.size > 2000, "got ${r.output.size} bytes")
    }

    @Test fun `quote and rnorm`() {
        assertEquals("plain/path.txt", quote("plain/path.txt"))
        assertEquals("\"a b\"", quote("a b"))
        assertEquals("'say \"hi\"'", quote("say \"hi\""))
        assertFailsWith<RpcError> { quote("both ' and \"") }
        assertEquals("/a/c", rnorm("../c", "/a/b"))
        assertEquals("/", rnorm("../../..", "/a"))
        assertEquals("/home/root/x", rnorm("x", "/home/root"))
    }
}
