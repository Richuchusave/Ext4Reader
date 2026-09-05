package com.ext4reader.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ext4reader.ui.theme.Ext4ReaderTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ext4reader.usb.UsbBlockDevice
import ext4reader.blocks.BlockDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.ext4reader.USB_PERMISSION"
    }

    lateinit var usbManager: UsbManager
        private set

    var blockDevice by mutableStateOf<BlockDevice?>(null)
        private set
    var usbLabel by mutableStateOf<String?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val dev = intentDevice(intent)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                        openDevice(dev)
                    } else toast("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intentDevice(intent)
                    statusMessage = "Attached: ${dev?.productName ?: dev?.deviceName} — tap it below to request access."
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> closeDevice()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)
        intentDevice(intent)?.let {
            statusMessage = "Launched for ${it.productName ?: it.deviceName} — request access below."
        }
        setContent { Ext4ReaderTheme { AppRoot(this) } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        closeDevice()
        super.onDestroy()
    }

    fun requestPermission(device: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pi)
    }

    fun openDevice(device: UsbDevice) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = usbManager.openDevice(device)
                    ?: error("System claimed this device (eject it from Files first), or busy.")
                val claimed = UsbBlockDevice.claim(device, conn)
                    ?: error("Unsupported device: need USB Mass Storage Bulk-Only (no UASP).")
                withContext(Dispatchers.Main) {
                    blockDevice = claimed
                    usbLabel = device.productName ?: device.deviceName
                    statusMessage = "Opened ${usbLabel}: ${claimed.sectorCount} sectors x ${claimed.sectorSize}B."
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Open failed: ${t.message}"
                    toast("Open failed: ${t.message}")
                }
            }
        }
    }

    fun useBlockDevice(dev: BlockDevice, label: String) {
        closeDevice()
        blockDevice = dev
        usbLabel = label
    }

    fun closeDevice() {
        runCatching { blockDevice?.close() }
        blockDevice = null
        usbLabel = null
    }

    fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
