package app.novalabs.rpcstudio

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.novalabs.rpcstudio.link.Entry
import app.novalabs.rpcstudio.link.Parse

// --- small pieces ----------------------------------------------------------------------------

@Composable private fun Kv(k: String, v: String, vColor: Color = LocalPalette.current.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Txt(k, Modifier.width(96.dp), color = LocalPalette.current.muted, size = 13.sp)
        Txt(v, color = vColor, size = 13.sp)
    }
}

@Composable private fun Bar(frac: Float, label: String) {
    val p = LocalPalette.current
    Column {
        // [██████░░░░] in the theme's colours, as a bar of blocks would draw at a terminal
        Box(Modifier.fillMaxWidth().height(8.dp).border(1.dp, p.line)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).background(p.accent))
        }
        Txt(label, Modifier.padding(top = 3.dp), color = p.muted, size = 12.sp)
    }
}

@Composable private fun Note(text: String) = Txt(text, color = LocalPalette.current.muted, size = 12.5.sp)

@Composable private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false,
                              onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.border(1.dp, p.lineHi).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Txt(hint, color = p.off)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(p.accent),
            textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = Mono),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
                                              imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }), modifier = Modifier.fillMaxWidth())
    }
}

/** A link out: the phone's browser opens it. */
@Composable private fun LinkOut(label: String, url: String) {
    val ctx = LocalContext.current
    Txt("↗ $label", Modifier.clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }.padding(vertical = 5.dp), color = LocalPalette.current.accent)
}

// --- the app ----------------------------------------------------------------------------------

private enum class Tab(val label: String) { DEVICE("device"), FILES("files"), PACKAGES("pkgs"), FIRMWARE("firmware"), RUN("run") }

@Composable
fun StudioApp(s: Session, onConnect: (android.hardware.usb.UsbDevice) -> Unit) {
    val palette = if (s.theme == Themes.DEVICE) deviceTheme() ?: Themes.get("tui") else Themes.get(s.theme)
    CompositionLocalProvider(LocalPalette provides palette) {
        val p = LocalPalette.current
        var settings by rememberSaveable { mutableStateOf(false) }
        var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
        BackHandler(enabled = settings) { settings = false }

        // The screen stays on while a board is connected, when that is wanted (Settings).
        val view = LocalView.current
        val keepOn = s.keepScreenOn && s.conn == Session.Conn.ON
        DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

        Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)) {
            TopBar(s, settings) { settings = !settings }
            if (settings) {
                SettingsScreen(s, Modifier.weight(1f))
            } else {
                Busy(s)
                if (s.conn != Session.Conn.ON) {
                    ConnectScreen(s, onConnect, Modifier.weight(1f)) { settings = true }
                } else {
                    Tabs(tab) { tab = it }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            Tab.DEVICE -> DeviceScreen(s)
                            Tab.FILES -> FilesScreen(s)
                            Tab.PACKAGES -> PackagesScreen(s)
                            Tab.FIRMWARE -> FirmwareScreen(s)
                            Tab.RUN -> RunScreen(s)
                        }
                    }
                }
                StatusLine(s)
            }
        }
        s.sudo?.let { SudoDialog(s, it) }
    }
}

@Composable private fun TopBar(s: Session, settings: Boolean, toggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("[", bold = true); Txt("rpc", color = p.accent, bold = true); Txt("]", bold = true)
        Spacer(Modifier.width(10.dp))
        val h = s.hello
        val what = when {
            settings -> "── settings"
            s.conn == Session.Conn.ON -> "── " + listOf(s.name, h["board"] ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
            s.conn == Session.Conn.CONNECTING -> "── connecting"
            else -> "── studio for android"
        }
        Txt(what, Modifier.weight(1f), color = p.muted, maxLines = 1)
        if (!settings && SelfUpdate.state == SelfUpdate.State.AVAILABLE) {
            Square(p.accent, modifier = Modifier.padding(end = 8.dp))
        }
        TuiButton(if (settings) "×" else "⚙", onClick = toggle)
    }
}

