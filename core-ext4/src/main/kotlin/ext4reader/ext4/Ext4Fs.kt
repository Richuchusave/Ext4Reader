package ext4reader.ext4

import ext4reader.blocks.BlockDevice

class Ext4Fs(
    val dev: BlockDevice,
    val partStartBytes: Long
) {
    val sb: Superblock by lazy { parseSuperblock(dev, partStartBytes) }

    fun blockBytes(fsBlock: Long): ByteArray {
        require(fsBlock >= 0) { "bad block: $fsBlock" }
        return dev.readBytes(partStartBytes + fsBlock * sb.blockSize.toLong(), sb.blockSize)
    }

    fun fileSize(inodeNum: Long): Long = readInode(inodeNum).size

    fun fileSize(inode: Inode): Long = inode.size

    fun stat(inodeNum: Long): Inode = readInode(inodeNum)

    fun readFileBytes(inodeNum: Long, offset: Long = 0, len: Long = -1): ByteArray {
        val inode = readInode(inodeNum)
        val size = inode.size
        if (offset < 0) throw IllegalArgumentException("bad offset: $offset")
        if (offset >= size) return ByteArray(0)
        var want = if (len < 0) size - offset else len
        if (want < 0) throw IllegalArgumentException("bad len: $len")
        if (offset + want > size) want = size - offset
        if (want == 0L) return ByteArray(0)
        require(want <= Int.MAX_VALUE) { "too large read: $want" }

        // Inline data: EXT4_INLINE_DATA_FL = 0x10000000
        if (sb.hasInlineData && (inode.flags and 0x10000000L) != 0L) {
            val avail = minOf(size, 60L).toInt()
            val end = minOf(avail.toLong(), offset + want)
            if (offset >= avail) return ByteArray(0)
            val from = offset.toInt()
            val to = end.toInt()
            return inode.block.copyOfRange(from, to)
        }

        val out = ByteArray(want.toInt())
        var outPos = 0
        var cur = offset
        var remaining = want
        val bs = sb.blockSize.toLong()
        while (remaining > 0) {
            val logical = cur / bs
            val offInBlock = (cur % bs).toInt()
            val take = minOf((sb.blockSize - offInBlock).toLong(), remaining).toInt()
            val phys = mapLogicalToPhysical(this, inode, logical)
            if (phys != null) {
                val blk = blockBytes(phys)
                System.arraycopy(blk, offInBlock, out, outPos, take)
            } // else hole: zeros already in place
            outPos += take
            cur += take
            remaining -= take
        }
        return out
    }

    fun readlink(inodeNum: Long): String {
        val inode = readInode(inodeNum)
        val size = inode.size
        if (size == 0L) return ""
        // Fast symlink: target stored in i_block when size < 60
        if (size < 60) {
            val n = minOf(size.toInt(), inode.block.size)
            // Trim at first NUL (fast symlinks are NUL-padded)
            var end = n
            for (i in 0 until n) {
                if (inode.block[i] == 0.toByte()) {
                    end = i
                    break
                }
            }
            return String(inode.block, 0, end, Charsets.US_ASCII)
        }
        val bytes = readFileBytes(inodeNum)
        var end = bytes.size
        for (i in bytes.indices) {
            if (bytes[i] == 0.toByte()) {
                end = i
                break
            }
        }
        return String(bytes, 0, end, Charsets.US_ASCII)
    }

    companion object {
        const val ROOT = 2L
    }
}
