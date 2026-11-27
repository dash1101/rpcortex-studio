package app.novalabs.rpcstudio

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import app.novalabs.rpcstudio.link.Device
import app.novalabs.rpcstudio.link.DeviceGone
import app.novalabs.rpcstudio.link.Entry
import app.novalabs.rpcstudio.link.Parse
import app.novalabs.rpcstudio.link.RpcError
import app.novalabs.rpcstudio.link.quote
import app.novalabs.rpcstudio.link.rnorm
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * One connection to one board, and everything the screens show about it.
 *
 * Every call to the device runs on one worker thread, in order, as in the desktop app: the link
 * protocol is one request at a time. The screens only read this state and ask for jobs.
 */
class Session(app: Application) : AndroidViewModel(app) {
    enum class Conn { OFF, CONNECTING, ON }
    enum class Kind { INFO, OK, WARN, ERR }
    class SudoAsk(val who: String, val attempt: Int, val answer: CompletableFuture<String?>)
    data class RepoPkg(val name: String, val ver: String, val desc: String, val arch: String,
                       val size: Long, val sha256: String, val url: String)
    data class Release(val ver: String, val name: String, val size: Long, val url: String)

    private val usb = app.getSystemService(UsbManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "device") }
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "network") }
    private val prefs = app.getSharedPreferences("studio", 0)
    private var dev: Device? = null

    var conn by mutableStateOf(Conn.OFF); private set
    var hello by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var status by mutableStateOf<Parse.Status?>(null); private set
    var name by mutableStateOf(""); private set
    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set
    var message by mutableStateOf<Pair<String, Kind>?>(null); private set
    var sudo by mutableStateOf<SudoAsk?>(null); private set
    var theme by mutableStateOf(prefs.getString("theme", "tui") ?: "tui"); private set
    /** Settings: keep the phone's screen on while a board is connected; look for a new Studio at start. */
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keep_screen_on", true)); private set
    var checkAtStart by mutableStateOf(prefs.getBoolean("check_at_start", true)); private set
    var boards by mutableStateOf<List<UsbDevice>>(emptyList()); private set

    // Files
    var cwd by mutableStateOf("/"); private set
    val entries = mutableStateListOf<Entry>()
    // Run
    val runLog = mutableStateListOf<String>()
    // Packages
    var installed by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var repo by mutableStateOf<List<RepoPkg>>(emptyList()); private set
    // Firmware
    var latest by mutableStateOf<Release?>(null); private set

    fun say(text: String, kind: Kind = Kind.INFO) { message = text to kind }
    fun chooseTheme(name: String) { theme = name; prefs.edit().putString("theme", name).apply() }
    fun chooseKeepScreenOn(on: Boolean) { keepScreenOn = on; prefs.edit().putBoolean("keep_screen_on", on).apply() }
    fun chooseCheckAtStart(on: Boolean) { checkAtStart = on; prefs.edit().putBoolean("check_at_start", on).apply() }

    fun scan() { boards = UsbTransport.boards(usb) }

    // --- connecting ---------------------------------------------------------------------

    fun connect(device: UsbDevice) = open(device.productName ?: "the board") { UsbTransport(usb, device) }

    /** Debug builds only: a console relayed over TCP (tools/serial_relay.py), for the emulator. */
    fun connectTcp(host: String, port: Int) = open("$host:$port") { app.novalabs.rpcstudio.link.TcpTransport(host, port) }

    private fun open(what: String, make: () -> app.novalabs.rpcstudio.link.Transport) {
        if (conn != Conn.OFF) return
        conn = Conn.CONNECTING
        say("Connecting to $what...")
        worker.execute {
            try {
                val t = make()
                val d = Device(t, sudoPassword = { askSudo() })
                d.connect()
                dev = d
                post {
                    hello = d.hello
                    cwd = d.cwd
                    conn = Conn.ON
                    say("Connected: ${d.hello["os"] ?: "?"} on ${d.hello["board"] ?: "?"}.", Kind.OK)
                }
                refreshAll()
            } catch (e: Exception) {
                dev = null
                post { conn = Conn.OFF; say(e.message ?: e.toString(), Kind.ERR) }
            }
        }
    }

    fun disconnect() {
        worker.execute {
            try { dev?.close() } catch (_: Exception) { }
            dev = null
            post { conn = Conn.OFF; status = null; say("Disconnected. The board is back at its own prompt.") }
        }
    }

    /** The board went away under a request: unplugged, or restarting. */
    private fun lost(why: String) {
        try { dev?.t?.close() } catch (_: Exception) { }
        dev = null
        post { conn = Conn.OFF; status = null; say(why, Kind.WARN); scan() }
    }

    private fun askSudo(): String? {
        val d = dev
        val ask = SudoAsk(d?.hello?.get("user") ?: "this account", d?.sudoAttempt ?: 0, CompletableFuture())
        post { sudo = ask }
        return try { ask.answer.get() } finally { post { sudo = null } }
    }

    fun answerSudo(pw: String?) { sudo?.answer?.complete(pw) }

    // --- running jobs ---------------------------------------------------------------------

    private fun post(fn: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(fn)

    /** Run [fn] against the device on the worker, with [label] shown while it runs. */
    private fun job(label: String, fn: (Device) -> Unit) {
        val d = dev ?: run { say("Not connected.", Kind.WARN); return }
        post { busy = label; progress = null }
        worker.execute {
            try {
                fn(d)
            } catch (e: DeviceGone) {
                lost("The board went away (unplugged, or restarting). Plug it back in to reconnect.")
            } catch (e: RpcError) {
                post { say(e.message ?: "failed", Kind.ERR) }
            } catch (e: Exception) {
                post { say(e.toString(), Kind.ERR) }
            } finally {
                post { busy = null; progress = null }
            }
        }
    }

    private fun prog(done: Long, total: Long) {
        val f = if (total > 0) done.toFloat() / total else null
        post { progress = f }
    }

    fun refreshAll() {
        job("reading the device's name") { d ->
            val n = Parse.sysinfo(d.exec("sysinfo").text)["device_id"] ?: ""
            post { name = n }
        }
        refreshStatus(); listDir(cwd); refreshPackages()
    }

    fun refreshStatus() = job("reading the status") { d ->
        val s = Parse.status(d.exec(Parse.STATUS_LINE).text)
        post { status = s }
    }

    // --- run ------------------------------------------------------------------------------

    fun run(line: String) {
        if (line.isBlank()) return
        post { runLog.add("> $line") }
        job("running $line") { d ->
            val r = d.exec(line, timeoutMs = 120_000)
            val text = Parse.clean(r.text).trimEnd()
            post {
                if (text.isNotEmpty()) runLog.add(text)
                runLog.add(if (r.timedOut) "[stopped after 2 minutes]" else "[rc ${r.rc}]")
                while (runLog.size > 400) runLog.removeAt(0)
            }
        }
    }

    // --- files ----------------------------------------------------------------------------

    fun listDir(path: String) = job("listing $path") { d ->
        val p = rnorm(path, d.cwd)
        val list = d.ls(p).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        post { cwd = p; entries.clear(); entries.addAll(list) }
    }

    fun up() { if (cwd != "/") listDir(cwd.substringBeforeLast('/').ifEmpty { "/" }) }

    fun open(e: Entry) { if (e.isDir) listDir(join(cwd, e.name)) }

    private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    fun upload(uri: Uri, name: String) = job("sending $name") { d ->
        val data = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RpcError("Could not read $name.")
        val dest = join(cwd, name)
        d.put(data, dest) { a, b -> prog(a, b) }
        post { say("Sent $name (${human(data.size.toLong())}), sha256 verified.", Kind.OK) }
        val list = d.ls(cwd).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        post { entries.clear(); entries.addAll(list) }
    }

    fun download(e: Entry, uri: Uri) = job("fetching ${e.name}") { d ->
        val data = d.get(join(cwd, e.name)) { a, b -> prog(a, b) }
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw RpcError("Could not write the copy.")
        post { say("Saved ${e.name} (${human(data.size.toLong())}), sha256 verified.", Kind.OK) }
    }

    fun delete(e: Entry) = job("deleting ${e.name}") { d ->
        d.rm(join(cwd, e.name), recursive = e.isDir)
        val list = d.ls(cwd).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        post { entries.clear(); entries.addAll(list); say("Deleted ${e.name}.", Kind.OK) }
    }

    fun mkdir(name: String) = job("making $name") { d ->
        d.mkdir(join(cwd, name))
        val list = d.ls(cwd).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        post { entries.clear(); entries.addAll(list) }
    }

    // --- packages -------------------------------------------------------------------------

    private val arch: String get() = if ((hello["board"] ?: "").startsWith("pico2")) "armv8m" else "armv6m"

    fun refreshPackages() {
        job("reading the packages") { d ->
            val got = Parse.pkgs(d.exec("_pkgs").text) ?: Parse.pkgList(d.exec("pkg list").text)
            post { installed = got }
        }
        net.execute {
            try {
                val j = JSONObject(fetchText(REPO_INDEX))
                val arr = j.getJSONArray("packages")
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    RepoPkg(it.optString("name"), it.optString("ver"), it.optString("desc"), it.optString("arch"),
                            it.optLong("size"), it.optString("sha256"), it.optString("url"))
                }
                post { repo = list }
            } catch (e: Exception) {
                post { say("The package list could not be read: ${e.message}", Kind.WARN) }
            }
        }
    }

    /** Packages this board can run: its own architecture, and armv6m (it runs on both chips). */
    fun runsHere(p: RepoPkg) = p.arch.isEmpty() || p.arch == "armv6m" || p.arch == arch

    fun install(name: String) = job("installing $name") { d ->
        val r = d.exec(d.sudoLine("pkg install " + quote(name)), timeoutMs = 300_000)
        post { say(lastLine(r.text, "installed $name"), if (r.rc == 0) Kind.OK else Kind.ERR) }
        val got = Parse.pkgs(d.exec("_pkgs").text) ?: Parse.pkgList(d.exec("pkg list").text)
        post { installed = got }
    }

    fun remove(name: String) = job("removing $name") { d ->
        val r = d.exec(d.sudoLine("pkg remove " + quote(name)))
        post { say(lastLine(r.text, "removed $name"), if (r.rc == 0) Kind.OK else Kind.ERR) }
        val got = Parse.pkgs(d.exec("_pkgs").text) ?: Parse.pkgList(d.exec("pkg list").text)
        post { installed = got }
    }

    // --- firmware -------------------------------------------------------------------------

    fun checkFirmware() {
        net.execute {
            try {
                val j = JSONObject(fetchText(LATEST))
                val board = hello["board"] ?: "pico2_w"
                val arr = j.getJSONArray("packages")
                val e = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("name") == board }
                    ?: throw RpcError("No release is listed for $board.")
                val desc = e.optString("desc")
                val name = Regex("\\bv\\d+\\.\\d+(?:\\.\\d+)?(?:-[a-z0-9]+)?").find(desc)?.value ?: "v" + e.optString("ver")
                post { latest = Release(e.optString("ver"), name, e.optLong("size"), e.optString("url")) }
            } catch (e: Exception) {
                post { say("The latest release could not be read: ${e.message}", Kind.WARN) }
            }
        }
    }

    /** The running version in the form the update channel compares ("2.0.676"). */
    val runningComparable: String get() {
        val os = hello["os"] ?: return ""
        val m = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(os) ?: return ""
        val (a, b, c) = m.destructured
        return if (c.isEmpty()) "$a.$b.${hello["build"] ?: "0"}" else "$a.$b.$c"
    }

    /** The board downloads, checks and writes the release itself (it needs Wi-Fi). */
    fun updateOverTheAir(force: Boolean) = job("updating over the air") { d ->
        d.exec(d.sudoLine("update online " + (if (force) "--force " else "") + "--yes"), timeoutMs = 600_000)
        post { say("The board says it is done; if it restarted, plug it back in.", Kind.OK) }
    }

    fun reboot() = job("restarting") { d ->
        val r = d.reboot()
        if (r == null) lost("The board is restarting.") else post { say(r.text.trim(), Kind.ERR) }
    }

    override fun onCleared() {
        try { dev?.close() } catch (_: Exception) { }
        worker.shutdownNow(); net.shutdownNow()
    }

    companion object {
        const val REPO_INDEX = "https://raw.githubusercontent.com/dash1101/RPCortex-repo/main/repo-v2/index.json"
        const val LATEST = "https://raw.githubusercontent.com/dash1101/RPCortex/main/releases/latest.json"

        fun fetchText(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "rpcortex-studio-android")
            try {
                if (c.responseCode != 200) throw RpcError("HTTP ${c.responseCode}")
                return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            } finally { c.disconnect() }
        }

        fun human(n: Long): String = when {
            n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
            n >= 1024 -> "%.1f KB".format(n / 1024.0)
            else -> "$n B"
        }

        fun lastLine(text: String, fallback: String): String =
            Parse.clean(text).trim().lines().lastOrNull { it.isNotBlank() }?.trim()?.trimStart('[', '@', ']', '!', ':', ' ')
                ?.removePrefix("] ")?.ifEmpty { fallback } ?: fallback

    }
}
