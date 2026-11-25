package app.novalabs.rpcstudio.link

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * The link protocol client (LINK.md in the RPCortex repository, protocols 1 to 3), ported from the
 * desktop tool's rpctool/link.py and kept in step with it.
 *
 * One request at a time, read to its final OK or ERR before the next is sent. Anything on the wire
 * that is not a frame is a background line from the device and goes to `onNoise`, never to a parser.
 */

internal const val FRAME_START: Byte = 0x1E
const val LINE_MAX = 1100           // a request line, newline included (LINK.md)
const val DEFAULT_CHUNK = 768
/** The most D lines a PUT keeps unacknowledged, whatever the device offers. */
const val PUT_WINDOW_MAX = 4

/** Every wait, in milliseconds, in one place so tests can shrink them. */
class Timing(
    val settle: Long = 250,         // after a Ctrl+C, before reading what came back
    val enter: Long = 4000,         // from `link` to the HELLO frame
    val request: Long = 10_000,     // an ordinary request to its final frame
    val transfer: Long = 15_000,    // one ACK, D or final frame during PUT and GET
    val abort: Long = 15_000,       // a cancelled request to its final frame
    val bye: Long = 2000,
    val typeDelay: Long = 4,        // between characters typed at the login prompt
) {
    companion object {
        /** For tests against the mock device, which answers at once. */
        fun fast() = Timing(settle = 60, enter = 2000, request = 4000, transfer = 4000, abort = 4000,
                            bye = 800, typeDelay = 0)
    }
}

/** One device-to-host frame: the bytes after 0x1E, without the newline. */
class Frame(val raw: ByteArray) {
    val type: String
    val rest: String

    init {
        val text = String(raw, Charsets.UTF_8)
        val sp = text.indexOf(' ')
        type = if (sp < 0) text else text.substring(0, sp)
        rest = if (sp < 0) "" else text.substring(sp + 1)
    }

    fun kv(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (word in rest.split(" ")) {
            val eq = word.indexOf('=')
            if (eq > 0) out[word.substring(0, eq)] = word.substring(eq + 1)
            else if (eq == 0) out[""] = word.substring(1)
        }
        return out
    }

    val isFinal: Boolean get() = type == "OK" || type == "ERR"

    fun error(): LinkError {
        val sp = rest.indexOf(' ')
        val code = if (sp < 0) rest else rest.substring(0, sp)
        val msg = if (sp < 0) "" else rest.substring(sp + 1)
        return LinkError(code.ifEmpty { "?" }, msg)
    }

    override fun toString() = "Frame(${String(raw, Charsets.UTF_8).take(80)})"
}

/** One LS entry. */
data class Entry(val type: String, val size: Long, val name: String) {
    val isDir: Boolean get() = type == "d"
}

class ExecResult(val rc: Int, val output: ByteArray, val truncated: Boolean = false,
                 val timedOut: Boolean = false) {
    val text: String get() = String(output, Charsets.UTF_8)
}

internal fun noiseText(line: ByteArray): String {
    var text = Ansi.strip(line)
    // The line editor repaints with a carriage return; what is left after the
    // last one is what a terminal would show.
    val trimmed = text.trimEnd('\r')
    if ('\r' in trimmed) text = trimmed.substringAfterLast('\r')
    return text.trimEnd('\r')
}

/**
 * Splits the byte stream into frames and everything else. A frame is found wherever 0x1E appears,
 * not only at the start of a line: a background task that printed without a newline would
 * otherwise hide the frame that follows it. A trailing carriage return is dropped.
 */
class FrameReader(private val t: Transport, private val onNoise: ((String) -> Unit)? = null) {
    private var data = ByteArray(0)       // unread bytes
    private val pending = ArrayDeque<Frame>()

    fun feed(bytes: ByteArray) { data += bytes }

    /** Put frames back, to be returned before anything new. */
    fun unread(frames: List<Frame>) { for (f in frames.asReversed()) pending.addFirst(f) }

    private fun takeLine(): ByteArray? {
        val i = data.indexOf('\n'.code.toByte())
        if (i < 0) return null
        val line = data.copyOfRange(0, i)
        data = data.copyOfRange(i + 1, data.size)
        return line
    }

    private fun noise(line: ByteArray) {
        val cb = onNoise ?: return
        if (line.any { it != '\r'.code.toByte() }) cb(noiseText(line))
    }

