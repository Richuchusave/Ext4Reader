package ext4reader.ext4

import ext4reader.blocks.u16le
import ext4reader.blocks.u32le

data class DirEntry(
    val name: String,
    val inode: Long,
    val fileType: Int
)

fun parseDirBlock(bytes: ByteArray): List<DirEntry> {
    val out = ArrayList<DirEntry>()
    var off = 0
    while (off + 8 <= bytes.size) {
        val ino = bytes.u32le(off)
        val recLen = bytes.u16le(off + 4)
        if (recLen < 8) break
        val nameLen = bytes[off + 6].toInt() and 0xFF
        val fileType = bytes[off + 7].toInt() and 0xFF
        if (ino != 0L && nameLen > 0 && off + 8 + nameLen <= bytes.size && off + 8 + nameLen <= off + recLen) {
            val name = String(bytes, off + 8, nameLen, Charsets.UTF_8)
            out.add(DirEntry(name, ino, fileType))
        }
        if (recLen == 0) break
        off += recLen
        if (off >= bytes.size) break
    }
    return out
}

fun Ext4Fs.listDir(inodeNum: Long): List<DirEntry> {
    val bytes = readFileBytes(inodeNum)
    if (bytes.isEmpty()) return emptyList()
    return parseDirBlock(bytes)
}
