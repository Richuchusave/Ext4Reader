package ext4reader.partition

import ext4reader.blocks.BlockDevice
import ext4reader.blocks.u32le
import ext4reader.blocks.u64le

fun scanGpt(dev: BlockDevice): List<PartitionCandidate> {
    try {
        if (dev.sectorCount < 2) return emptyList()
        val ss = dev.sectorSize
        if (ss < 92) return emptyList()
        val hdr = dev.readBytes(ss.toLong() * 1, ss)
        if (hdr.size < 92) return emptyList()
        val sig = byteArrayOf(0x45, 0x46, 0x49, 0x20, 0x50, 0x41, 0x52, 0x54)
        for (i in sig.indices) {
            if (hdr[i] != sig[i]) return emptyList()
        }
        val entryLba = hdr.u64le(72)
        val numEntriesRaw = hdr.u32le(80)
        val entrySizeRaw = hdr.u32le(84)
        if (numEntriesRaw <= 0) return emptyList()
        if (entrySizeRaw < 128 || entrySizeRaw > 1024) return emptyList()
        if (entryLba < 0 || entryLba >= dev.sectorCount) return emptyList()
        val numEntries = minOf(numEntriesRaw, 256L).toInt()
        val entrySize = entrySizeRaw.toInt()
        val out = mutableListOf<PartitionCandidate>()
        var index = 10
        for (i in 0 until numEntries) {
            val entryOff = entryLba * ss + i.toLong() * entrySize.toLong()
            val e: ByteArray
            try {
                e = dev.readBytes(entryOff, 128)
            } catch (ex: Exception) {
                continue
            }
            if (e.size < 128) continue
            var allZero = true
            for (b in 0 until 16) {
                if (e[b] != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            if (allZero) continue
            val firstLba = e.u64le(32)
            val lastLba = e.u64le(40)
            if (firstLba < 0 || lastLba < 0) continue
            if (firstLba > lastLba) continue
            if (firstLba >= dev.sectorCount) continue
            if (lastLba >= dev.sectorCount) continue
            val nameBytes = e.copyOfRange(56, 128)
            val rawName = String(nameBytes, Charsets.UTF_16LE)
            val cut = rawName.substringBefore('\u0000')
            var trimmed = cut.trim()
            trimmed = trimmed.trim { it == '\u0000' }
            val sb = StringBuilder()
            for (b in 0 until 16) {
                sb.append(String.format("%02x", e[b].toInt() and 0xFF))
            }
            val typeHint = "GPT:" + sb.toString()
            val label = if (trimmed.isNotEmpty()) trimmed else "gpt-p$index"
            out.add(PartitionCandidate(index, firstLba, lastLba, typeHint, label))
            index++
        }
        return out
    } catch (ex: Exception) {
        return emptyList()
    }
}
