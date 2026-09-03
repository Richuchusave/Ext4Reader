package ext4reader.partition

import ext4reader.blocks.BlockDevice

fun collectCandidates(dev: BlockDevice): List<PartitionCandidate> {
    val mbr: List<PartitionCandidate> = try {
        scanMbr(dev)
    } catch (ex: Exception) {
        emptyList()
    }
    val gpt: List<PartitionCandidate> = try {
        scanGpt(dev)
    } catch (ex: Exception) {
        emptyList()
    }
    val known = (mbr + gpt).sortedBy { it.startLba }
    val gaps = mutableListOf<PartitionCandidate>()
    var gapIdx = 50
    var gapN = 0
    if (known.isNotEmpty() && dev.sectorCount > 0) {
        val first = known[0]
        if (first.startLba > 4096) {
            gaps.add(PartitionCandidate(gapIdx++, 0, first.startLba - 1, "gap", "gap-$gapN"))
            gapN++
        }
        for (i in 0 until known.size - 1) {
            val gapStart = known[i].endLbaInclusive + 1
            val gapEnd = known[i + 1].startLba - 1
            if (gapEnd >= gapStart) {
                val size = gapEnd - gapStart + 1
                if (size > 4096) {
                    gaps.add(PartitionCandidate(gapIdx++, gapStart, gapEnd, "gap", "gap-$gapN"))
                    gapN++
                }
            }
        }
        val last = known[known.size - 1]
        val diskEnd = dev.sectorCount - 1
        if (diskEnd >= last.endLbaInclusive + 1) {
            val size = diskEnd - (last.endLbaInclusive + 1) + 1
            if (size > 4096) {
                gaps.add(PartitionCandidate(gapIdx++, last.endLbaInclusive + 1, diskEnd, "gap", "gap-$gapN"))
            }
        }
    }
    val whole = PartitionCandidate(100, 0, dev.sectorCount - 1, "whole-disk", "whole-disk")
    val all = mutableListOf<PartitionCandidate>()
    all.addAll(mbr)
    all.addAll(gpt)
    all.add(whole)
    all.addAll(gaps)
    val seen = LinkedHashMap<Long, PartitionCandidate>()
    for (c in all) {
        if (!seen.containsKey(c.startLba)) {
            seen[c.startLba] = c
        }
    }
    return seen.values.sortedBy { it.startLba }
}