@Composable private fun Busy(s: Session) {
    val p = LocalPalette.current
    val label = s.busy ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val f = s.progress
        if (f != null) Bar(f, "$label  ${(f * 100).toInt()}%")
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(); Spacer(Modifier.width(8.dp)); Txt("$label...", color = p.warn, size = 13.sp, maxLines = 1)
        }
    }
}

@Composable private fun Tabs(tab: Tab, pick: (Tab) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in Tab.entries) {
            val on = t == tab
            Box(Modifier.weight(1f).border(1.dp, if (on) p.accent else p.line).clickable { pick(t) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center) {
                Txt(t.label, color = if (on) p.accent else p.muted, size = 12.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun StatusLine(s: Session) {
    val p = LocalPalette.current
    val m = s.message ?: return
    val c = when (m.second) { Session.Kind.OK -> p.ok; Session.Kind.WARN -> p.warn; Session.Kind.ERR -> p.down; else -> p.muted }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Square(c, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(8.dp))
        Txt(m.first, color = if (m.second == Session.Kind.INFO) p.text else c, size = 13.sp, maxLines = 3)
    }
}

// --- not connected ----------------------------------------------------------------------------

@Composable private fun ConnectScreen(s: Session, onConnect: (android.hardware.usb.UsbDevice) -> Unit, modifier: Modifier,
                                      openSettings: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (SelfUpdate.state == SelfUpdate.State.AVAILABLE) {
            LegendBox("update", borderColor = p.accent) {
                Txt("RPCortex Studio ${SelfUpdate.release?.version} is out.", bold = true)
                Note("You have ${SelfUpdate.installedVersion(LocalContext.current)}. It installs from Settings, checked against its release.")
                Spacer(Modifier.height(8.dp))
                TuiButton("open settings", kind = Kind.PRIMARY, onClick = openSettings)
            }
        }
        LegendBox("boards on usb", right = "${s.boards.size}") {
            if (s.boards.isEmpty()) {
                Txt("No board is plugged in.", bold = true)
                Note("Connect one to the phone's USB-C port (or through an OTG adapter). Android then offers to open this app.")
            }
            for (b in s.boards) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Square(p.ok, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(b.productName ?: "RPCortex board", bold = true, maxLines = 1)
                        Txt(b.deviceName, color = p.muted, size = 12.sp, maxLines = 1)
                    }
                    TuiButton("connect", kind = Kind.GO, enabled = s.conn == Session.Conn.OFF) { onConnect(b) }
                }
            }
            Spacer(Modifier.height(8.dp))
            TuiButton("look again") { s.scan() }
        }
        if (BuildConfig.DEBUG) {
            var addr by remember { mutableStateOf("10.0.2.2:7777") }
            LegendBox("debug: a console relayed over tcp") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(addr, { addr = it }, "host:port", modifier = Modifier.weight(1f))
                    TuiButton("connect", enabled = s.conn == Session.Conn.OFF) {
                        val h = addr.substringBeforeLast(':'); val port = addr.substringAfterLast(':').toIntOrNull() ?: 7777
                        s.connectTcp(h, port)
                    }
                }
            }
        }
        LegendBox("what it does") {
            Note("Files on the board, packages from the RPCortex repository, firmware updates, its health, and a command " +
                 "line, over the USB cable. The board stays usable at its own prompt when you disconnect.")
        }
        Spacer(Modifier.height(16.dp))
    }
}

