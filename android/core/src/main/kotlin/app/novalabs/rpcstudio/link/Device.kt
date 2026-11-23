package app.novalabs.rpcstudio.link

/**
 * A device, connected in link mode. Ported from the desktop tool's rpctool/device.py.
 *
 * Firmware from before link mode (v2.00-beta) is not driven here: it is told to update, which
 * this app can do over BOOTSEL or which the device can do itself.
 */

private val SHELL_PROMPT = Regex("\\S+@\\S+:.*>\\s*$")

private fun lastLine(text: String): String = text.replace("\r\n", "\n").split("\n").last().split("\r").last()

/** What the console is showing, from what it printed after a Ctrl+C:
 *  "shell", "login", "password", "link", "other", or null for nothing at all. */
fun classify(data: ByteArray): String? {
    var text = Ansi.strip(data)
    if ('\u001e' in text) {
        var tail = text.substring(text.lastIndexOf('\u001e'))
        tail = if ('\n' in tail) tail.substringAfter('\n') else ""
        if (!(lastLine(tail).isNotEmpty() && SHELL_PROMPT.containsMatchIn(lastLine(tail)))) return "link"
        text = tail
    }
    val last = lastLine(text)
    if ("Username" in last) return "login"
    if ("Password" in last) return "password"
    if (SHELL_PROMPT.containsMatchIn(last) || "^C" in text) return "shell"
    return if (text.isNotBlank()) "other" else null
}

/** Quote one argument for the device's shell: '...' or "..." (it has no escape character). */
fun quote(arg: String): String {
    if (arg.isNotEmpty() && Regex("^[A-Za-z0-9_./@%+=:,-]+$").matches(arg)) return arg
    if ('"' !in arg) return "\"$arg\""
    if ('\'' !in arg) return "'$arg'"
    throw RpcError("'$arg' holds both kinds of quote, which the device's shell cannot be given")
}

/** An absolute, normalised device path. */
fun rnorm(path: String, cwd: String = "/"): String {
    val p = if (path.isEmpty()) "." else path
    val full = if (p.startsWith("/")) p else (cwd.ifEmpty { "/" }).trimEnd('/') + "/" + p
    val out = ArrayList<String>()
    for (part in full.split("/")) {
        when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
    }
    return "/" + out.joinToString("/")
}

