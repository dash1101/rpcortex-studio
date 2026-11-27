package app.novalabs.rpcstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Orbit's look (its android/.../Look.kt), so the two apps sit side by side on a phone: mono text,
 * boxes with the title cut into the top border, square buttons that are a border until pressed.
 */

val LocalPalette = staticCompositionLocalOf { Themes.get("tui") }
val Mono = FontFamily.Monospace

@Composable
fun Txt(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.text, size: TextUnit = 14.sp,
        bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Mono, fontSize = size, lineHeight = size * 1.45f,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
}

/** A box whose title is cut into its top border; [right] sits in the border at the other end. */
@Composable
fun LegendBox(title: String, modifier: Modifier = Modifier, right: String = "", borderColor: Color? = null,
              content: @Composable ColumnScope.() -> Unit) {
    val t = LocalPalette.current
    Box(modifier.fillMaxWidth().padding(top = 9.dp)) {
        Column(
            Modifier.fillMaxWidth().border(1.dp, borderColor ?: t.line)
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
            content = content,
        )
        Txt(title, Modifier.offset(x = 10.dp, y = (-9).dp).background(t.bg).padding(horizontal = 5.dp),
            color = t.legend, size = 13.sp, maxLines = 1)
        if (right.isNotEmpty()) {
            Box(Modifier.matchParentSize().padding(end = 10.dp), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                Txt(right, Modifier.offset(y = (-9).dp).background(t.bg).padding(horizontal = 5.dp),
                    color = t.muted, size = 13.sp, maxLines = 1)
            }
        }
    }
}

enum class Kind { NORMAL, PRIMARY, GO, DANGER, DANGER_GO }

@Composable
fun TuiButton(label: String, modifier: Modifier = Modifier, kind: Kind = Kind.NORMAL, enabled: Boolean = true,
              onClick: () -> Unit) {
    val t = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val edge = when (kind) {
        Kind.PRIMARY, Kind.GO -> t.accent
        Kind.DANGER, Kind.DANGER_GO -> t.down
        Kind.NORMAL -> t.lineHi
    }
    val fill = when {
        pressed -> edge
        kind == Kind.GO -> t.accent
        kind == Kind.DANGER_GO -> t.down
        else -> Color.Transparent
    }
    val ink = when {
        pressed || kind == Kind.GO -> t.accentInk
        kind == Kind.DANGER_GO -> t.bg
        kind == Kind.PRIMARY -> t.accent
        kind == Kind.DANGER -> t.down
        else -> t.text
    }
    Box(
        modifier.alpha(if (enabled) 1f else .45f).background(fill).border(1.dp, edge)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Txt(label, color = ink, maxLines = 1) }
}

/** A status square: filled, or hollow for "not yet". */
@Composable
fun Square(color: Color, size: Dp = 8.dp, hollow: Boolean = false, modifier: Modifier = Modifier) {
    Box(if (hollow) modifier.size(size).border(1.dp, color) else modifier.size(size).background(color))
}

private const val SPIN = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

@Composable
fun Spinner(color: Color = LocalPalette.current.accent) {
    val i by produceState(0) { while (true) { delay(80); value = (value + 1) % SPIN.length } }
    Txt(SPIN[i].toString(), color = color)
}

/** A choice in a list: "(•) label" / "( ) label", as Orbit's settings draw them. */
@Composable
fun Radio(on: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalPalette.current
    androidx.compose.foundation.layout.Row(modifier.clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Txt(if (on) "(•) " else "( ) ", color = if (on) t.accent else t.muted)
        Txt(label, maxLines = 1)
    }
}

/** A switch in a list: "[x] label   on". */
@Composable
fun Check(on: Boolean, label: String, note: String = "", onClick: () -> Unit) {
    val t = LocalPalette.current
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Txt(if (on) "[x] " else "[ ] ", color = if (on) t.accent else t.muted)
        Column(Modifier.weight(1f)) {
            Txt(label)
            if (note.isNotEmpty()) Txt(note, color = t.muted, size = 12.5.sp)
        }
    }
}