// --- settings ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsScreen(s: Session, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UpdateBox(s)
        LegendBox("theme") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val choices = (deviceTheme()?.let { listOf(Themes.DEVICE to it) } ?: emptyList()) + Themes.all
                for ((id, tk) in choices) {
                    val on = s.theme == id
                    Column(Modifier.width(140.dp).border(1.dp, if (on) p.accent else p.line).clickable { s.chooseTheme(id) }.padding(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(46.dp).background(tk.bg).border(1.dp, tk.line)) {
                            Box(Modifier.padding(start = 7.dp, top = 8.dp).size(26.dp).background(tk.accent))
                            Box(Modifier.padding(start = 28.dp, top = 28.dp).size(8.dp).background(tk.ok))
                            Column(Modifier.padding(start = 42.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.width(60.dp).height(4.dp).background(tk.text))
                                Box(Modifier.width(40.dp).height(4.dp).background(tk.line))
                                Box(Modifier.width(26.dp).height(4.dp).background(tk.accent))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Txt((if (on) "(•) " else "( ) ") + (if (id == Themes.DEVICE) "material you" else id), size = 12.5.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Note("The same themes as Orbit and Studio's terminal app.")
        }
        LegendBox("while connected") {
            Check(s.keepScreenOn, "keep the screen on", "while a board is connected, so a long transfer or update is not cut off") {
                s.chooseKeepScreenOn(!s.keepScreenOn)
            }
        }
        LegendBox("about") {
            Txt("RPCortex Studio ${SelfUpdate.installedVersion(ctx)} for Android", bold = true)
            Note("By NovaLabs. Talks to boards running RPCortex v2 (Vela II) over the link protocol, the same as `rpc` on a computer.")
            Spacer(Modifier.height(6.dp))
            LinkOut("source and releases", "https://github.com/${SelfUpdate.REPO}")
            LinkOut("RPCortex", "https://rpc.novalabs.app")
            LinkOut("report a problem", "https://github.com/${SelfUpdate.REPO}/issues")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun UpdateBox(s: Session) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val have = SelfUpdate.installedVersion(ctx)
    val rel = SelfUpdate.release
    LaunchedEffect(Unit) { if (SelfUpdate.state == SelfUpdate.State.IDLE) SelfUpdate.check(ctx) }
    LegendBox("updates", borderColor = if (SelfUpdate.state == SelfUpdate.State.AVAILABLE) p.accent else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (SelfUpdate.state) {
                SelfUpdate.State.CHECKING, SelfUpdate.State.DOWNLOADING, SelfUpdate.State.INSTALLING -> Spinner()
                SelfUpdate.State.AVAILABLE -> Square(p.accent)
                SelfUpdate.State.CURRENT -> Square(p.ok)
                SelfUpdate.State.FAILED -> Square(p.down)
                SelfUpdate.State.IDLE -> Square(p.off, hollow = true)
            }
            Spacer(Modifier.width(10.dp))
            Txt(when (SelfUpdate.state) {
                SelfUpdate.State.IDLE -> "version $have"
                SelfUpdate.State.CHECKING -> "looking for a new version..."
                SelfUpdate.State.CURRENT -> "up to date · $have"
                SelfUpdate.State.AVAILABLE -> "${rel?.version} is out · you have $have"
                SelfUpdate.State.DOWNLOADING -> "downloading ${rel?.version}..."
                SelfUpdate.State.INSTALLING -> "Android is installing ${rel?.version}"
                SelfUpdate.State.FAILED -> "could not update"
            }, bold = true, maxLines = 2)
        }
        if (SelfUpdate.state == SelfUpdate.State.DOWNLOADING) {
            Spacer(Modifier.height(8.dp))
            Bar(SelfUpdate.progress, "${(SelfUpdate.progress * 100).toInt()}% · ${Session.human(rel?.apkSize ?: 0)}")
        }
        if (SelfUpdate.problem.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Txt(SelfUpdate.problem, color = if (SelfUpdate.state == SelfUpdate.State.FAILED) p.down else p.warn, size = 12.5.sp)
        }
        Spacer(Modifier.height(4.dp))
        Note("From this app's GitHub releases. The download is checked against the release's SHA-256 list, and Android asks before it installs.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (SelfUpdate.state == SelfUpdate.State.AVAILABLE) {
                if (SelfUpdate.mayInstall(ctx)) TuiButton("update to ${rel?.version}", kind = Kind.GO) { SelfUpdate.install(ctx) }
                else TuiButton("allow installing", kind = Kind.PRIMARY) { SelfUpdate.openInstallSetting(ctx) }
            }
            TuiButton("check now", enabled = SelfUpdate.state != SelfUpdate.State.CHECKING &&
                SelfUpdate.state != SelfUpdate.State.DOWNLOADING && SelfUpdate.state != SelfUpdate.State.INSTALLING) {
                SelfUpdate.check(ctx)
            }
        }
        if (SelfUpdate.state == SelfUpdate.State.AVAILABLE && !SelfUpdate.mayInstall(ctx)) {
            Spacer(Modifier.height(4.dp))
            Note("Android asks once whether RPCortex Studio may install apps. Allow it, come back, and update.")
        }
        val notes = rel?.notes?.trim().orEmpty()
        if (SelfUpdate.state == SelfUpdate.State.AVAILABLE && notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Txt("what's new", color = p.legend, size = 12.5.sp)
            Txt(notes.lines().filter { it.isNotBlank() && !it.startsWith("#") }.take(8).joinToString("\n") { it.trim() },
                color = p.text, size = 12.5.sp, maxLines = 8)
        }
        Spacer(Modifier.height(6.dp))
        Check(s.checkAtStart, "look when the app opens", "at most a quick call to GitHub; nothing is downloaded without you") {
            s.chooseCheckAtStart(!s.checkAtStart)
        }
    }
}

// --- connected --------------------------------------------------------------------------------

@Composable private fun DeviceScreen(s: Session) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { if (s.status == null) s.refreshStatus() }
    var confirmRestart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val h = s.hello
        LegendBox("device", right = "connected", borderColor = p.line) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Square(p.ok); Spacer(Modifier.width(10.dp))
                Txt(s.name.ifEmpty { "this board" }, color = p.accent, bold = true, size = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            Kv("firmware", h["os"] ?: "-")
            Kv("build", h["build"] ?: "-")
            Kv("board", h["board"] ?: "-")
            Kv("account", h["user"] ?: "-")
            Kv("link", "protocol ${h["proto"] ?: "1"}" + if (h["bridge"] == "1") ", no network of its own" else "")
        }
        LegendBox("health") {
            val st = s.status
            if (st == null) Row { Spinner(); Spacer(Modifier.width(8.dp)); Txt("reading...", color = p.muted, size = 13.sp) } else {
                val m = st.mem
                if (m.totalKb != null && m.freeKb != null)
                    Bar(1f - m.freeKb!!.toFloat() / m.totalKb!!, "memory  ${m.freeKb} of ${m.totalKb} KB free, largest ${m.largestKb ?: "?"} KB")
                Spacer(Modifier.height(8.dp))
                val d = st.df
                if (d.totalKb != null && d.freeKb != null)
                    Bar(1f - d.freeKb!!.toFloat() / d.totalKb!!, "storage ${d.freeKb} of ${d.totalKb} KB free")
                Spacer(Modifier.height(8.dp))
                Kv("uptime", Parse.humanUptime(st.uptime))
            }
            Spacer(Modifier.height(8.dp))
            TuiButton("refresh") { s.refreshStatus() }
        }
        LegendBox("this connection") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuiButton("disconnect") { s.disconnect() }
                TuiButton("restart the board", kind = Kind.DANGER) { confirmRestart = true }
            }
            Spacer(Modifier.height(4.dp))
            Note("Disconnecting leaves the board at its own prompt. Restarting ends the connection; plug back in or tap connect.")
        }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmRestart) Confirm("Restart ${s.name.ifEmpty { "the board" }}? Whatever it is running stops.", yes = "restart",
                                danger = true, onYes = { s.reboot(); confirmRestart = false }, onNo = { confirmRestart = false })
}

