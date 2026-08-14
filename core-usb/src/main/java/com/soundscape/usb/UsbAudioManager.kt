package com.soundscape.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val ACTION_USB_PERMISSION = "com.soundscape.app.USB_PERMISSION"
private const val USB_AUDIO_INTERFACE_CLASS = 1 // USB_CLASS_AUDIO

@Singleton
class UsbAudioManager @Inject constructor(
    private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    val devices: StateFlow<List<UsbAudioDevice>> = _devices

    private val rawDeviceMap = mutableMapOf<String, UsbDevice>()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getUsbDeviceCompat() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) refreshDevice(device)
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    intent.getUsbDeviceCompat()?.let { onDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    intent.getUsbDeviceCompat()?.let { onDeviceDetached(it) }
                }
            }
        }
    }

    fun start() {
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(attachDetachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, permissionFilter)
            context.registerReceiver(attachDetachReceiver, attachFilter)
        }

        // Pick up devices already attached before this manager started.
        usbManager.deviceList.values
            .filter { it.isAudioDevice() }
            .forEach { onDeviceAttached(it) }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(attachDetachReceiver) }
    }

    /** Call when the user picks a device to use — triggers the system permission dialog if needed. */
    fun requestPermission(deviceName: String) {
        val device = rawDeviceMap[deviceName] ?: return
        if (usbManager.hasPermission(device)) {
            refreshDevice(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    /** Returns the live [UsbDevice] + its connection so the native layer can open it, once permitted. */
    fun openConnection(deviceName: String) =
        rawDeviceMap[deviceName]?.let { device ->
            if (usbManager.hasPermission(device)) usbManager.openDevice(device) else null
        }

    private fun onDeviceAttached(device: UsbDevice) {
        if (!device.isAudioDevice()) return
        rawDeviceMap[device.deviceName] = device
        refreshDevice(device)
    }

    private fun onDeviceDetached(device: UsbDevice) {
        rawDeviceMap.remove(device.deviceName)
        _devices.update { list -> list.filterNot { it.deviceName == device.deviceName } }
    }

    private fun refreshDevice(device: UsbDevice) {
        val hasPermission = usbManager.hasPermission(device)
        val caps = if (hasPermission) {
            usbManager.openDevice(device)?.use { connection ->
                UacDescriptorParser.parse(connection.rawDescriptors ?: ByteArray(0))
            }
        } else null

        val entry = UsbAudioDevice(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName,
            manufacturerName = device.manufacturerName,
            hasPermission = hasPermission,
            supportedSampleRates = caps?.sampleRates ?: emptyList(),
            supportedBitDepths = caps?.bitDepths ?: emptyList(),
            maxChannelCount = caps?.maxChannelCount ?: 0,
            isRecognizedAudioInterface = caps?.recognized ?: false
        )

        _devices.update { list -> list.filterNot { it.deviceName == entry.deviceName } + entry }
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == USB_AUDIO_INTERFACE_CLASS }

    private fun Intent.getUsbDeviceCompat(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    // android.hardware.usb.UsbDeviceConnection has no Closeable/use in older APIs directly,
    // so provide a tiny helper.
    private inline fun <T> android.hardware.usb.UsbDeviceConnection.use(block: (android.hardware.usb.UsbDeviceConnection) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
