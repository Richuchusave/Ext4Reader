package ext4reader.partition

import ext4reader.blocks.BlockDevice
import ext4reader.blocks.u16le
import ext4reader.blocks.u32le

fun probeExt4(dev: BlockDevice, c: PartitionCandidate): ProbeResult {
    try {
        val startBytes = c.startLba * dev.sectorSize.toLong()
        val sb = dev.readBytes(startBytes + 1024, 1024)
        if (sb.size < 0x38 + 2) {
            return ProbeResult(c, false, "no magic")
        }
        val magic = sb.u16le(0x38)
        if (magic != 0xEF53) {
            return ProbeResult(c, false, "no magic")
        }
        val logBlock = sb.u32le(24)
        val blocksPerGroup = sb.u32le(32)
        val inodesPerGroup = sb.u32le(40)
        val inodesCount = sb.u32le(0)
        if (logBlock < 0 || logBlock > 6 || blocksPerGroup == 0L || inodesPerGroup == 0L || inodesCount == 0L) {
            return ProbeResult(c, false, "bad superblock logBlock=$logBlock")
        }
        val blockSize = 1024L shl logBlock.toInt()
        val detail = "magic EF53, logBlock=$logBlock blocksize=$blockSize typeHint=${c.typeHint}"
        return ProbeResult(c, true, detail)
    } catch (ex: Exception) {
        return ProbeResult(c, false, "error: " + (ex.message ?: ex.toString()))
    }
}
