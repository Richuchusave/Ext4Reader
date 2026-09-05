package com.ext4reader.ui.screens

data class UiRow(
    val name: String,
    val inode: Long,
    val kind: String,
    val size: Long,
    val linkTarget: String? = null
)

fun kindOf(fileType: Int): String = when (fileType) {
    2 -> "DIR"
    7 -> "SYMLINK"
    1 -> "FILE"
    else -> "INO"
}