class Device(
    val t: Transport,
    val user: String? = null,
    /** The login password: a fixed string, or asked for when the login prompt wants it. */
    val password: (() -> String?)? = null,
    val timing: Timing = Timing(),
    val onNoise: ((String) -> Unit)? = null,
    /** For sudo (protocol 2): asked for once, when the device first says a request needs it. */
    val sudoPassword: (() -> String?)? = null,
    val bridge: FetchServer? = null,
) : AutoCloseable {
    var link: LinkClient? = null; private set
    var gone = false
    /** Which try at the sudo password this is (0, 1, 2), so a prompt can say the last one was refused. */
    var sudoAttempt = 0; private set
    private var sudoKnown: String? = null

    val hello: Map<String, String> get() = link?.hello ?: emptyMap()
    val cwd: String get() = hello["cwd"] ?: "/"
    val proto: Int get() = link?.proto ?: 0
    val hasSudo: Boolean get() = proto >= 2
    val bridged: Boolean get() = link?.bridged == true

    private fun l(): LinkClient = link ?: throw RpcError("not connected")

    /** Get from whatever the console is doing to link mode. */
    fun connect(): Device {
        Thread.sleep(timing.settle / 2)
        t.discardInput()
        repeat(4) {
            t.write(byteArrayOf(0x03))
            var seen = collect(timing.settle)
            var state = classify(seen)
            if (state == null) {
                // Nothing at all. A login prompt ignores Ctrl+C, so ask with an empty
                // line, which every prompt refuses without counting it.
                t.write("\r".toByteArray())
                seen = collect(timing.settle * 2)
                state = classify(seen)
            }
            when (state) {
                "password" -> throw LoginRequired(
                    "The device is waiting for a password that something else started. Finish or " +
                    "abandon that login in a terminal, then try again.")
                "login" -> { login(); return@repeat }
                "link" -> {
                    // Still in link mode from an earlier client: the Ctrl+C ended its
                    // transfer, not link mode itself.
                    val lc = LinkClient(t, FrameReader(t, onNoise), timing = timing)
                    link = lc
                    if (lc.resync()) return linked()
                    link = null
                    return@repeat
                }
            }
            t.write("link\r".toByteArray())
            when (awaitHello()) {
                "hello" -> return linked()
                "old" -> throw NotSupported(
                    "This firmware predates link mode (v2.00-beta). Update it first: the Firmware " +
                    "screen can write a new image over BOOTSEL.")
                "login" -> login()
            }
        }
        throw RpcError("The device did not answer. Is it running RPCortex, and is the shell running?")
    }

    private fun linked(): Device {
        val b = bridge
        if (b != null) {
            l().bridge = b
            try { l().bridgeOn() } catch (_: RpcError) { }   // commands still run, without a network
        }
        return this
    }

    private fun collect(ms: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return out.toByteArray()
            out.write(t.read(minOf(left, 100)))
        }
    }

    private fun awaitHello(): String? {
        var buf = ByteArray(0)
        val end = System.nanoTime() + timing.enter * 1_000_000
        val marker = byteArrayOf(FRAME_START) + "HELLO".toByteArray()
        while (System.nanoTime() < end) {
            buf += t.read(100)
            val at = indexOf(buf, marker)
            if (at >= 0) {
                val nl = indexOf(buf, byteArrayOf('\n'.code.toByte()), at)
                if (nl >= 0) {
                    var body = buf.copyOfRange(at + 1, nl)
                    if (body.isNotEmpty() && body.last() == '\r'.code.toByte()) body = body.copyOfRange(0, body.size - 1)
                    val reader = FrameReader(t, onNoise)
                    reader.feed(buf.copyOfRange(nl + 1, buf.size))
                    val lc = LinkClient(t, reader, timing = timing)
                    lc.setHello(Frame(body))
                    link = lc
                    return "hello"
                }
                continue
            }
            val clean = Ansi.strip(buf)
            if ("'link' is not a command" in clean) { collect(timing.settle); return "old" }
            if ("Username" in lastLine(clean)) return "login"
            val fs = buf.indexOf(FRAME_START)
            if (fs >= 0 && buf.lastIndexOf('\n'.code.toByte()) > fs) return "link"
        }
        return null
    }

    private fun type(text: String) {
        for (b in text.toByteArray()) {
            t.write(byteArrayOf(b))
            if (timing.typeDelay > 0) Thread.sleep(timing.typeDelay)
        }
        t.write("\r".toByteArray())
    }

    private fun waitFor(patterns: List<Regex>, ms: Long): Int? {
        var buf = ByteArray(0)
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            buf += t.read(100)
            val text = Ansi.strip(buf)
            patterns.forEachIndexed { i, p -> if (p.containsMatchIn(text)) return i }
        }
        return null
    }

    private fun login() {
        val u = user ?: throw LoginRequired(
            "The device is at a login prompt. Give an account to log in with, or log in from a terminal first.")
        type(u)
        val opts = setOf(RegexOption.MULTILINE)
        val i = waitFor(listOf(Regex("not found", opts), Regex("Password[^\\n]*$", opts),
                               Regex("Welcome|No password required", opts)), 5000)
        when (i) {
            0 -> throw LoginFailed("The device has no account called '$u'.")
            1 -> {
                val pw = password?.invoke()
                if (pw.isNullOrEmpty()) throw LoginRequired("The account '$u' needs a password.")
                type(pw)
                val j = waitFor(listOf(Regex("Incorrect password|Too many"), Regex("Welcome")), 12_000)
                if (j != 1) throw LoginFailed("The device refused the password for '$u'.")
            }
            null -> throw LoginFailed("No answer to the username.")
        }
        collect(timing.settle)
    }

    /** Back to the normal prompt, then let go of the port. */
    override fun close() {
        try {
            val lc = link
            if (!gone && lc != null) {
                lc.busy?.let { kind -> lc.busy = null; lc.abort(kind) }
                lc.bridgeOff()
                // Not in link mode after all, or not answering: a Ctrl+C leaves link
                // mode, and at the prompt it is harmless.
                if (!lc.bye()) t.write(byteArrayOf(0x03))
            }
        } catch (_: RpcError) {
        } catch (_: java.io.IOException) {
        } finally {
            t.close()
        }
    }

    // --- sudo ------------------------------------------------------------------

    fun sudoLine(line: String): String = if (hasSudo) "sudo $line" else line

    /** A file command, with sudo when it touches /os or /etc. */
    private fun filesLine(line: String, vararg paths: String): String {
        for (p0 in paths) {
            val p = abspath(p0)
            if (p == "/os" || p == "/etc" || p.startsWith("/os/") || p.startsWith("/etc/")) return sudoLine(line)
        }
        return line
    }

    /** AUTH with the account's password, asking for it (up to three times when it is typed). */
    fun authenticate(): Boolean {
        if (!hasSudo) return false
        val who = hello["user"] ?: "this account"
        for (attempt in 0 until 3) {
            sudoAttempt = attempt
            val known = sudoKnown
            val pw = known ?: sudoPassword?.invoke()
            if (pw.isNullOrEmpty()) throw RpcError("This needs sudo on the device, which asks for $who's password.")
            try {
                l().auth(pw)
                sudoKnown = pw
                return true
            } catch (e: LinkError) {
                if (e.code != "auth") throw e
                sudoKnown = null
                if (known != null) continue
            }
        }
        throw RpcError("Three wrong passwords for $who; nothing was done.")
    }

    private fun <T> withSudo(fn: () -> T): T {
        try {
            return fn()
        } catch (e: LinkError) {
            if (e.code != "sudo" || !hasSudo) throw e
        }
        authenticate()
        return fn()
    }

    // --- requests --------------------------------------------------------------

    fun exec(line: String, timeoutMs: Long? = null): ExecResult = withSudo { l().exec(line, timeoutMs) }

    /** exec, throwing the device's own words when it fails. */
    fun check(line: String, timeoutMs: Long? = null): ExecResult {
        val r = exec(line, timeoutMs)
        if (r.rc != 0) throw RpcError(r.text.trim().ifEmpty { "'$line' failed" })
        return r
    }

    fun put(data: ByteArray, path: String, progress: ((Long, Long) -> Unit)? = null): String =
        withSudo { l().put(data, path, progress) }

    fun get(path: String, progress: ((Long, Long) -> Unit)? = null): ByteArray =
        withSudo { l().get(path, progress) }

    fun ls(path: String = "."): List<Entry> = withSudo { l().ls(path) }

    fun stat(path: String): Pair<String, Long>? = withSudo { l().stat(path) }

    fun isDir(path: String): Boolean = stat(path)?.first == "d"

    fun abspath(path: String): String = rnorm(path, cwd)

    fun mkdir(path0: String, parents: Boolean = false) {
        val path = abspath(path0)
        if (!parents) { check(filesLine("mkdir " + quote(path), path)); return }
        var cur = ""
        for (p in path.split("/").filter { it.isNotEmpty() }) {
            cur += "/$p"
            val st = stat(cur)
            if (st == null) check(filesLine("mkdir " + quote(cur), cur))
            else if (st.first != "d") throw RpcError("$cur is a file, not a directory")
        }
    }

    fun rm(path: String, recursive: Boolean = false): ExecResult {
        if ('*' in path) throw RpcError("'$path': rm on the device treats * as a wildcard; refusing a name that has one")
        return check(filesLine("rm " + (if (recursive) "-r " else "") + quote(path), path))
    }

    fun mv(src: String, dst: String): ExecResult =
        check(filesLine("mv ${quote(src)} ${quote(dst)}", src, dst))

    /** Restart the device. Returns null once it has gone, or the command's result when the
     *  device refused (an account that is not admin). */
    fun reboot(): ExecResult? {
        val lc = l()
        lc.send("EXEC reboot")
        val out = java.io.ByteArrayOutputStream()
        val end = System.nanoTime() + 4_000_000_000L
        try {
            while (System.nanoTime() < end) {
                val f = lc.r.nextFrame(300) ?: continue
                if (f.type == "OUT") out.write(b64(f.rest))
                else if (f.isFinal) {
                    val rc = if (f.type == "OK") f.kv()["rc"]?.toIntOrNull() ?: 1 else 1
                    return ExecResult(rc, out.toByteArray())
                }
            }
        } catch (_: DeviceGone) { }
        gone = true
        return null
    }

    companion object {
        internal fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
