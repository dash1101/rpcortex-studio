package app.novalabs.rpcstudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

/**
 * RPCortex Studio for Android. Plug a board into the phone (USB-C, or a USB OTG adapter) and it
 * offers to open this; or open it and pick the board.
 */
class MainActivity : ComponentActivity() {
    private val session: Session by viewModels()
    private lateinit var usb: UsbManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val dev = intent.usbDevice()
                    if (dev != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) session.connect(dev)
                    else session.say("Android did not allow the app to use the board.", Session.Kind.WARN)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> session.scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> session.scan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        session.scan()
        handle(intent)
        setContent { StudioApp(session, onConnect = ::ask) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opened by plugging a board in: Android has already granted the permission. */
    private fun handle(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        session.scan()
        intent.usbDevice()?.let { if (usb.hasPermission(it)) session.connect(it) else ask(it) }
    }

    private fun ask(dev: UsbDevice) {
        if (usb.hasPermission(dev)) { session.connect(dev); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
        usb.requestPermission(dev, pi)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PERMISSION = "app.novalabs.rpcstudio.USB_PERMISSION"
    }
}

@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
