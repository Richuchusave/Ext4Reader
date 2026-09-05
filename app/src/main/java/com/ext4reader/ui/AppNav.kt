package com.ext4reader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.ext4reader.ui.components.CopyBottomSheet
import com.ext4reader.ui.components.CopySheetState
import com.ext4reader.ui.components.StatusBanner
import com.ext4reader.ui.components.humanSize
import com.ext4reader.ui.screens.BrowserScreen
import com.ext4reader.ui.screens.PartitionsScreen
import com.ext4reader.ui.screens.PickerScreen
import ext4reader.blocks.FileBlockDevice
import ext4reader.ext4.Ext4Fs
import ext4reader.partition.PartitionCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ui/screens/* now exist (PickerScreen/PartitionsScreen/BrowserScreen) with
// matching signatures, so AppRoot delegates to them. components/CopyBottomSheet.kt
// also exists, so the copy sheet uses the shared CopySheetState + CopyBottomSheet.

sealed interface Screen {
    data object Picker : Screen
    data object Parts : Screen
    data class Browse(val candidate: PartitionCandidate) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var copyState by remember { mutableStateOf<CopySheetState?>(null) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    var pendingCopy by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var fsRef by remember { mutableStateOf<Ext4Fs?>(null) }

    // Holder for picker launch lambdas so banner-retry can re-launch without
    // self-referencing the launcher vals inside their own initializer.
    val pickerHolder = remember { arrayOfNulls<() -> Unit>(2) }

    fun dismissBanner() {
        banner = null
        bannerRetry = null
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val (inode, name) = pendingCopy ?: return@rememberLauncherForActivityResult
        val fs = fsRef ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = DocumentFile.fromTreeUri(ctx, uri) ?: run {
            banner = "Bad destination"
            bannerRetry = null
            return@rememberLauncherForActivityResult
        }
        dismissBanner()
        copyState = CopySheetState(
            label = name,
            copiedBytes = 0L,
            totalBytes = 0L,
            filesDone = 0L,
            totalFiles = 0L,
            currentFile = name,
            indeterminate = true,
        )
        copyJob = scope.launch(Dispatchers.IO) {
            var doneBytes = 0L
            var doneFiles = 0L
            try {
                // Phase 1: measure (indeterminate "Measuring…" UI).
                val totals = try {
                    CopyWorker.measureFs(fs, inode)
                } catch (_: Throwable) {
                    CopyWorker.CopyTotals(0L, 0L)
                }
                copyState = copyState?.copy(
                    totalBytes = totals.bytes,
                    totalFiles = totals.files,
                    indeterminate = false,
                )
                // Phase 2: copy with progress events.
                CopyWorker.copyWithProgress(
                    fs = fs,
                    srcInode = inode,
                    srcName = name,
                    destDir = dest,
                    resolver = ctx.contentResolver,
                    onEvent = { ev ->
                        when (ev) {
                            is CopyWorker.CopyEvent.Progress -> {
                                copyState = copyState?.copy(
                                    copiedBytes = ev.copiedBytes,
                                    filesDone = ev.filesDone,
                                    currentFile = ev.currentPath,
                                )
                            }
                            is CopyWorker.CopyEvent.Done -> {
                                doneBytes = ev.totalBytes
                                doneFiles = ev.files
                                copyState = copyState?.copy(
                                    copiedBytes = ev.totalBytes,
                                    filesDone = ev.files,
                                )
                            }
                            is CopyWorker.CopyEvent.Failed -> Unit
                        }
                    },
                )
                withContext(Dispatchers.Main) {
                    copyState = null
                    copyJob = null
                    Toast.makeText(
                        ctx,
                        "Copied $doneFiles files (${humanSize(doneBytes)})",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    copyState = null
                    copyJob = null
                    Toast.makeText(ctx, "Copy cancelled", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    copyState = null
                    copyJob = null
                    banner = "Copy failed: ${t.message}"
                    bannerRetry = { pickerHolder[0]?.invoke() }
                }
            }
        }
    }
    pickerHolder[0] = { treePicker.launch(null) }

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
                    dismissBanner()
                    activity.useBlockDevice(FileBlockDevice(tmp), "test.img")
                    screen = Screen.Parts
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    banner = "IMG load failed: ${t.message}"
                    bannerRetry = { pickerHolder[1]?.invoke() }
                }
            }
        }
    }
    pickerHolder[1] = { imgPicker.launch(arrayOf("*/*")) }

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
            banner?.let { msg ->
                StatusBanner(
                    message = msg,
                    onRetry = bannerRetry,
                    onDismiss = { dismissBanner() },
                )
            }
            when (val s = screen) {
                is Screen.Picker -> PickerScreen(
                    activity = activity,
                    onOpened = { screen = Screen.Parts },
                    onTestImg = { imgPicker.launch(arrayOf("*/*")) },
                )
                is Screen.Parts -> PartitionsScreen(
                    dev = activity.blockDevice,
                    onPick = { screen = Screen.Browse(it) },
                )
                is Screen.Browse -> BrowserScreen(
                    activity = activity,
                    candidate = s.candidate,
                    onFs = { fsRef = it },
                    onCopy = { inode, name ->
                        pendingCopy = inode to name
                        treePicker.launch(null)
                    },
                )
            }
            copyState?.let { st ->
                Spacer(Modifier.height(8.dp))
                CopyBottomSheet(
                    state = st,
                    onCancel = { copyJob?.cancel() },
                )
            }
        }
    }
}
