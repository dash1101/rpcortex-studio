package app.novalabs.rpcstudio.link

/**
 * Reading what the device's commands print, for the screens. A port of the desktop tool's
 * rpctool/tui/parse.py: the same commands, the same marks, the same rules.
 */
object Parse {
    const val MARK_MEM = "--rpctui-mem--"
    const val MARK_DF = "--rpctui-df--"
    /** One EXEC for uptime, memory and storage, so a refresh is one round trip. */
    const val STATUS_LINE = "uptime ; echo $MARK_MEM ; meminfo ; echo $MARK_DF ; df"

    fun clean(text: String): String = Ansi.strip(text).replace("\r", "")

    private fun kb(label: String, text: String): Int? =
        Regex(Regex.escape(label) + "\\s*:\\s*(\\d+)\\s*KB").find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun splitMarks(text: String, vararg marks: String): List<String> {
        val parts = ArrayList<String>()
        var rest = clean(text)
        for (m in marks) {
            val i = rest.indexOf(m)
            if (i < 0) { parts.add(rest); rest = ""; continue }
            parts.add(rest.substring(0, i))
            rest = rest.substring(i + m.length)
        }
        parts.add(rest)
        return parts
    }

    /** `sysinfo`: the device's name (its "Device ID", the host part of its prompt) and owner. */
    fun sysinfo(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val t = clean(text)
        for ((key, label) in listOf("device_id" to "Device ID", "owner" to "Owner")) {
            Regex("^\\s*" + label + "\\s*:\\s*(.+?)\\s*$", RegexOption.MULTILINE).find(t)?.let { out[key] = it.groupValues[1] }
        }
        return out
    }

    /** "Uptime: 1h 2m 3s" -> seconds, or null. */
    fun uptime(text: String): Long? {
        val m = Regex("Uptime:\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?").find(clean(text)) ?: return null
        val g = m.groupValues.drop(1)
        if (g.all { it.isEmpty() }) return null
        val (h, mi, s) = g.map { it.toLongOrNull() ?: 0L }
        return h * 3600 + mi * 60 + s
    }

    fun humanUptime(secs: Long?): String {
        if (secs == null) return "-"
        val d = secs / 86400; var rem = secs % 86400
        val h = rem / 3600; rem %= 3600
        val m = rem / 60; val s = rem % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    data class Memory(val totalKb: Int?, val freeKb: Int?, val largestKb: Int?)
    data class Storage(val totalKb: Int?, val usedKb: Int?, val freeKb: Int?, val pct: Int?)
    data class Status(val uptime: Long?, val mem: Memory, val df: Storage)

    fun meminfo(text: String): Memory {
        val t = clean(text)
        return Memory(kb("Total", t), kb("Free", t), kb("Largest block", t))
    }

    fun df(text: String): Storage {
        val t = clean(text)
        val pct = Regex("Used\\s*:\\s*\\d+\\s*KB\\s*\\((\\d+)%\\)").find(t)?.groupValues?.get(1)?.toIntOrNull()
        return Storage(kb("Total", t), kb("Used", t), kb("Free", t), pct)
    }

    fun status(text: String): Status {
        val (up, mem, df) = splitMarks(text, MARK_MEM, MARK_DF)
        return Status(uptime(up), meminfo(mem), df(df))
    }

    /** `_pkgs` (PKGS_BEGIN / PKG:name:ver / PKGS_END): name to version, or null without it. */
    fun pkgs(text: String): Map<String, String>? {
        val t = clean(text)
        if ("PKGS_BEGIN" !in t) return null
        val out = LinkedHashMap<String, String>()
        for (ln in t.split("\n")) {
            val s = ln.trim()
            if (!s.startsWith("PKG:")) continue
            val parts = s.split(":", limit = 3)
            if (parts.size == 3) out[parts[1]] = parts[2]
        }
        return out
    }

    /** `pkg list`: name to version. */
    fun pkgList(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val name = Regex("^[A-Za-z0-9_.-]+$")
        for (ln in clean(text).split("\n").map { it.trimEnd() }) {
            val s = ln.trim()
            if (s.isEmpty() || s.startsWith("[") || s.endsWith(":") || s.lowercase().startsWith("usage")) continue
            val parts = s.split(Regex("\\s+"))
            if (parts.size >= 2 && name.matches(parts[0])) out[parts[0]] = parts[1]
            else if (parts.size == 1 && name.matches(parts[0])) out[parts[0]] = ""
        }
        return out
    }

    /** repo_version_cmp() in the firmware's os/core/repoindex.cpp: number by number; a component
     *  with a suffix sorts newer than one without; two suffixes compare as text. */
    fun versionCmp(a0: String?, b0: String?): Int {
        val a = a0 ?: ""; val b = b0 ?: ""
        var i = 0; var j = 0
        fun digits(s: String, k0: Int): Pair<Long, Int> {
            var k = k0; var v = 0L
            while (k < s.length && s[k].isDigit()) { v = minOf(v * 10 + (s[k] - '0'), 0x7FFFFFFFL); k++ }
            return v to k
        }
        while (i < a.length || j < b.length) {
            val (va, ni) = digits(a, i); i = ni
            val (vb, nj) = digits(b, j); j = nj
            if (va != vb) return if (va < vb) -1 else 1
            val si = i; val sj = j
            while (i < a.length && a[i] != '.') i++
            while (j < b.length && b[j] != '.') j++
            val sa = a.substring(si, i); val sb = b.substring(sj, j)
            if (sa.isNotEmpty() || sb.isNotEmpty()) {
                if (sa.isEmpty()) return -1
                if (sb.isEmpty()) return 1
                val n = minOf(sa.length, sb.length)
                val c = sa.substring(0, n).compareTo(sb.substring(0, n))
                if (c != 0) return if (c < 0) -1 else 1
                if (sa.length != sb.length) return if (sa.length < sb.length) -1 else 1
            }
            if (i < a.length && a[i] == '.') i++
            if (j < b.length && b[j] == '.') j++
        }
        return 0
    }
}