    /** The next frame, or null after [timeoutMs] (null: wait for ever). */
    fun nextFrame(timeoutMs: Long?): Frame? {
        pending.removeFirstOrNull()?.let { return it }
        val deadline = timeoutMs?.let { System.nanoTime() + it * 1_000_000 }
        while (true) {
            var line = takeLine()
            while (line != null) {
                val at = line.indexOf(FRAME_START)
                if (at < 0) {
                    noise(line)
                } else {
                    if (at > 0) noise(line.copyOfRange(0, at))
                    var body = line.copyOfRange(at + 1, line.size)
                    if (body.isNotEmpty() && body.last() == '\r'.code.toByte()) body = body.copyOfRange(0, body.size - 1)
                    return Frame(body)
                }
                line = takeLine()
            }
            val wait = if (deadline == null) 500L else {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                minOf(left, 500L)
            }
            val got = t.read(wait)
            if (got.isNotEmpty()) data += got
        }
    }
}

/**
 * Answers the device's FETCH frames (protocol 3, the network bridge). Returns any frames it read
 * that were not its own, to be handed back to the request that was waiting.
 */
interface FetchServer {
    fun serve(first: Frame, next: (Long?) -> Frame?, write: (ByteArray) -> Unit,
              interrupt: (() -> Boolean)?): List<Frame>
}

fun parseHello(frame: Frame): Map<String, String> {
    val info = frame.kv().toMutableMap()
    info["cwd"]?.let { info["cwd"] = URLDecoder.decode(it, "UTF-8") }
    return info
}

