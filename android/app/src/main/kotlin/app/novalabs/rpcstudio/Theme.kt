package app.novalabs.rpcstudio

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
        "tui" to Palette(bg = Color(0xFF1C1C1E), raise = Color(0xFF262629), line = Color(0xFF3D3D42), lineHi = Color(0xFF5B5B62), text = Color(0xFFD6D6D8), muted = Color(0xFF8C8C93), legend = Color(0xFF9A9AA1), accent = Color(0xFFF0668E), accentInk = Color(0xFF1C1C1E), ok = Color(0xFF23A55A), warn = Color(0xFFF0B232), down = Color(0xFFF23F43), off = Color(0xFF6D6F78), sel = Color(0xFFE8E8EA)),
        "phosphor" to Palette(bg = Color(0xFF070C08), raise = Color(0xFF0D1710), line = Color(0xFF1D3A23), lineHi = Color(0xFF2F6A3B), text = Color(0xFFA8FFB0), muted = Color(0xFF4F9A5A), legend = Color(0xFF62B96E), accent = Color(0xFF39FF7A), accentInk = Color(0xFF04140A), ok = Color(0xFF39FF7A), warn = Color(0xFFE6FF5C), down = Color(0xFFFF5C5C), off = Color(0xFF2F5A36), sel = Color(0xFFA8FFB0)),
        "amber" to Palette(bg = Color(0xFF120D05), raise = Color(0xFF1D1508), line = Color(0xFF3E2E12), lineHi = Color(0xFF6B4E1C), text = Color(0xFFFFCF7A), muted = Color(0xFFA07A3C), legend = Color(0xFFC4913F), accent = Color(0xFFFFB000), accentInk = Color(0xFF120D05), ok = Color(0xFFFFD36B), warn = Color(0xFFFF8A3D), down = Color(0xFFFF4D3D), off = Color(0xFF5E4722), sel = Color(0xFFFFCF7A)),
        "ember" to Palette(bg = Color(0xFF1A1412), raise = Color(0xFF251C18), line = Color(0xFF3F302A), lineHi = Color(0xFF644A3F), text = Color(0xFFECD9CF), muted = Color(0xFF9A8276), legend = Color(0xFFB08D7E), accent = Color(0xFFFF7A45), accentInk = Color(0xFF1A1412), ok = Color(0xFF9CCC65), warn = Color(0xFFFFC24B), down = Color(0xFFFF4F4F), off = Color(0xFF6B574E), sel = Color(0xFFF3E3DA)),
        "frost" to Palette(bg = Color(0xFF1B2028), raise = Color(0xFF232A35), line = Color(0xFF36404F), lineHi = Color(0xFF4F5D72), text = Color(0xFFDBE3EE), muted = Color(0xFF8592A6), legend = Color(0xFF93A4BB), accent = Color(0xFF7CC4E8), accentInk = Color(0xFF12171E), ok = Color(0xFF8FCF8A), warn = Color(0xFFEBCB7D), down = Color(0xFFE0707A), off = Color(0xFF5D6879), sel = Color(0xFFE6EDF5)),
        "lilac" to Palette(bg = Color(0xFF1D1A24), raise = Color(0xFF26222F), line = Color(0xFF3B3448), lineHi = Color(0xFF594E6D), text = Color(0xFFE4DDF0), muted = Color(0xFF9389A6), legend = Color(0xFFA596C2), accent = Color(0xFFB794F6), accentInk = Color(0xFF1D1A24), ok = Color(0xFF7BD88F), warn = Color(0xFFF2C66D), down = Color(0xFFF7768E), off = Color(0xFF625A73), sel = Color(0xFFEFE9F9)),
        "mono" to Palette(bg = Color(0xFF111111), raise = Color(0xFF1C1C1C), line = Color(0xFF333333), lineHi = Color(0xFF555555), text = Color(0xFFE0E0E0), muted = Color(0xFF8A8A8A), legend = Color(0xFF9E9E9E), accent = Color(0xFFFFFFFF), accentInk = Color(0xFF111111), ok = Color(0xFFCFCFCF), warn = Color(0xFFA8A8A8), down = Color(0xFFFFFFFF), off = Color(0xFF4A4A4A), sel = Color(0xFFFFFFFF)),
        "ocean" to Palette(bg = Color(0xFF0D1B24), raise = Color(0xFF13252F), line = Color(0xFF20394A), lineHi = Color(0xFF315670), text = Color(0xFFCFE6F0), muted = Color(0xFF6F93A5), legend = Color(0xFF7FA8BB), accent = Color(0xFF2EC4B6), accentInk = Color(0xFF062027), ok = Color(0xFF3DDC97), warn = Color(0xFFFFBF46), down = Color(0xFFFF6B6B), off = Color(0xFF3B5566), sel = Color(0xFFD8EEF7)),
        "paper" to Palette(bg = Color(0xFFF3F0E8), raise = Color(0xFFE9E5DA), line = Color(0xFFC4BDAE), lineHi = Color(0xFF8F877A), text = Color(0xFF2A2826), muted = Color(0xFF6B655E), legend = Color(0xFF6A645C), accent = Color(0xFFC2366B), accentInk = Color(0xFFFFFFFF), ok = Color(0xFF16733D), warn = Color(0xFF8F5B12), down = Color(0xFFC53030), off = Color(0xFFA39D92), sel = Color(0xFF2A2826)),
    )
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
