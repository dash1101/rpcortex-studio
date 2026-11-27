package app.novalabs.rpcstudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Studio updating itself from its GitHub releases, the way Orbit installs it: the latest release's
 * rpcortex-studio-android.apk, checked against the release's SHA256SUMS and against this app (same
 * package, a newer version), then handed to Android's installer, which asks before it installs.
 * Android also refuses an APK signed with another key, so a release can only come from the same
 * hands as the app already here.
 */
object SelfUpdate {
    const val REPO = "dash1101/rpcortex-studio"
    const val APK = "rpcortex-studio-android.apk"
    const val SUMS = "SHA256SUMS"

    enum class State { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }
    data class Release(val tag: String, val version: String, val apkUrl: String, val apkSize: Long, val sumsUrl: String,
                       val notes: String)

    var state by mutableStateOf(State.IDLE); private set
    var release by mutableStateOf<Release?>(null); private set
    var progress by mutableStateOf(0f); private set
    var problem by mutableStateOf(""); private set
    /** When it was last looked at, for "checked 3 minutes ago". */
    var checkedAt by mutableStateOf(0L); private set

    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "self-update") }
    private fun post(fn: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(fn)

    fun installedVersion(ctx: Context): String = info(ctx)?.versionName ?: "?"
    private fun info(ctx: Context): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    /** "1.4.0" > "1.3.10"? Numbers compared part by part; anything after a '-' is ignored. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trimStart('v').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun check(ctx: Context, quiet: Boolean = false) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING) return
        state = State.CHECKING; problem = ""
        val have = installedVersion(ctx)
        work.execute {
            try {
                val j = JSONObject(Session.fetchText("https://api.github.com/repos/$REPO/releases/latest"))
                val assets = j.getJSONArray("assets")
                var apk: JSONObject? = null; var sums: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    when (a.optString("name")) { APK -> apk = a; SUMS -> sums = a }
                }
                val tag = j.optString("tag_name")
                if (apk == null || sums == null) throw Exception("the $tag release has no Android app")
                val r = Release(tag, tag.trimStart('v'), apk!!.getString("browser_download_url"), apk!!.optLong("size"),
                                sums!!.getString("browser_download_url"), j.optString("body"))
                post {
                    release = r; checkedAt = System.currentTimeMillis()
                    state = if (newer(r.version, have)) State.AVAILABLE else State.CURRENT
                }
            } catch (e: Exception) {
                post {
                    checkedAt = System.currentTimeMillis()
                    if (quiet) state = State.IDLE
                    else fail("GitHub could not be reached (${e.message ?: e.javaClass.simpleName}).")
                }
            }
        }
    }

    private fun fail(why: String) { problem = why; state = State.FAILED }

    /** Can this app ask Android to install? Android 8+ asks the person once, in its own settings. */
    fun mayInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSetting(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context) {
        val r = release ?: return
        if (state == State.DOWNLOADING || state == State.INSTALLING) return
        state = State.DOWNLOADING; progress = 0f; problem = ""
        val app = ctx.applicationContext
        work.execute {
            val file = File(app.cacheDir, "update.apk")
            try {
                val sums = parseSums(Session.fetchText(r.sumsUrl))
                val want = sums[APK] ?: throw Exception("The release lists no checksum for its app, so it was not installed.")
                val got = download(r.apkUrl, file, r.apkSize)
                if (got != want) throw Exception("The download does not match the release's checksum, so it was not installed. Try again.")
                val pm = app.packageManager
                val archived = if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
                               else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, 0)
                if (archived == null || archived.packageName != app.packageName)
                    throw Exception("The download is not RPC Studio's app, so it was not installed.")
                val here = info(app)
                if (here != null && code(archived) <= code(here)) {
                    post { state = State.CURRENT }
                    return@execute
                }
                post { state = State.INSTALLING }
                commit(app, file)
            } catch (e: Exception) {
                file.delete()
                post { fail(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun download(url: String, to: File, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        var u = URL(url)
        // GitHub sends release files from another host: follow up to a few redirects (https only).
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "rpcortex-studio-android")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = URL(u, c.getHeaderField("Location") ?: throw Exception("a redirect with nowhere to go"))
                    if (next.protocol != "https") throw Exception("refused a redirect off https")
                    u = next
                    return@repeat
                }
                if (code != 200) throw Exception("HTTP $code")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else size
                var done = 0L; var shown = 0L
                c.inputStream.use { inp ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0 && done - shown > 128 * 1024) { shown = done; val f = done.toFloat() / total; post { progress = f } }
                        }
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            } finally { c.disconnect() }
        }
        throw Exception("too many redirects")
    }

    /** "<sha256>  <name>" lines -> name to sha256. */
    fun parseSums(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].length == 64 && parts[0].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                out[parts[1].trim().trimStart('*')] = parts[0].lowercase()
        }
        return out
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /** What Android's installer said (on success this app is replaced and restarts, so that one is rarely seen). */
    internal fun result(ctx: Context, status: Int, message: String?) {
        File(ctx.cacheDir, "update.apk").delete()
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> state = State.CURRENT
            PackageInstaller.STATUS_FAILURE_ABORTED -> { state = State.AVAILABLE; problem = "The update was cancelled; nothing changed." }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                fail("Android would not install it over this copy: they are signed with different keys. Remove the app, then install it again.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail("There is not enough space on this phone.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail("This phone blocks installing apps from RPC Studio.")
            else -> fail("Android could not install the update" + (message?.let { ": $it" } ?: "."))
        }
    }
}

/** Android's installer reports here; when it needs the person to confirm, its screen is shown. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                   else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (_: RuntimeException) { }
            }
            return
        }
        SelfUpdate.result(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
