package ext4reader.partition

import ext4reader.blocks.BlockDevice
import ext4reader.blocks.u32le
import ext4reader.blocks.u16le

internal fun isExtendedType(t: Int) = t == 0x05 || t == 0x0F || t == 0x85

fun scanMbr(dev: BlockDevice): List<PartitionCandidate> {
    val out = mutableListOf<PartitionCandidate>()
    if (dev.sectorCount < 1) return out
    val sec = ByteArray(dev.sectorSize)
    dev.readSectors(0, 1, sec, 0)
    if (sec.size < 512) return out
    if (sec.u16le(510) != 0xAA55) return out
    var idx = 0
    // check protective MBR: single 0xEE entry -> GPT disk, return empty here
    var onlyProtective = true
    for (i in 0 until 4) {
        val o = 0x1BE + i * 16
        val type = sec[o + 4].toInt() and 0xFF
        val start = sec.u32le(o + 8)
        val size = sec.u32le(o + 12)
        if (size == 0L) continue
        if (type != 0xEE) onlyProtective = false
    }
    // primary + extended walk
    var ebrBase = -1L
    for (i in 0 until 4) {
        val o = 0x1BE + i * 16
        val type = sec[o + 4].toInt() and 0xFF
        val start = sec.u32le(o + 8)
        val size = sec.u32le(o + 12)
        if (size == 0L || type == 0) continue
        if (type == 0xEE && onlyProtective) continue
        if (isExtendedType(type)) {
            ebrBase = start
        } else {
            out.add(PartitionCandidate(idx++, start, start + size - 1, "MBR:0x%02X".format(type), "mbr-p$idx"))
        }
    }
    // EBR chain
    var ebr = ebrBase
    var guard = 0
    while (ebr >= 0 && guard++ < 64) {
        if (ebr >= dev.sectorCount) break
        val e = ByteArray(dev.sectorSize)
        dev.readSectors(ebr, 1, e, 0)
        if (e.u16le(510) != 0xAA55) break
        val o0 = 0x1BE
        val t0 = e[o0 + 4].toInt() and 0xFF
        val s0 = e.u32le(o0 + 8)
        val z0 = e.u32le(o0 + 12)
        if (z0 > 0 && t0 != 0 && !isExtendedType(t0)) {
            val absStart = ebr + s0
            out.add(PartitionCandidate(idx++, absStart, absStart + z0 - 1, "MBR:0x%02X".format(t0), "mbr-logical$idx"))
        }
        val o1 = 0x1BE + 16
        val t1 = e[o1 + 4].toInt() and 0xFF
        val s1 = e.u32le(o1 + 8)
        if (t1 == 0 || z0 == 0L || !isExtendedType(t1)) break
        ebr = ebrBase + s1
    }
    return out.filter { it.startLba < dev.sectorCount }
        .map { it.copy(endLbaInclusive = minOf(it.endLbaInclusive, dev.sectorCount - 1)) }
}
