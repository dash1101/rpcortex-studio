"""Write the Android app's themes (android/app/.../Theme.kt) from rpctool/tui/themes.py, so the
terminal app and the phone app draw with the same colours.

    python3 tools/theme_kt.py            # writes Theme.kt
    python3 tools/theme_kt.py --check    # exits 1 when Theme.kt is out of date
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from rpctool.tui.themes import THEMES, ORDER  # noqa: E402

OUT = ROOT / "android/app/src/main/kotlin/app/novalabs/rpcstudio/Theme.kt"
FIELDS = ["bg", "raise_", "line", "line_hi", "text", "muted", "legend", "accent", "accent_ink", "ok", "warn",
          "down", "off", "sel"]
NAMES = ["bg", "raise", "line", "lineHi", "text", "muted", "legend", "accent", "accentInk", "ok", "warn", "down",
         "off", "sel"]

HEAD = '''package app.novalabs.rpcstudio

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// GENERATED from rpctool/tui/themes.py by tools/theme_kt.py: Orbit's themes, the same tokens
// Studio's terminal app uses. Edit themes.py, then run the tool.

/** A theme: a colour per role. The screens ask for roles. */
data class Palette(
    val bg: Color, val raise: Color, val line: Color, val lineHi: Color, val text: Color,
    val muted: Color, val legend: Color, val accent: Color, val accentInk: Color, val ok: Color,
    val warn: Color, val down: Color, val off: Color, val sel: Color,
)

object Themes {
    /** The phone's own colours (Material You, Android 12 and newer), as Orbit offers them. */
    const val DEVICE = "device"

    val all: List<Pair<String, Palette>> = listOf(
'''

TAIL = '''    )
    val names: List<String> = all.map { it.first }
    fun get(name: String): Palette = all.firstOrNull { it.first == name }?.second ?: all.first().second
}

/** The "tui" look in the colours Android makes from the wallpaper, light or dark as the phone is set;
 *  null on a phone without them. Green and amber keep Orbit's own shades. */
@Composable
fun deviceTheme(): Palette? {
    if (Build.VERSION.SDK_INT < 31) return null
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    return Palette(bg = s.surface, raise = s.surfaceContainer, line = s.outlineVariant, lineHi = s.outline,
        text = s.onSurface, muted = s.onSurfaceVariant, legend = s.secondary, accent = s.primary,
        accentInk = s.onPrimary, ok = if (dark) Color(0xFF7BD88F) else Color(0xFF16733D),
        warn = if (dark) Color(0xFFF2C66D) else Color(0xFF8F5B12), down = s.error, off = s.outline, sel = s.onSurface)
}
'''


def render():
    rows = []
    for name in ORDER:
        t = THEMES[name]
        cols = ", ".join("%s = Color(0xFF%s)" % (k, t[f].lstrip("#").upper()) for k, f in zip(NAMES, FIELDS))
        rows.append('        "%s" to Palette(%s),\n' % (name, cols))
    return HEAD + "".join(rows) + TAIL


def main():
    text = render()
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != text:
            print("Theme.kt is out of date: run python3 tools/theme_kt.py")
            sys.exit(1)
        return
    OUT.write_text(text)
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