@Composable private fun FilesScreen(s: Session) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Entry?>(null) }
    var confirm by remember { mutableStateOf<Entry?>(null) }
    var newDir by remember { mutableStateOf<String?>(null) }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) s.upload(uri, displayName(ctx, uri) ?: "upload.bin")
    }
    val pickSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val e = pending
        if (uri != null && e != null) s.download(e, uri)
        pending = null
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TuiButton("..", enabled = s.cwd != "/") { s.up() }
            Txt(s.cwd, Modifier.weight(1f), color = p.accent, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TuiButton("send here", kind = Kind.PRIMARY) { pickUpload.launch(arrayOf("*/*")) }
            TuiButton("new folder") { newDir = "" }
            TuiButton("refresh") { s.listDir(s.cwd) }
        }
        newDir?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { newDir = it }, "folder name", modifier = Modifier.weight(1f),
                      onDone = { if (name.isNotBlank()) s.mkdir(name.trim()); newDir = null })
                TuiButton("make", kind = Kind.GO, enabled = name.isNotBlank()) { s.mkdir(name.trim()); newDir = null }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, p.line)) {
            items(s.entries, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable { if (e.isDir) s.open(e) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (e.isDir) e.name + "/" else e.name, Modifier.weight(1f), color = if (e.isDir) p.accent else p.text, maxLines = 1)
                    if (!e.isDir) {
                        Txt(Session.human(e.size), color = p.muted, size = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Txt("save", Modifier.clickable { pending = e; pickSave.launch(e.name) }.padding(4.dp), size = 12.5.sp)
                    }
                    // Not offered for the top-level folders (os, etc, home...): the board refuses
                    // those anyway, and a phone screen is an easy place to tap the wrong line.
                    if (s.cwd != "/") {
                        Spacer(Modifier.width(6.dp))
                        Txt("delete", Modifier.clickable { confirm = e }.padding(4.dp), color = p.down, size = 12.5.sp)
                    }
                }
            }
        }
    }
    confirm?.let { e ->
        Confirm("Delete ${rnormJoin(s.cwd, e.name)} on the board?${if (e.isDir) " Everything in it goes too." else ""} This cannot be undone.",
                yes = "delete", danger = true, onYes = { s.delete(e); confirm = null }, onNo = { confirm = null })
    }
}

