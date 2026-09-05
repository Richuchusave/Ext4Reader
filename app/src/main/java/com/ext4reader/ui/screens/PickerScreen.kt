package com.ext4reader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ext4reader.ui.MainActivity

@Composable
fun PickerScreen(
    activity: MainActivity,
    onOpened: () -> Unit,
    onTestImg: () -> Unit
) {
    var refresh by remember { mutableStateOf(0) }
    val devices = remember(refresh, activity.blockDevice) {
        activity.usbManager.deviceList.values.toList()
    }
    if (activity.blockDevice != null) {
        Text("Connected: ${activity.usbLabel}")
        Row {
            Button(onClick = onOpened) { Text("Browse partitions") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { activity.closeDevice() }) { Text("Disconnect") }
        }
    } else {
        Text(
            "Pick a USB drive (Kingston DataTraveler and most sticks work).",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "If the system claims it first, eject it from Files, then tap below.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "UASP-only enclosures are unsupported \u2014 Bulk-Only (BOT) sticks only.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) Text("No USB devices visible.")
        LazyColumn {
            items(devices, key = { it.deviceName }) { d ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.productName ?: d.deviceName)
                            Text(
                                "vid=${d.vendorId} pid=${d.productId}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = { activity.requestPermission(d) }) { Text("Allow") }
                    }
                }
            }
        }
        Row {
            OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = onTestImg) { Text("Test .img file") }
        }
    }
}
