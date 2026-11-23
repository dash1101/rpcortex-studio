package app.novalabs.rpcstudio.link

/**
 * A byte stream to a device's console: a USB serial port on Android or a computer, an SSH channel,
 * a test's pipe. Reads return what has arrived (empty when nothing did within [timeoutMs]); a
 * closed or vanished port throws [DeviceGone].
 */
interface Transport {
    fun read(timeoutMs: Long): ByteArray
    fun write(data: ByteArray)
    /** Drop whatever has already arrived and not been read. */
    fun discardInput() {
        while (read(0).isNotEmpty()) { /* drain */ }
    }
    fun close()
    val name: String
}

/** ANSI escape sequences out of console text, as the device's line editor paints with them. */
object Ansi {
    // The same pattern as the desktop tool's (rpctool/transport.py).
    private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b[()][A-Za-z0-9]|\u001b[=>78]")

    fun strip(text: String): String = ANSI.replace(text, "")

    fun strip(data: ByteArray): String = strip(String(data, Charsets.UTF_8))
}