private fun rnormJoin(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

private fun displayName(ctx: android.content.Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

@Composable private fun PackagesScreen(s: Session) {
    val p = LocalPalette.current
    var confirm by remember { mutableStateOf<String?>(null) }
    val rows = remember(s.repo, s.installed) {
        val names = (s.repo.map { it.name } + s.installed.keys).distinct().sortedBy { it.lowercase() }
        names.map { n -> Triple(n, s.installed[n], s.repo.firstOrNull { it.name == n && s.runsHere(it) }) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${s.installed.size} installed · ${s.repo.count { s.runsHere(it) }} for this board", Modifier.weight(1f),
                color = p.muted, size = 12.5.sp)
            TuiButton("refresh") { s.refreshPackages() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, p.line)) {
            items(rows, key = { it.first }) { (name, have, offer) ->
                val newer = have != null && offer != null && Parse.versionCmp(offer.ver, have) > 0
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Square(when { newer -> p.accent; have != null -> p.ok; else -> p.off }, hollow = have == null)
                        Spacer(Modifier.width(10.dp))
                        Txt(name, Modifier.weight(1f), color = if (have != null) p.text else p.muted, bold = have != null)
                        when {
                            have == null && offer != null -> TuiButton("install", kind = Kind.PRIMARY) { s.install(name) }
                            newer -> TuiButton("update", kind = Kind.GO) { s.install(name) }
                        }
                        if (have != null) {
                            Spacer(Modifier.width(8.dp))
                            TuiButton("remove", kind = Kind.DANGER) { confirm = name }
                        }
                    }
                    Txt(listOfNotNull(have?.let { "installed $it" }, offer?.let { "repo ${it.ver}" }).joinToString(" · "),
                        color = p.muted, size = 12.sp)
                    offer?.desc?.takeIf { it.isNotBlank() }?.let { Txt(it, color = p.muted, size = 12.sp, maxLines = 2) }
                }
            }
        }
    }
    confirm?.let { n ->
        Confirm("Remove $n from the board? (sudo)", yes = "remove", danger = true,
                onYes = { s.remove(n); confirm = null }, onNo = { confirm = null })
    }
}

