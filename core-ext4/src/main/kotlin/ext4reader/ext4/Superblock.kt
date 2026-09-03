package ext4reader.ext4

import ext4reader.blocks.BlockDevice
import ext4reader.blocks.u16le
import ext4reader.blocks.u32le

data class Superblock(
    val blockSize: Int,
    val blocksPerGroup: Long,
    val inodesPerGroup: Long,
    val inodeCount: Long,
    val inodeSize: Int,
    val firstDataBlock: Long,
    val featureIncompat: Long,
    val featureRoCompat: Long,
    val is64bit: Boolean,
    val usesExtents: Boolean,
    val hasInlineData: Boolean,
    val blocksCount: Long,
    val firstIno: Long
)

fun parseSuperblock(dev: BlockDevice, partStartBytes: Long): Superblock {
    val sb = dev.readBytes(partStartBytes + 1024, 1024)
    require(sb.size >= 1024) { "short superblock" }
    val magic = sb.u16le(0x38)
    if (magic != 0xEF53) throw IllegalArgumentException("bad ext4 magic: ${magic.toString(16)}")
    val inodeCount = sb.u32le(0)
    val blocksCountLo = sb.u32le(4)
    val firstDataBlock = sb.u32le(20)
    val logBlockSize = sb.u32le(24).toInt()
    val blocksPerGroup = sb.u32le(32)
    val inodesPerGroup = sb.u32le(40)
    val firstIno = sb.u32le(84)
    val rawInodeSize = sb.u16le(88)
    val inodeSize = if (rawInodeSize == 0) 128 else rawInodeSize
    val featureIncompat = sb.u32le(96)
    val featureRoCompat = sb.u32le(100)
    val is64bit = (featureIncompat and 0x80L) != 0L
    val usesExtents = (featureIncompat and 0x40L) != 0L
    val hasInlineData = (featureIncompat and 0x8000L) != 0L
    val blocksCount: Long = if (is64bit) {
        val hi = sb.u32le(336)
        (hi shl 32) or blocksCountLo
    } else {
        blocksCountLo
    }
    val blockSize = 1024 shl logBlockSize
    return Superblock(
        blockSize = blockSize,
        blocksPerGroup = blocksPerGroup,
        inodesPerGroup = inodesPerGroup,
        inodeCount = inodeCount,
        inodeSize = inodeSize,
        firstDataBlock = firstDataBlock,
        featureIncompat = featureIncompat,
        featureRoCompat = featureRoCompat,
        is64bit = is64bit,
        usesExtents = usesExtents,
        hasInlineData = hasInlineData,
        blocksCount = blocksCount,
        firstIno = firstIno
    )
}
