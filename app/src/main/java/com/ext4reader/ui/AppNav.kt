package com.ext4reader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.ext4reader.copy.CopyWorker
import ext4reader.blocks.BlockDevice
import ext4reader.blocks.FileBlockDevice
import ext4reader.ext4.DirEntry
import ext4reader.ext4.Ext4Fs
import ext4reader.ext4.listDir
import ext4reader.partition.PartitionCandidate
import ext4reader.partition.collectCandidates
import ext4reader.partition.probeExt4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Screen {
    data object Picker : Screen
    data object Parts : Screen
    data class Browse(val candidate: PartitionCandidate) : Screen
}

private data class Probed(val c: PartitionCandidate, val isExt4: Boolean, val detail: String)

private data class UiRow(val name: String, val inode: Long, val kind: String, val size: Long)

private fun kindOf(fileType: Int, name: String): String = when (fileType) {
    2 -> "DIR"
    7 -> "SYMLINK"
    1 -> "FILE"
    else -> "INO"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }
    var error by remember { mutableStateOf<String?>(null) }
    var copyLabel by remember { mutableStateOf<String?>(null) }
    var copyBytes by remember { mutableStateOf(0L) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    var pendingCopy by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var fsRef by remember { mutableStateOf<Ext4Fs?>(null) }

    LaunchedEffect(error) {
        error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); error = null }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val (inode, name) = pendingCopy ?: return@rememberLauncherForActivityResult
        val fs = fsRef ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = DocumentFile.fromTreeUri(ctx, uri) ?: run { error = "Bad destination"; return@rememberLauncherForActivityResult }
        copyLabel = name; copyBytes = 0L
        copyJob = scope.launch(Dispatchers.IO) {
            try {
                CopyWorker.copyRecursively(fs, inode, name, dest, ctx.contentResolver,
                    onProgress = { b, _ -> copyBytes = b })
                launch(Dispatchers.Main) { Toast.makeText(ctx, "Copied $name", Toast.LENGTH_SHORT).show() }
            } catch (e: CancellationException) {
                launch(Dispatchers.Main) { Toast.makeText(ctx, "Copy cancelled", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                error = "Copy failed: ${t.message}"
            } finally {
                launch(Dispatchers.Main) { copyLabel = null; copyJob = null }
            }
        }
    }
    // .img test flow: copy SAF file into cache, wrap in FileBlockDevice.
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File(ctx.cacheDir, "test.img").also { if (it.exists()) it.delete() }
                ctx.contentResolver.openInputStream(uri).use { ins ->
                    requireNotNull(ins); tmp.outputStream().use { outs -> ins.copyTo(outs) }
                }
                withContext(Dispatchers.Main) {
                    activity.useBlockDevice(FileBlockDevice(tmp), "test.img")
                    screen = Screen.Parts
                }
            } catch (t: Throwable) { error = "IMG load failed: ${t.message}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ext4Reader") },
                navigationIcon = {
                    if (screen != Screen.Picker) TextButton(onClick = {
                        screen = when (screen) {
                            is Screen.Browse -> Screen.Parts
                            else -> Screen.Picker
                        }
                    }) { Text("Back") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(12.dp)) {
            activity.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            when (val s = screen) {
                is Screen.Picker -> UsbPickerPane(activity, onOpened = { screen = Screen.Parts },
                    onTestImg = { imgPicker.launch(arrayOf("*/*")) })
                is Screen.Parts -> PartitionListPane(activity.blockDevice,
                    onPick = { screen = Screen.Browse(it) })
                is Screen.Browse -> BrowserPane(activity, s.candidate,
                    onFs = { fsRef = it },
                    onCopy = { inode, name -> pendingCopy = inode to name; treePicker.launch(null) })
            }
            copyLabel?.let { label ->
                Spacer(Modifier.height(8.dp))
                Text("Copying $label … $copyBytes bytes")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = { copyJob?.cancel() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun UsbPickerPane(activity: MainActivity, onOpened: () -> Unit, onTestImg: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    val devices = remember(refresh, activity.blockDevice) { activity.usbManager.deviceList.values.toList() }
    if (activity.blockDevice != null) {
        Text("Connected: ${activity.usbLabel}")
        Row {
            Button(onClick = onOpened) { Text("Browse partitions") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { activity.closeDevice() }) { Text("Disconnect") }
        }
    } else {
        Text("Pick a USB drive (Kingston DataTraveler and most sticks work).", style = MaterialTheme.typography.bodyMedium)
        Text("If the system claims it first, eject it from Files, then tap below.",
            style = MaterialTheme.typography.bodySmall)
        Text("UASP-only enclosures are unsupported — Bulk-Only (BOT) sticks only.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) Text("No USB devices visible.")
        LazyColumn {
            items(devices, key = { it.deviceName }) { d ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.productName ?: d.deviceName)
                            Text("vid=${d.vendorId} pid=${d.productId}", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun PartitionListPane(dev: BlockDevice?, onPick: (PartitionCandidate) -> Unit) {
    if (dev == null) { Text("No device open."); return }
    var probes by remember { mutableStateOf<List<Probed>?>(null) }
    LaunchedEffect(dev) {
        probes = withContext(Dispatchers.IO) {
            collectCandidates(dev).map { c ->
                val r = probeExt4(dev, c)
                Probed(c, r.isExt4, r.detail)
            }
        }
    }
    val list = probes
    if (list == null) { CircularProgressIndicator(); return }
    if (list.isEmpty()) { Text("No partitions found."); return }
    LazyColumn {
        items(list, key = { it.c.startLba }) { p ->
            val mb = p.c.sizeSectors() * dev.sectorSize / (1024 * 1024)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .let { m -> if (p.isExt4) m.clickable { onPick(p.c) } else m }) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(p.c.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(if (p.isExt4) "ext4" else p.c.typeHint,
                            color = if (p.isExt4) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    Text("LBA ${p.c.startLba}–${p.c.endLbaInclusive} · ${mb}MB",
                        style = MaterialTheme.typography.bodySmall)
                    Text(p.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BrowserPane(
    activity: MainActivity,
    candidate: PartitionCandidate,
    onFs: (Ext4Fs) -> Unit,
    onCopy: (Long, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dev = activity.blockDevice
    if (dev == null) { Text("Device closed."); return }
    var fs by remember { mutableStateOf<Ext4Fs?>(null) }
    var path by remember { mutableStateOf(listOf(2L to "/")) }
    var entries by remember { mutableStateOf<List<UiRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(error) { error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); error = null } }

    LaunchedEffect(dev, candidate) {
        loading = true
        withContext(Dispatchers.IO) {
            try {
                val startBytes = candidate.startLba * dev.sectorSize.toLong()
                val opened = Ext4Fs(dev, startBytes)
                // force superblock parse now so mount errors surface here
                opened.sb.blockSize
                withContext(Dispatchers.Main) { fs = opened; onFs(opened) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { loading = false; error = "Mount failed: ${t.message}" }
            }
        }
    }
    fun load(inode: Long) {
        val f = fs ?: return
        loading = true
        scope.launch(Dispatchers.IO) {
            try {
                val rows = f.listDir(inode)
                    .filter { it.name != "." && it.name != ".." }
                    .map { e ->
                        val size = try { f.fileSize(e.inode) } catch (_: Throwable) { -1L }
                        UiRow(e.name, e.inode, kindOf(e.fileType, e.name), size)
                    }
                    .sortedWith(compareBy({ it.kind }, { it.name }))
                withContext(Dispatchers.Main) { entries = rows; loading = false }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { loading = false; error = "listDir failed: ${t.message}" }
            }
        }
    }
    LaunchedEffect(fs) { fs?.let { load(path.last().first) } }

    val (curInode, curName) = path.last()
    Row {
        Text("/" + path.drop(1).joinToString("/") { it.second }, Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall)
        if (path.size > 1) TextButton(onClick = { path = path.dropLast(1); load(path.last().first) }) { Text("Up") }
        TextButton(onClick = { onCopy(curInode, if (path.size <= 1) candidate.label else curName) }) { Text("Copy folder") }
    }
    if (loading) { CircularProgressIndicator(); return }
    LazyColumn {
        items(entries, key = { it.inode to it.name }) { e ->
            val isDir = e.kind == "DIR"
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .let { m -> if (isDir) m.clickable { path = path + (e.inode to e.name); load(e.inode) } else m }) {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        Text("ino=${e.inode} ${e.kind} ${e.size}B", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onCopy(e.inode, e.name) }) { Text("Copy") }
                }
            }
        }
    }
}