@Composable private fun FirmwareScreen(s: Session) {
    val p = LocalPalette.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (s.latest == null) s.checkFirmware() }
    val rel = s.latest
    val running = s.runningComparable
    val cmp = if (rel != null && running.isNotEmpty()) Parse.versionCmp(rel.ver, running) else 0
    val oldScheme = Regex("^v?\\d+\\.\\d+-").containsMatchIn(s.hello["os"] ?: "")
    val update = cmp > 0 || oldScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendBox("firmware", borderColor = if (update && rel != null) p.accent else null) {
            Kv("running", s.hello["os"] ?: "-")
            Kv("board", s.hello["board"] ?: "-")
            Kv("latest", rel?.name ?: "checking...")
            Kv("state", when {
                rel == null -> "-"
                oldScheme -> "update (needs --force once)"
                cmp > 0 -> "update available"
                cmp == 0 -> "up to date"
                else -> "newer than the latest release"
            }, if (rel != null && update) p.accent else p.text)
            rel?.let { Kv("size", Session.human(it.size)) }
        }
        LegendBox("over the air") {
            Note(if (s.hello["bridge"] == "1")
                  "This image has no Wi-Fi of its own. Updating it over the air needs the computer tool for now " +
                  "(it lends the board its connection)."
              else "The board downloads the release over its own Wi-Fi, checks it, writes it and restarts. Its files " +
                   "and settings are kept. It needs to be on a network.")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuiButton(if (update) "update over the air" else "reinstall over the air", kind = if (update) Kind.GO else Kind.NORMAL,
                    enabled = rel != null && s.hello["bridge"] != "1") { confirm = true }
                TuiButton("check again") { s.checkFirmware() }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (confirm) Confirm("Install ${rel?.name ?: "the latest release"} on the board? It restarts when done; files and settings are kept. (sudo)",
                         yes = "update", onYes = { s.updateOverTheAir(oldScheme); confirm = false }, onNo = { confirm = false })
}

@Composable private fun RunScreen(s: Session) {
    val p = LocalPalette.current
    var line by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.runLog.size) { if (s.runLog.isNotEmpty()) list.animateScrollToItem(s.runLog.size - 1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, p.line).padding(8.dp), state = list) {
            if (s.runLog.isEmpty()) item {
                Note("A command here runs on the board as at its prompt, and its output comes back. Interactive programs " +
                     "(editors, the Nova D1 GUI) need a terminal: use the board's own console or SSH.")
            }
            items(s.runLog.size) { i ->
                val t = s.runLog[i]
                Txt(t, color = when { t.startsWith("> ") -> p.accent; t.startsWith("[rc 0]") -> p.ok; t.startsWith("[") -> p.warn; else -> p.text },
                    size = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(line, { line = it }, "a command", modifier = Modifier.weight(1f), onDone = { s.run(line); line = "" })
            TuiButton("run", kind = Kind.GO, enabled = line.isNotBlank() && s.busy == null) { s.run(line); line = "" }
        }
    }
}

// --- dialogs ------------------------------------------------------------------------------------

@Composable private fun Confirm(text: String, yes: String, danger: Boolean = false, onYes: () -> Unit, onNo: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onNo) {
        Column(Modifier.border(1.dp, if (danger) p.down else p.lineHi).background(p.bg).padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(text)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TuiButton(yes, kind = if (danger) Kind.DANGER_GO else Kind.GO, onClick = onYes)
                TuiButton("cancel", onClick = onNo)
            }
        }
    }
}

@Composable private fun SudoDialog(s: Session, ask: Session.SudoAsk) {
    val p = LocalPalette.current
    var pw by remember(ask) { mutableStateOf("") }
    Dialog(onDismissRequest = { s.answerSudo(null) }) {
        Box(Modifier.background(p.bg).padding(4.dp)) {
            LegendBox("sudo", borderColor = p.accent) {
                Txt(if (ask.attempt > 0) "That password was refused. Password for ${ask.who} (${ask.attempt + 1} of 3)"
                    else "The board asks for ${ask.who}'s password before it changes the system.")
                Spacer(Modifier.height(10.dp))
                Field(pw, { pw = it }, "password", secret = true, onDone = { s.answerSudo(pw) })
                Spacer(Modifier.height(6.dp))
                Note("It goes to the board and is not kept here.")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TuiButton("ok", kind = Kind.GO, enabled = pw.isNotEmpty()) { s.answerSudo(pw) }
                    TuiButton("cancel") { s.answerSudo(null) }
                }
            }
        }
    }
}
