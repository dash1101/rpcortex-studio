package app.novalabs.rpcstudio

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.novalabs.rpcstudio.link.Entry
import app.novalabs.rpcstudio.link.Parse

private val P = staticCompositionLocalOf { Themes.get("tui") }
private val Mono = FontFamily.Monospace

// --- small pieces, in Orbit's manner: square boxes, a title in the border line, mono data -------

@Composable private fun T(text: String, color: Color = P.current.text, size: Int = 14, mono: Boolean = false,
                          bold: Boolean = false, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontFamily = if (mono) Mono else FontFamily.Default,
                          fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable private fun Panel(title: String, right: String = "", modifier: Modifier = Modifier,
                              content: @Composable () -> Unit) {
    val p = P.current
    Column(modifier.fillMaxWidth().border(1.dp, p.line).background(p.raise).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(title, p.muted, 12, mono = true, modifier = Modifier.weight(1f))
            if (right.isNotEmpty()) T(right, p.muted, 12, mono = true)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable private fun Btn(label: String, primary: Boolean = false, enabled: Boolean = true,
                            danger: Boolean = false, onClick: () -> Unit) {
    val p = P.current
    val fg = when { !enabled -> p.off; primary -> p.accentInk; danger -> p.down; else -> p.text }
    val bg = if (primary && enabled) p.accent else p.raise
    Box(Modifier.border(1.dp, if (primary && enabled) p.accent else p.lineHi).background(bg)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        T(label, fg, 14, bold = primary)
    }
}

@Composable private fun Dot(color: Color) { Box(Modifier.size(9.dp).background(color, CircleShape)) }

@Composable private fun Kv(k: String, v: String, vColor: Color = P.current.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        T(k, P.current.muted, 13, mono = true, modifier = Modifier.width(96.dp))
        T(v, vColor, 13, mono = true)
    }
}

@Composable private fun Bar(frac: Float, label: String) {
    val p = P.current
    Column {
        Box(Modifier.fillMaxWidth().height(8.dp).background(p.line)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).background(p.accent))
        }
        T(label, p.muted, 12, mono = true, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false,
                              onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val p = P.current
    Box(modifier.border(1.dp, p.lineHi).background(p.bg).padding(10.dp)) {
        if (value.isEmpty()) T(hint, p.off, 14, mono = true)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(p.accent),
            textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = Mono),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
                                              imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }), modifier = Modifier.fillMaxWidth())
    }
}

// --- the app ----------------------------------------------------------------------------------

private enum class Tab(val label: String) { DEVICE("device"), FILES("files"), PACKAGES("packages"), FIRMWARE("firmware"), RUN("run") }

