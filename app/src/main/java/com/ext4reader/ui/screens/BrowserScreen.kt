package com.ext4reader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ext4reader.ui.MainActivity
import com.ext4reader.ui.components.BreadcrumbBar
import com.ext4reader.ui.components.EmptyState
import com.ext4reader.ui.components.FileRow
import com.ext4reader.ui.components.StatusBanner
import ext4reader.ext4.Ext4Fs
import ext4reader.ext4.listDir
import ext4reader.partition.PartitionCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(
    activity: MainActivity,
    candidate: PartitionCandidate,
    onFs: (Ext4Fs) -> Unit,
    onCopy: (Long, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val dev = activity.blockDevice
    if (dev == null) {
        EmptyState("Device closed.")
        return
    }
    var fs by remember { mutableStateOf<Ext4Fs?>(null) }
    // Rotation resets to root; acceptable (avoids a custom saver for inode Longs).
    var path by remember { mutableStateOf(listOf(Ext4Fs.ROOT to "/")) }
    var entries by remember { mutableStateOf<List<UiRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(dev, candidate, retryTick) {
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            try {
                val startBytes = candidate.startLba * dev.sectorSize.toLong()
                val opened = Ext4Fs(dev, startBytes)
                // Force superblock parse now so mount errors surface here.
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
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                val rows = f.listDir(inode)
                    .filter { it.name != "." && it.name != ".." }
                    .map { e ->
                        val size = try {
                            f.fileSize(e.inode)
                        } catch (_: Throwable) {
                            -1L
                        }
                        val target = if (e.fileType == 7) {
                            try {
                                f.readlink(e.inode)
                            } catch (_: Throwable) {
                                null
                            }
                        } else null
                        UiRow(e.name, e.inode, kindOf(e.fileType), size, target)
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
    val segments = listOf(candidate.label) + path.drop(1).map { it.second }

    Column {
        BreadcrumbBar(segments = segments, onNavigate = { idx ->
            val newPath = if (idx <= 0) path.take(1) else path.take((idx + 1).coerceAtMost(path.size))
            if (newPath.size != path.size && newPath.isNotEmpty()) {
                path = newPath
                query = ""
                load(newPath.last().first)
            }
        })
        Row {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TextButton(onClick = {
                onCopy(curInode, if (path.size <= 1) candidate.label else curName)
            }) { Text("Copy folder") }
        }
        error?.let { msg ->
            StatusBanner(
                message = msg,
                onRetry = {
                    error = null
                    if (fs == null) retryTick++ else load(path.last().first)
                },
                onDismiss = { error = null }
            )
        }
        if (loading) {
            CircularProgressIndicator()
            return@Column
        }
        val visible = if (query.isBlank()) entries
        else entries.filter { it.name.contains(query, ignoreCase = true) }
        if (visible.isEmpty()) {
            EmptyState(if (query.isBlank()) "Empty directory." else "No matches.")
            return@Column
        }
        LazyColumn {
            items(visible, key = { it.inode to it.name }) { e ->
                val isDir = e.kind == "DIR"
                FileRow(
                    name = e.name,
                    kind = e.kind,
                    size = e.size,
                    symlinkTarget = e.linkTarget,
                    onOpen = if (isDir) ({
                        path = path + (e.inode to e.name)
                        query = ""
                        load(e.inode)
                    }) else null,
                    onCopy = { onCopy(e.inode, e.name) }
                )
            }
        }
    }
}