/** Requests over a transport that is already in link mode. */
class LinkClient(val t: Transport, reader: FrameReader? = null, onNoise: ((String) -> Unit)? = null,
                 val timing: Timing = Timing()) {
    val r: FrameReader = reader ?: FrameReader(t, onNoise)
    var hello: Map<String, String> = emptyMap(); private set
    var busy: String? = null                // the request in flight: exec, put, get, req
    var dropped = false                     // link mode ended under us (a stray Ctrl+C)
    var bridge: FetchServer? = null
    var bridged = false; private set

    // --- basics ---------------------------------------------------------------

    val chunk: Int get() = hello["chunk"]?.toIntOrNull()?.coerceIn(1, DEFAULT_CHUNK) ?: DEFAULT_CHUNK
    val execMax: Int get() = hello["exec_max"]?.toIntOrNull() ?: 0
    val proto: Int get() = hello["proto"]?.toIntOrNull() ?: 1

    fun setHello(frame: Frame): Map<String, String> { hello = parseHello(frame); return hello }

    fun send(line: String) = t.write(encode(line))

    /** The next frame, answering any FETCH on the way when this client has a bridge. */
    fun next(timeoutMs: Long?, interrupt: (() -> Boolean)? = null): Frame? {
        while (true) {
            val f = r.nextFrame(timeoutMs)
            val b = bridge
            if (f == null || f.type != "FETCH" || b == null) return f
            r.unread(b.serve(f, { r.nextFrame(it) }, { t.write(it) }, interrupt))
        }
    }

    fun frame(timeoutMs: Long): Frame =
        next(timeoutMs) ?: throw DeviceTimeout("no answer from the device in ${timeoutMs / 1000} s")

    /** Read frames to the final one; others are added to [keep]. */
    fun final(timeoutMs: Long, keep: MutableList<Frame>? = null): Frame {
        while (true) {
            val f = frame(timeoutMs)
            if (f.isFinal) return f
            if (f.type == "BYE") {
                // The device left link mode (Ctrl+C on its console, or the port
                // was closed and reopened) instead of answering.
                dropped = true
                throw RpcError("the device left link mode before answering; reconnect")
            }
            keep?.add(f)
        }
    }

    private fun begin(kind: String) {
        if (dropped) throw RpcError("link mode has ended; reconnect")
        busy = kind
    }

    /** Run fn; if it is interrupted with a request still in flight, put the device back to
     *  waiting for a request before passing the error on. */
    private fun <T> guard(fn: () -> T): T {
        try {
            return fn()
        } catch (e: Throwable) {
            val kind = busy
            if (kind != null) {
                busy = null
                try { abort(kind) } catch (_: Throwable) { }
            }
            throw e
        }
    }

    /** Send one request; return (final frame, intermediate frames). ERR is thrown as LinkError. */
    fun request(line: String, timeoutMs: Long? = null): Pair<Frame, List<Frame>> {
        val data = encode(line)
        return guard {
            begin("req")
            t.write(data)
            val keep = ArrayList<Frame>()
            val f = final(timeoutMs ?: timing.request, keep)
            busy = null
            if (f.type == "ERR") throw f.error()
            f to keep
        }
    }

    // --- recovery -------------------------------------------------------------

    /** Stop whatever [kind] of request is in flight and resynchronise. */
    fun abort(kind: String) {
        when (kind) {
            "put", "get" -> t.write("CANCEL\n".toByteArray())
            "exec" -> t.write(byteArrayOf(0x03))
        }
        try { final(timing.abort) } catch (_: DeviceTimeout) { }
        resync()
    }

    /** Make sure the next frame read answers the next request sent: HELLO is answered by a HELLO
     *  frame and then OK, so anything stale is read and dropped before it. No HELLO: link mode ended. */
    fun resync(timeoutMs: Long? = null): Boolean {
        val end = System.nanoTime() + (timeoutMs ?: (timing.bye * 2)) * 1_000_000
        t.write("HELLO\n".toByteArray())
        var seen = false
        while (true) {
            val left = (end - System.nanoTime()) / 1_000_000
            val f = if (left > 0) r.nextFrame(left) else null
            if (f == null) { dropped = true; return false }
            if (f.type == "HELLO") { setHello(f); seen = true }
            else if (seen && f.isFinal) return true
        }
    }

    // --- requests -------------------------------------------------------------

    fun ping() { request("PING") }

    /** AUTH (protocol 2): prove the signed-in account's password, so commands that need sudo
     *  can run for the next few minutes. Returns the seconds it counts for. Wrong: LinkError "auth". */
    fun auth(password: String): Int {
        val (f, _) = request("AUTH " + Base64.getEncoder().encodeToString(password.toByteArray()))
        return f.kv()["grace"]?.toIntOrNull() ?: 0
    }

    /** The firmware has the bridge and no network of its own (HELLO bridge=1). */
    val canBridge: Boolean get() = proto >= 3 && hello["bridge"] == "1"

    fun bridgeOn(): Boolean {
        if (!canBridge) return false
        val (f, _) = request("BRIDGE on")
        bridged = f.kv()["bridge"] == "on"
        return bridged
    }

    fun bridgeOff() {
        if (!bridged || dropped) return
        try { request("BRIDGE off") } catch (_: RpcError) { }
        bridged = false
    }

    fun helloRequest(): Map<String, String> {
        val (_, keep) = request("HELLO")
        for (k in keep) if (k.type == "HELLO") return setHello(k)
        throw ProtocolError("HELLO was answered without a HELLO frame")
    }

    fun ls(path: String = "."): List<Entry> {
        val (_, keep) = request("LS " + path.ifEmpty { "." })
        return keep.filter { it.type == "E" }.map { k ->
            val parts = k.rest.split(" ", limit = 3)
            if (parts.size != 3) throw ProtocolError("malformed entry: $k")
            Entry(parts[0], parts[1].toLongOrNull() ?: throw ProtocolError("malformed entry: $k"), parts[2])
        }
    }

    /** (type, size), or null when the path does not exist. */
    fun stat(path: String): Pair<String, Long>? {
        val f = try { request("STAT $path").first } catch (e: LinkError) {
            if (e.code == "nofile") return null else throw e
        }
        val kv = f.kv()
        return (kv["type"] ?: "?") to (kv["size"]?.toLongOrNull() ?: 0L)
    }

    /** Run a shell line. On [timeoutMs] a Ctrl+C is sent and the result comes back timed out. */
    fun exec(line: String, timeoutMs: Long? = null): ExecResult {
        val data = encode("EXEC $line")
        return guard {
            begin("exec")
            t.write(data)
            val out = java.io.ByteArrayOutputStream()
            var stopped = false
            var deadline: Long? = timeoutMs?.let { System.nanoTime() + it * 1_000_000 }
            var due = false
            val dueFn = {
                // the timeout running out in the middle of a fetch: a Ctrl+C, once
                val d = deadline
                if (d == null || due || System.nanoTime() < d) false else { due = true; true }
            }
            while (true) {
                val wait = deadline?.let { maxOf(0L, (it - System.nanoTime()) / 1_000_000) }
                val f = next(wait, dueFn)
                if (due && !stopped) {
                    stopped = true
                    deadline = System.nanoTime() + timing.abort * 1_000_000
                }
                if (f == null) {
                    if (!stopped) {
                        t.write(byteArrayOf(0x03))
                        stopped = true
                        deadline = System.nanoTime() + timing.abort * 1_000_000
                        continue
                    }
                    throw DeviceTimeout("the command did not stop after Ctrl+C")
                }
                when (f.type) {
                    "OUT" -> out.write(b64(f.rest))
                    "OK" -> {
                        busy = null
                        val kv = f.kv()
                        return@guard ExecResult(kv["rc"]?.toIntOrNull() ?: 1, out.toByteArray(),
                                                kv["truncated"] == "1", stopped)
                    }
                    "ERR" -> { busy = null; throw f.error() }
                }
            }
            @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
        }
    }

    /** Write [data] to [path]. Returns the digest the device confirmed. */
    fun put(data: ByteArray, path: String, progress: ((Long, Long) -> Unit)? = null): String {
        val digest = sha256Hex(data)
        val req = encode("PUT ${data.size} $digest $path")
        return guard {
            begin("put")
            t.write(req)
            var f = frame(timing.transfer)
            while (!(f.type == "READY" || f.isFinal)) f = frame(timing.transfer)
            if (f.type == "ERR") { busy = null; throw f.error() }
            if (f.type == "OK") throw ProtocolError("PUT was answered OK before READY")
            val kv = f.kv()
            val chunk = kv["chunk"]?.toIntOrNull()?.coerceIn(1, this.chunk) ?: this.chunk
            // How many D lines may be unacknowledged (READY window=, LINK.md); firmware
            // that does not say gets one at a time.
            val window = kv["window"]?.toIntOrNull()?.coerceIn(1, PUT_WINDOW_MAX) ?: 1
            var sent = 0
            var ackedUpto = 0
            val pending = ArrayDeque<Int>()
            progress?.invoke(0, data.size.toLong())
            while (ackedUpto < data.size) {
                while (sent < data.size && pending.size < window) {
                    val end = minOf(sent + chunk, data.size)
                    val piece = data.copyOfRange(sent, end)
                    t.write("D ".toByteArray() + Base64.getEncoder().encode(piece) + "\n".toByteArray())
                    sent = end
                    pending.addLast(sent)
                }
                while (true) {
                    f = frame(timing.transfer)
                    if (f.type == "ACK") break
                    if (f.type == "ERR") { busy = null; throw f.error() }
                    if (f.type == "OK") throw ProtocolError("PUT ended early, after $ackedUpto bytes")
                }
                val acked = f.rest.split(" ")[0].toIntOrNull() ?: throw ProtocolError("malformed ACK: $f")
                if (acked != pending.first()) throw ProtocolError("the device acknowledged $acked bytes, ${pending.first()} were sent")
                pending.removeFirst()
                ackedUpto = acked
                progress?.invoke(ackedUpto.toLong(), data.size.toLong())
            }
            f = final(timing.transfer)
            busy = null
            if (f.type == "ERR") throw f.error()
            val got = (f.kv()["sha"] ?: "").lowercase()
            if (got != digest) throw DigestMismatch("the device reports sha256 ${got.ifEmpty { "(none)" }}, expected $digest")
            digest
        }
    }

    /** Read a file. Returns its bytes, checked against the device's digest. */
    fun get(path: String, progress: ((Long, Long) -> Unit)? = null): ByteArray {
        val req = encode("GET $path")
        return guard {
            begin("get")
            t.write(req)
            var f = frame(timing.transfer)
            while (!(f.type == "SIZE" || f.isFinal)) f = frame(timing.transfer)
            if (f.type == "ERR") { busy = null; throw f.error() }
            if (f.type == "OK") throw ProtocolError("GET was answered OK before SIZE")
            val size = f.rest.split(" ")[0].toLongOrNull() ?: throw ProtocolError("malformed SIZE: $f")
            val data = java.io.ByteArrayOutputStream()
            progress?.invoke(0, size)
            while (true) {
                f = frame(timing.transfer)
                when (f.type) {
                    "D" -> {
                        data.write(b64(f.rest))
                        progress?.invoke(data.size().toLong(), size)
                        t.write("A\n".toByteArray())
                    }
                    "ERR" -> { busy = null; throw f.error() }
                    "OK" -> { busy = null; break }
                }
            }
            val bytes = data.toByteArray()
            val want = (f.kv()["sha"] ?: "").lowercase()
            if (bytes.size.toLong() != size) throw DigestMismatch("$path: ${bytes.size} bytes arrived, the device said $size")
            val have = sha256Hex(bytes)
            if (want != have) throw DigestMismatch("$path: the device sent sha256 ${want.ifEmpty { "(none)" }}, what arrived is $have")
            bytes
        }
    }

    /** Leave link mode. True when the device said OK. */
    fun bye(): Boolean {
        if (dropped) return false
        return try {
            t.write("BYE\n".toByteArray())
            val end = System.nanoTime() + timing.bye * 1_000_000
            while (true) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                val f = r.nextFrame(left) ?: return false
                if (f.isFinal) { dropped = true; return f.type == "OK" }
            }
            @Suppress("UNREACHABLE_CODE") false
        } catch (_: RpcError) { false }
    }

    companion object {
        /** The request as bytes, refused here if the device could not take it. */
        fun encode(line: String): ByteArray {
            val data = line.toByteArray(Charsets.UTF_8)
            if (data.any { it == '\n'.code.toByte() || it == '\r'.code.toByte() })
                throw RpcError("a request cannot contain a line break")
            if (data.size + 1 > LINE_MAX)
                throw RpcError("the request is ${data.size + 1} bytes; the device takes at most $LINE_MAX")
            return data + '\n'.code.toByte()
        }
    }
}

internal fun b64(text: String): ByteArray = try {
    Base64.getDecoder().decode(text.trim())
} catch (e: IllegalArgumentException) {
    throw ProtocolError("bad base64 from the device")
}

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
