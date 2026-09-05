package com.ext4reader.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ext4reader.ui.components.EmptyState
import com.ext4reader.ui.components.PartitionRow
import com.ext4reader.ui.components.humanSize
import ext4reader.blocks.BlockDevice
import ext4reader.partition.PartitionCandidate
import ext4reader.partition.collectCandidates
import ext4reader.partition.probeExt4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Probed(val c: PartitionCandidate, val isExt4: Boolean, val detail: String)

@Composable
fun PartitionsScreen(
    dev: BlockDevice?,
    onPick: (PartitionCandidate) -> Unit
) {
    if (dev == null) {
        EmptyState("No device open.")
        return
    }
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
    if (list == null) {
        CircularProgressIndicator()
        return
    }
    if (list.isEmpty()) {
        EmptyState("No partitions found.")
        return
    }
    LazyColumn {
        items(list, key = { it.c.startLba }) { p ->
            val sizeBytes = p.c.sizeSectors() * dev.sectorSize.toLong()
            PartitionRow(
                label = p.c.label,
                badge = if (p.isExt4) "ext4" else "skipped",
                isExt4 = p.isExt4,
                sub = "${humanSize(sizeBytes)} \u00b7 ${p.c.typeHint}\n${p.detail}",
                onPick = if (p.isExt4) ({ onPick(p.c) }) else null
            )
        }
    }
}
