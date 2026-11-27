package app.novalabs.rpcstudio.link

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session as JSession
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * This app's own SSH key: ECDSA P-256 (what every RPCortex board accepts), made once and kept by
 * the app. [private] is the PEM text; [public] is the authorized_keys line.
 */
class SshKey(val private: ByteArray, val public: String) {
    companion object {
        fun generate(comment: String): SshKey {
            val jsch = JSch()
            val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
            val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8).trim()
            kp.dispose()
            return SshKey(prv, pub)
        }
    }
}

/** OpenSSH's form of a key's fingerprint: "SHA256:" and the unpadded base64 of the key's SHA-256. */
fun sshFingerprint(keyBlob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob))

/** The one host key this board is known by: pinned at pairing, over the USB cable. */
private class PinnedHost(private val fingerprint: String) : HostKeyRepository {
    var seen: String? = null; private set
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        seen = sshFingerprint(key)
        return if (seen == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/**
 * A board's link mode over SSH (Wi-Fi): `link` run as the session's command, on a board whose
 * firmware allows it (v2.0.700 and later). Signs in with [key] only, and only to the board whose
 * host key has [fingerprint]: anything else on that address is refused before a byte of the link
 * goes to it. Use with [Device.connectLinked], never [Device.connect] (its Ctrl+C would end link
 * mode here).
 */
class SshTransport(
    host: String, port: Int, user: String, key: SshKey, fingerprint: String,
    connectTimeoutMs: Int = 8000,
) : Transport {
    override val name = "$user@$host"
    private val session: JSession
    private val chan: ChannelExec
    private val inp: InputStream
    private val out: OutputStream

    init {
        val jsch = JSch()
        jsch.addIdentity("rpcortex-studio", key.private, null, null)
        val pin = PinnedHost(fingerprint)
        jsch.hostKeyRepository = pin
        session = jsch.getSession(user, host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        // What the board has: its host key is ECDSA P-256 only.
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256")
        try {
            session.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            val m = e.message ?: ""
            throw when {
                "HostKey has been changed" in m || pin.seen != null && pin.seen != fingerprint -> RpcError(
                    "The board at $host is not the one paired: its SSH key is ${pin.seen ?: "different"}. If it was " +
                    "reset or its key renewed, pair it again over USB.")
                "Auth fail" in m || "Auth cancel" in m -> RpcError(
                    "The board turned this phone's key away. Pair it again over USB (the key may have been " +
                    "removed from ~/.ssh/authorized_keys).")
                "timeout" in m.lowercase() || e.cause is java.net.SocketTimeoutException -> RpcError(
                    "Nothing answered at $host:$port. Is the board on the same Wi-Fi, with its SSH server on?")
                e.cause is java.net.ConnectException -> RpcError(
                    "$host refused the connection on port $port: the board's SSH server may be off ('ssh server on').")
                e.cause is java.net.UnknownHostException -> RpcError("$host could not be found on this network.")
                else -> RpcError("SSH to $host failed: $m")
            }
        }
        chan = session.openChannel("exec") as ChannelExec
        chan.setCommand("link")
        inp = chan.inputStream
        out = chan.outputStream
        try {
            chan.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            session.disconnect()
            throw RpcError("The board would not start a session: ${e.message} (it serves two at a time).")
        }
    }

    override fun read(timeoutMs: Long): ByteArray {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val n = inp.available()
            if (n > 0) {
                val b = ByteArray(n)
                val got = inp.read(b, 0, n)
                if (got < 0) throw DeviceGone()
                return b.copyOf(got)
            }
            if (chan.isClosed || !session.isConnected) throw DeviceGone()
            if (System.nanoTime() >= end) return ByteArray(0)
            Thread.sleep(2)
        }
    }

    override fun write(data: ByteArray) {
        if (chan.isClosed || !session.isConnected) throw DeviceGone()
        try { out.write(data); out.flush() } catch (e: java.io.IOException) { throw DeviceGone() }
    }

    override fun close() {
        try { chan.disconnect() } catch (_: Exception) { }
        try { session.disconnect() } catch (_: Exception) { }
    }
}

/** A board paired for Wi-Fi: where it is, who signs in, and the host key it must present. */
data class Paired(val name: String, val host: String, val port: Int, val user: String, val fingerprint: String,
                  val board: String = "")

object Pairing {
    /**
     * Pair over a USB link: put [key]'s public half in the linked account's authorized_keys
     * (once), and read the board's SSH host key fingerprint, address and port. The SSH server is
     * switched on if it is off (that needs sudo, which [d] asks for).
     */
    fun pair(d: Device, key: SshKey): Paired {
        val user = d.hello["user"] ?: throw RpcError("The board did not say which account is signed in.")
        val wifi = d.exec("wifi status").text
        val host = Regex("Address:\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(wifi)?.groupValues?.get(1)
            ?: throw RpcError("The board is not on Wi-Fi. Join a network on it first ('wifi connect'), then pair.")

        var status = d.exec("ssh server status").text
        if (!Regex("Server on").containsMatchIn(status)) {
            d.check(d.sudoLine("ssh server on"))
            status = d.exec("ssh server status").text
            if (!Regex("Server on").containsMatchIn(status)) throw RpcError("The board's SSH server did not start: ${status.trim()}")
        }
        val port = Regex("port (\\d+)").find(status)?.groupValues?.get(1)?.toInt() ?: 22
        val fp = Regex("SHA256:[A-Za-z0-9+/]+").find(d.exec("ssh server fingerprint").text)?.value
            ?: throw RpcError("The board did not give its SSH key's fingerprint.")

        val dir = "/home/$user/.ssh"
        val file = "$dir/authorized_keys"
        if (d.stat(dir) == null) d.mkdir(dir, parents = true)
        val have = if (d.stat(file) != null) String(d.get(file), Charsets.UTF_8) else ""
        val blob = key.public.split(Regex("\\s+")).getOrNull(1) ?: throw RpcError("this app's key is malformed")
        if (have.lines().none { it.split(Regex("\\s+")).getOrNull(1) == blob }) {
            val text = (if (have.isEmpty() || have.endsWith("\n")) have else have + "\n") + key.public + "\n"
            d.put(text.toByteArray(Charsets.UTF_8), file)
        }
        val name = Parse.sysinfo(d.exec("sysinfo").text)["device_id"] ?: d.hello["board"] ?: host
        return Paired(name, host, port, user, fp, d.hello["board"] ?: "")
    }

    /** Take [key] out of the account's authorized_keys again (over any link to the board). */
    fun unpair(d: Device, key: SshKey) {
        val user = d.hello["user"] ?: return
        val file = "/home/$user/.ssh/authorized_keys"
        if (d.stat(file) == null) return
        val blob = key.public.split(Regex("\\s+")).getOrNull(1) ?: return
        val have = String(d.get(file), Charsets.UTF_8)
        val kept = have.lines().filter { it.isNotBlank() && it.split(Regex("\\s+")).getOrNull(1) != blob }
        if (kept.size != have.lines().count { it.isNotBlank() }) d.put((kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n").toByteArray(), file)
    }
}
