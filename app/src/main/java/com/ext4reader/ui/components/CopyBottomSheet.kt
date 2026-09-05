package com.ext4reader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class CopySheetState(
    val label: String,
    val copiedBytes: Long,
    val totalBytes: Long,
    val filesDone: Long,
    val totalFiles: Long,
    val currentFile: String,
    val indeterminate: Boolean
)

@Composable
fun CopyBottomSheet(
    state: CopySheetState,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Copying ${state.label}",
                style = MaterialTheme.typography.titleSmall
            )
            val progress = if (state.totalBytes > 0) {
                (state.copiedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
            if (state.indeterminate || state.totalBytes <= 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Text(
                "${humanSize(state.copiedBytes)}/${humanSize(state.totalBytes)} \u00b7 file ${state.filesDone}/${state.totalFiles}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                state.currentFile,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                Text("Cancel")
            }
        }
    }
}
