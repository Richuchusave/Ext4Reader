package ext4reader.ext4

import ext4reader.blocks.u16le
import ext4reader.blocks.u32le

data class Inode(
    val num: Long,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val flags: Long,
    val block: ByteArray,
    val extraIsize: Int
) {
    fun isDir(): Boolean = (mode and 0xF000) == 0x4000
    fun isSymlink(): Boolean = (mode and 0xF000) == 0xA000
    fun isReg(): Boolean = (mode and 0xF000) == 0x8000

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Inode) return false
        return num == other.num && mode == other.mode && uid == other.uid &&
            gid == other.gid && size == other.size && flags == other.flags &&
            block.contentEquals(other.block) && extraIsize == other.extraIsize
    }

    override fun hashCode(): Int {
        var r = num.hashCode()
        r = 31 * r + mode
        r = 31 * r + uid
        r = 31 * r + gid
        r = 31 * r + size.hashCode()
        r = 31 * r + flags.hashCode()
        r = 31 * r + block.contentHashCode()
        r = 31 * r + extraIsize
        return r
    }
}

fun Ext4Fs.readInode(num: Long): Inode {
    require(num >= 1) { "bad inode num: $num" }
    val sb = this.sb
    require(sb.inodesPerGroup > 0) { "bad inodesPerGroup" }
    val group = (num - 1) / sb.inodesPerGroup
    val idx = (num - 1) % sb.inodesPerGroup
    val descriptorSize = if (sb.is64bit) 64 else 32
    val gdtBlock: Long = if (sb.blockSize == 1024) 2 else 1
    val descLoc = partStartBytes + gdtBlock * sb.blockSize.toLong() + group * descriptorSize.toLong()
    val desc = dev.readBytes(descLoc, descriptorSize)
    require(desc.size >= descriptorSize) { "short group descriptor" }
    val tableLo = desc.u32le(8)
    val tableHi = if (sb.is64bit) {
        if (desc.size >= 44) desc.u32le(40) else 0L
    } else 0L
    val tableBlock = (tableHi shl 32) or tableLo
    val inodeOff = partStartBytes + tableBlock * sb.blockSize.toLong() + idx * sb.inodeSize.toLong()
    val raw = dev.readBytes(inodeOff, sb.inodeSize)
    require(raw.size >= 128) { "short inode (need >=128, got ${raw.size})" }
    val mode = raw.u16le(0)
    val uid = raw.u16le(2)
    val sizeLo = raw.u32le(4)
    val gid = raw.u16le(24)
    val flags = raw.u32le(32)
    val blk = raw.copyOfRange(40, 100)
    val sizeHi = if (raw.size >= 112) raw.u32le(108) else 0L
    val size = sizeLo or (sizeHi shl 32)
    val extraIsize = if (raw.size >= 130) raw.u16le(128) else 0
    return Inode(
        num = num,
        mode = mode,
        uid = uid,
        gid = gid,
        size = size,
        flags = flags,
        block = blk,
        extraIsize = extraIsize
    )
}
