package com.ext4reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FileRow(
    name: String,
    kind: String,
    size: Long,
    symlinkTarget: String?,
    onOpen: (() -> Unit)?,
    onCopy: () -> Unit
) {
    val icon = when (kind) {
        "DIR" -> Icons.Filled.Folder
        "FILE" -> Icons.Filled.Description
        "SYMLINK" -> Icons.Filled.Link
        else -> Icons.Filled.QuestionMark
    }
    val subtitle = when (kind) {
        "DIR" -> "DIR"
        "SYMLINK" -> "link \u2192 ${symlinkTarget ?: "\u2026"}"
        else -> humanSize(size)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .let { m -> if (onOpen != null) m.clickable(onClick = onOpen) else m }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = kind)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

@Composable
fun PartitionRow(
    label: String,
    badge: String,
    isExt4: Boolean,
    sub: String,
    onPick: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .let { m -> if (onPick != null) m.clickable(onClick = onPick) else m }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            badge,
                            color = if (isExt4) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                )
            }
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}
