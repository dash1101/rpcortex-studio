package app.novalabs.rpcstudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import app.novalabs.rpcstudio.link.DeviceGone
import app.novalabs.rpcstudio.link.RpcError
import app.novalabs.rpcstudio.link.Transport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The board's USB serial console, through Android's USB host API.
 *
 * Android has no serial driver an app can use, so this is a small CDC-ACM driver of its own: the
 * board is a composite device (the console, a drive, a picotool interface, a keyboard), and the
 * console is its CDC data interface with one bulk endpoint each way. DTR is raised on open, which
 * the firmware reads as "a program has the port", and dropped on close, which ends a network-bridge
 * offer.
 *
 * DEVICE-UNCONFIRMED: written against the USB CDC-ACM specification and Android's UsbManager
 * documentation; not yet run on a phone. The same link client is proven against a board on a
 * computer's serial port (core's BoardTest).
 */
class UsbTransport(private val manager: UsbManager, private val device: UsbDevice) : Transport {
    private val conn: UsbDeviceConnection
    private val comm: UsbInterface?
    private val data: UsbInterface
    private val inEp: UsbEndpoint
    private val outEp: UsbEndpoint
    private val inbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lost = false
    @Volatile private var closed = false
    override val name: String = device.productName ?: device.deviceName

    init {
        var c: UsbInterface? = null
        var d: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (c == null) c = itf
                UsbConstants.USB_CLASS_CDC_DATA -> if (d == null) d = itf
            }
        }
        data = d ?: throw RpcError("This USB device has no serial console.")
        comm = c
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until data.endpointCount) {
            val ep = data.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
        }
        inEp = i ?: throw RpcError("The serial console has no input endpoint.")
        outEp = o ?: throw RpcError("The serial console has no output endpoint.")
        conn = manager.openDevice(device) ?: throw RpcError("Android would not open the USB device.")
        if (comm != null && !conn.claimInterface(comm, true)) {
            conn.close(); throw RpcError("Could not claim the console's control interface.")
        }
        if (!conn.claimInterface(data, true)) {
            conn.close(); throw RpcError("Could not claim the console. Is another app using it?")
        }
        // 115200 8N1 (the board ignores the rate; a CDC host is expected to set it) and DTR + RTS.
        comm?.let { itf ->
            val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)   // 115200, 1 stop, no parity, 8 bits
            conn.controlTransfer(0x21, 0x20, 0, itf.id, coding, coding.size, 1000)
            conn.controlTransfer(0x21, 0x22, 0x03, itf.id, null, 0, 1000)
        }
        Thread({ readLoop() }, "usb-console").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val buf = ByteArray(maxOf(inEp.maxPacketSize, 64) * 16)
        while (!closed) {
            val n = conn.bulkTransfer(inEp, buf, buf.size, 100)
            if (n > 0) { inbox.put(buf.copyOf(n)); continue }
            // A negative count is a timeout or a vanished device; only the second matters.
            if (n < 0 && !attached()) { lost = true; break }
        }
    }

    private fun attached(): Boolean = manager.deviceList.values.any { it.deviceName == device.deviceName }

    override fun read(timeoutMs: Long): ByteArray {
        if (closed) throw DeviceGone("closed")
        val first = (if (timeoutMs <= 0) inbox.poll() else inbox.poll(timeoutMs, TimeUnit.MILLISECONDS))
        if (first == null) {
            if (lost) throw DeviceGone("The board was unplugged or restarted.")
            return ByteArray(0)
        }
        var out = first
        while (true) out += inbox.poll() ?: break
        return out
    }

    override fun write(data: ByteArray) {
        if (closed || lost) throw DeviceGone("The board was unplugged or restarted.")
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, 16 * 1024)
            val sent = conn.bulkTransfer(outEp, data, off, n, 2000)
            if (sent < 0) {
                if (!attached()) { lost = true; throw DeviceGone("The board was unplugged or restarted.") }
                throw RpcError("Writing to the board timed out.")
            }
            off += sent
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            comm?.let { conn.controlTransfer(0x21, 0x22, 0, it.id, null, 0, 500) }   // DTR off
            comm?.let { conn.releaseInterface(it) }
            conn.releaseInterface(data)
        } catch (_: Exception) {
        } finally {
            conn.close()
        }
    }

    companion object {
        const val RASPBERRY_PI_VID = 0x2E8A

        /** The RPCortex boards plugged in: Raspberry Pi's vendor ID with a CDC data interface. */
        fun boards(manager: UsbManager): List<UsbDevice> = manager.deviceList.values.filter { d ->
            d.vendorId == RASPBERRY_PI_VID &&
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
        }
    }
}