@Composable
fun StudioApp(s: Session, onConnect: (android.hardware.usb.UsbDevice) -> Unit) {
    CompositionLocalProvider(P provides Themes.get(s.theme)) {
        val p = P.current
        var tab by remember { mutableStateOf(Tab.DEVICE) }
        Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().imePadding()) {
            TopBar(s)
            s.busy?.let { label ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    val f = s.progress
                    if (f != null) Bar(f, "$label  ${(f * 100).toInt()}%") else T("$label...", p.warn, 12, mono = true)
                }
            }
            if (s.conn != Session.Conn.ON) {
                ConnectScreen(s, onConnect, Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in Tab.entries) {
                        val on = t == tab
                        Box(Modifier.weight(1f).border(1.dp, if (on) p.accent else p.line)
                            .background(if (on) p.raise else p.bg).clickable { tab = t }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            T(t.label, if (on) p.accent else p.muted, 12, mono = true)
                        }
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
        s.sudo?.let { SudoDialog(s, it) }
    }
}

@Composable private fun TopBar(s: Session) {
    val p = P.current
    Row(Modifier.fillMaxWidth().border(1.dp, p.line).background(p.raise).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T("[rpc]", p.accent, 15, mono = true, bold = true)
        Spacer(Modifier.width(8.dp))
        Dot(when (s.conn) { Session.Conn.ON -> p.ok; Session.Conn.CONNECTING -> p.warn; else -> p.off })
        Spacer(Modifier.width(8.dp))
        val h = s.hello
        T(if (s.conn == Session.Conn.ON) listOf(s.name, h["board"] ?: "", h["os"] ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
          else "RPCortex Studio", p.text, 14, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
        if (s.conn == Session.Conn.ON) T("disconnect", p.muted, 12, mono = true,
                                         modifier = Modifier.clickable { s.disconnect() }.padding(4.dp))
    }
}

@Composable private fun StatusLine(s: Session) {
    val p = P.current
    val m = s.message ?: return
    val c = when (m.second) { Session.Kind.OK -> p.ok; Session.Kind.WARN -> p.warn; Session.Kind.ERR -> p.down; else -> p.text }
    Box(Modifier.fillMaxWidth().border(1.dp, p.line).background(p.raise).padding(10.dp)) {
        T(m.first, c, 13, mono = true, maxLines = 3)
    }
}

@Composable private fun ConnectScreen(s: Session, onConnect: (android.hardware.usb.UsbDevice) -> Unit, modifier: Modifier) {
    val p = P.current
    Column(modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("boards on USB", "${s.boards.size}") {
            if (s.boards.isEmpty()) {
                T("No board is plugged in. Connect one to the phone's USB-C port (or through an OTG adapter); " +
                  "Android then offers to open this app.", p.muted, 14)
            }
            for (b in s.boards) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(b.productName ?: "RPCortex board", p.text, 15)
                        T(b.deviceName, p.muted, 12, mono = true)
                    }
                    Btn("connect", primary = true, enabled = s.conn == Session.Conn.OFF) { onConnect(b) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Btn("look again") { s.scan() }
        }
        if (BuildConfig.DEBUG) {
            var addr by remember { mutableStateOf("10.0.2.2:7777") }
            Panel("debug: a console relayed over TCP") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(addr, { addr = it }, "host:port", modifier = Modifier.weight(1f))
                    Btn("connect", enabled = s.conn == Session.Conn.OFF) {
                        val h = addr.substringBeforeLast(':'); val port = addr.substringAfterLast(':').toIntOrNull() ?: 7777
                        s.connectTcp(h, port)
                    }
                }
            }
        }
        Panel("theme") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in Themes.names.take(5)) ThemeChip(s, name, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in Themes.names.drop(5)) ThemeChip(s, name, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun ThemeChip(s: Session, name: String, modifier: Modifier) {
    val t = Themes.get(name)
    Box(modifier.border(1.dp, if (s.theme == name) t.accent else t.line).background(t.bg)
        .clickable { s.chooseTheme(name) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        T(name, t.accent, 11, mono = true)
    }
}

@Composable private fun DeviceScreen(s: Session) {
    val p = P.current
    LaunchedEffect(Unit) { if (s.status == null) s.refreshStatus() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val h = s.hello
        Panel("device", "connected") {
            Kv("name", s.name.ifEmpty { "-" }, p.accent)
            Kv("firmware", h["os"] ?: "-")
            Kv("build", h["build"] ?: "-")
            Kv("board", h["board"] ?: "-")
            Kv("account", h["user"] ?: "-")
            Kv("link", "protocol ${h["proto"] ?: "1"}" + if (h["bridge"] == "1") ", no network of its own" else "")
        }
        Panel("health", "refresh", Modifier.clickable { s.refreshStatus() }) {
            val st = s.status
            if (st == null) T("reading...", p.muted, 13, mono = true) else {
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
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("refresh") { s.refreshStatus() }
            Btn("restart", danger = true) { s.reboot() }
        }
    }
}

@Composable private fun FilesScreen(s: Session) {
    val p = P.current
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
            Btn("up", enabled = s.cwd != "/") { s.up() }
            T(s.cwd, p.accent, 14, mono = true, maxLines = 1, modifier = Modifier.weight(1f))
            Btn("send here") { pickUpload.launch(arrayOf("*/*")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("new folder") { newDir = "" }
            Btn("refresh") { s.listDir(s.cwd) }
        }
        newDir?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { newDir = it }, "folder name", modifier = Modifier.weight(1f),
                      onDone = { if (name.isNotBlank()) s.mkdir(name.trim()); newDir = null })
                Btn("make", primary = true, enabled = name.isNotBlank()) { s.mkdir(name.trim()); newDir = null }
            }
        }
        LazyColumn(Modifier.weight(1f).border(1.dp, p.line).background(p.raise)) {
            items(s.entries, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable { if (e.isDir) s.open(e) }.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T(if (e.isDir) e.name + "/" else e.name, if (e.isDir) p.accent else p.text, 14, mono = true,
                      maxLines = 1, modifier = Modifier.weight(1f))
                    if (!e.isDir) {
                        T(Session.human(e.size), p.muted, 12, mono = true)
                        Spacer(Modifier.width(10.dp))
                        T("save", p.text, 12, mono = true, modifier = Modifier.clickable { pending = e; pickSave.launch(e.name) }.padding(4.dp))
                    }
                    // Not offered for the top-level folders (os, etc, home...): the board refuses
                    // those anyway, and a phone screen is an easy place to tap the wrong line.
                    if (s.cwd != "/") {
                        Spacer(Modifier.width(6.dp))
                        T("delete", p.down, 12, mono = true, modifier = Modifier.clickable { confirm = e }.padding(4.dp))
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
    val p = P.current
    var confirm by remember { mutableStateOf<String?>(null) }
    val rows = remember(s.repo, s.installed) {
        val names = (s.repo.map { it.name } + s.installed.keys).distinct().sortedBy { it.lowercase() }
        names.map { n -> Triple(n, s.installed[n], s.repo.firstOrNull { it.name == n && s.runsHere(it) }) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T("${s.installed.size} installed · ${s.repo.count { s.runsHere(it) }} for this board", p.muted, 12, mono = true,
              modifier = Modifier.weight(1f))
            Btn("refresh") { s.refreshPackages() }
        }
        LazyColumn(Modifier.weight(1f).border(1.dp, p.line).background(p.raise)) {
            items(rows, key = { it.first }) { (name, have, offer) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(name, if (have != null) p.text else p.muted, 15, mono = true, modifier = Modifier.weight(1f))
                        val newer = have != null && offer != null && Parse.versionCmp(offer.ver, have) > 0
                        when {
                            have == null && offer != null -> T("install", p.accent, 13, mono = true,
                                modifier = Modifier.clickable { s.install(name) }.padding(4.dp))
                            newer -> T("update to ${offer!!.ver}", p.accent, 13, mono = true,
                                modifier = Modifier.clickable { s.install(name) }.padding(4.dp))
                        }
                        if (have != null) {
                            Spacer(Modifier.width(8.dp))
                            T("remove", p.down, 13, mono = true, modifier = Modifier.clickable { confirm = name }.padding(4.dp))
                        }
                    }
                    T(listOfNotNull(have?.let { "installed $it" }, offer?.let { "repo ${it.ver}" }).joinToString(" · ") +
                      (offer?.desc?.let { "  $it" } ?: ""), p.muted, 12, maxLines = 2)
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
    val p = P.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (s.latest == null) s.checkFirmware() }
    val rel = s.latest
    val running = s.runningComparable
    val cmp = if (rel != null && running.isNotEmpty()) Parse.versionCmp(rel.ver, running) else 0
    val oldScheme = Regex("^v?\\d+\\.\\d+-").containsMatchIn(s.hello["os"] ?: "")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel("firmware") {
            Kv("running", s.hello["os"] ?: "-")
            Kv("board", s.hello["board"] ?: "-")
            Kv("latest", rel?.name ?: "checking...")
            Kv("state", when {
                rel == null -> "-"
                oldScheme -> "update (needs --force once)"
                cmp > 0 -> "update available"
                cmp == 0 -> "up to date"
                else -> "newer than the latest release"
            }, if (rel != null && (cmp > 0 || oldScheme)) p.accent else p.text)
            rel?.let { Kv("size", Session.human(it.size)) }
        }
        Panel("over the air") {
            T(if (s.hello["bridge"] == "1")
                  "This image has no Wi-Fi of its own. Updating it over the air needs the computer tool for now " +
                  "(it lends the board its connection); this app will do the same in a later version."
              else "The board downloads the release over its own Wi-Fi, checks it, writes it and restarts. Its files " +
                   "and settings are kept. It needs to be on a network.", p.muted, 13)
            Spacer(Modifier.height(10.dp))
            Btn(if (cmp > 0 || oldScheme) "update over the air" else "reinstall over the air", primary = cmp > 0 || oldScheme,
                enabled = rel != null && s.hello["bridge"] != "1") { confirm = true }
        }
        Btn("check again") { s.checkFirmware() }
    }
    if (confirm) Confirm("Install ${rel?.name ?: "the latest release"} on the board? It restarts when done; files and settings are kept. (sudo)",
                         yes = "update", onYes = { s.updateOverTheAir(oldScheme); confirm = false }, onNo = { confirm = false })
}

@Composable private fun RunScreen(s: Session) {
    val p = P.current
    var line by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.runLog.size) { if (s.runLog.isNotEmpty()) list.animateScrollToItem(s.runLog.size - 1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, p.line).background(p.bg).padding(8.dp), state = list) {
            items(s.runLog.size) { i ->
                val t = s.runLog[i]
                T(t, when { t.startsWith("> ") -> p.accent; t.startsWith("[rc 0]") -> p.ok; t.startsWith("[") -> p.warn; else -> p.text },
                  13, mono = true)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(line, { line = it }, "a command, as at the prompt", modifier = Modifier.weight(1f),
                  onDone = { s.run(line); line = "" })
            Btn("run", primary = true, enabled = line.isNotBlank() && s.busy == null) { s.run(line); line = "" }
        }
    }
}

// --- dialogs ------------------------------------------------------------------------------------

@Composable private fun Confirm(text: String, yes: String, danger: Boolean = false, onYes: () -> Unit, onNo: () -> Unit) {
    val p = P.current
    Dialog(onDismissRequest = onNo) {
        Column(Modifier.border(1.dp, p.lineHi).background(p.raise).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T(text, p.text, 15)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(yes, primary = !danger, danger = danger, onClick = onYes)
                Btn("cancel", onClick = onNo)
            }
        }
    }
}

@Composable private fun SudoDialog(s: Session, ask: Session.SudoAsk) {
    val p = P.current
    var pw by remember(ask) { mutableStateOf("") }
    Dialog(onDismissRequest = { s.answerSudo(null) }) {
        Column(Modifier.border(1.dp, p.accent).background(p.raise).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("sudo", p.accent, 13, mono = true)
            T(if (ask.attempt > 0) "That password was refused. Password for ${ask.who} (${ask.attempt + 1} of 3)"
              else "The board asks for ${ask.who}'s password before it changes the system.", p.text, 15)
            Field(pw, { pw = it }, "password", secret = true, onDone = { s.answerSudo(pw) })
            T("It goes to the board and is not kept here.", p.muted, 12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("ok", primary = true, enabled = pw.isNotEmpty()) { s.answerSudo(pw) }
                Btn("cancel") { s.answerSudo(null) }
            }
        }
    }
}
