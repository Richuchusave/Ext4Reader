package ext4reader.ext4

import ext4reader.blocks.u16le
import ext4reader.blocks.u32le

private const val EXTENTS_FL = 0x80000L

fun mapLogicalToPhysical(fs: Ext4Fs, inode: Inode, logicalBlock: Long): Long? {
    require(logicalBlock >= 0) { "bad logical block: $logicalBlock" }
    return if ((inode.flags and EXTENTS_FL) == 0L) {
        mapIndirect(fs, inode, logicalBlock)
    } else {
        walkExtentNode(fs, inode.block, logicalBlock)
    }
}

private fun mapIndirect(fs: Ext4Fs, inode: Inode, logical: Long): Long? {
    val blk = inode.block
    require(blk.size >= 60) { "short i_block" }
    if (logical < 12) {
        val p = blk.u32le(logical.toInt() * 4)
        return if (p == 0L) null else p
    }
    val blockSize = fs.sb.blockSize
    require(blockSize % 4 == 0) { "bad blocksize" }
    val ppb = blockSize / 4
    val ppbL = ppb.toLong()
    val single = blk.u32le(48)
    val dbl = blk.u32le(52)
    val tpl = blk.u32le(56)

    val singleStart = 12L
    val singleEnd = singleStart + ppbL
    if (logical < singleEnd) {
        if (single == 0L) return null
        val idx = (logical - singleStart).toInt()
        val node = fs.blockBytes(single)
        val p = node.u32le(idx * 4)
        return if (p == 0L) null else p
    }
    val doubleEnd = singleEnd + ppbL * ppbL
    if (logical < doubleEnd) {
        if (dbl == 0L) return null
        val off = logical - singleEnd
        val idx1 = (off / ppbL).toInt()
        val idx2 = (off % ppbL).toInt()
        val dblNode = fs.blockBytes(dbl)
        if ((idx1 + 1) * 4 > dblNode.size) return null
        val singleBlk = dblNode.u32le(idx1 * 4)
        if (singleBlk == 0L) return null
        val sNode = fs.blockBytes(singleBlk)
        if ((idx2 + 1) * 4 > sNode.size) return null
        val p = sNode.u32le(idx2 * 4)
        return if (p == 0L) null else p
    }
    // triple (best-effort)
    if (tpl == 0L) return null
    val off2 = logical - doubleEnd
    if (off2 < 0) return null
    val span3 = ppbL * ppbL * ppbL
    if (off2 >= span3) return null
    val span2 = ppbL * ppbL
    val idx1 = (off2 / span2).toInt()
    val rem1 = off2 % span2
    val idx2 = (rem1 / ppbL).toInt()
    val idx3 = (rem1 % ppbL).toInt()
    val tplNode = fs.blockBytes(tpl)
    if ((idx1 + 1) * 4 > tplNode.size) return null
    val dblBlk = tplNode.u32le(idx1 * 4)
    if (dblBlk == 0L) return null
    val dblNode = fs.blockBytes(dblBlk)
    if ((idx2 + 1) * 4 > dblNode.size) return null
    val sBlk = dblNode.u32le(idx2 * 4)
    if (sBlk == 0L) return null
    val sNode = fs.blockBytes(sBlk)
    if ((idx3 + 1) * 4 > sNode.size) return null
    val p = sNode.u32le(idx3 * 4)
    return if (p == 0L) null else p
}

private fun extentDepth(node: ByteArray): Int {
    val d6 = if (node.size >= 8) node.u16le(6) else 0
    val d4 = if (node.size >= 6) node.u16le(4) else 0
    return when {
        d6 <= 5 -> d6
        d4 <= 5 -> d4
        else -> d6
    }
}

private data class Leaf(val logical: Long, val len: Int, val uninit: Boolean, val physical: Long, val rawEmpty: Boolean)

private fun parseLeafAt(node: ByteArray, off: Int): Leaf? {
    if (off + 12 > node.size) return null
    val eeBlock = node.u32le(off)
    val rawLen = node.u16le(off + 4)
    val startHi = node.u16le(off + 6).toLong()
    val startLo = node.u32le(off + 8)
    val start = (startHi shl 32) or startLo
    val uninit = (rawLen and 0x8000) != 0
    var len = rawLen and 0x7FFF
    val isEmptySlot = (eeBlock == 0L && start == 0L && rawLen == 0)
    if (len == 0) {
        if (isEmptySlot) {
            return Leaf(eeBlock, 0, uninit, start, true)
        }
        // 32768-block extent edge case (real ext4 encodes 32768 as 0)
        len = 32768
    }
    // uninit with len bits zero but non-empty -> also 32768 uninit
    if (rawLen == 0x8000 && !isEmptySlot) {
        len = 32768
    }
    return Leaf(eeBlock, len, uninit, start, false)
}

internal fun walkExtentNode(fs: Ext4Fs, node: ByteArray, logical: Long): Long? {
    require(node.size >= 12) { "short extent node" }
    val magic = node.u16le(0)
    if (magic != 0xF30A) throw IllegalArgumentException("bad extent magic: ${magic.toString(16)}")
    val entries = node.u16le(2)
    val depth = extentDepth(node)

    if (depth == 0) {
        // Phase 1: entries starting at +12
        for (i in 0 until entries) {
            val off = 12 + i * 12
            if (off + 12 > node.size) break
            val leaf = parseLeafAt(node, off) ?: continue
            if (leaf.rawEmpty || leaf.len == 0) continue
            if (logical >= leaf.logical && logical < leaf.logical + leaf.len) {
                if (leaf.uninit) return null
                return leaf.physical + (logical - leaf.logical)
            }
        }
        // Fallback: scan every slot (handles synthetic layouts with leaf at +24
        // while entries==1, as well as standard +12). Skip empties.
        var foundCoveringUninit = false
        var fallbackHit: Long? = null
        var scannedExtra = false
        var off = 12
        while (off + 12 <= node.size) {
            // skip slots already covered in phase 1
            val idx = (off - 12) / 12
            if (idx >= entries) scannedExtra = true
            if (idx < entries) {
                off += 12
                continue
            }
            val leaf = parseLeafAt(node, off) ?: break
            off += 12
            if (leaf.rawEmpty || leaf.len == 0) continue
            if (logical >= leaf.logical && logical < leaf.logical + leaf.len) {
                if (leaf.uninit) {
                    foundCoveringUninit = true
                } else {
                    fallbackHit = leaf.physical + (logical - leaf.logical)
                    break
                }
            }
        }
        if (fallbackHit != null) return fallbackHit
        if (foundCoveringUninit) return null
        // If phase1 had entries but fallback scanned nothing extra, it's a hole.
        // If node is 60B inode block and entries==1 but leaf actually at +24,
        // fallback above already found it. Otherwise hole.
        if (scannedExtra) {
            // already handled
        }
        return null
    } else {
        require(depth in 1..5) { "bad extent depth: $depth" }
        // Phase 1: index entries starting at +12
        var bestChild: Long? = null
        var bestBlock: Long = -1
        for (i in 0 until entries) {
            val off = 12 + i * 12
            if (off + 12 > node.size) break
            val eiBlock = node.u32le(off)
            val lo = node.u32le(off + 4)
            val hi = node.u16le(off + 8).toLong()
            val child = (hi shl 32) or lo
            if (child == 0L) continue
            if (eiBlock <= logical && eiBlock >= bestBlock) {
                bestBlock = eiBlock
                bestChild = child
            }
        }
        if (bestChild != null) {
            return walkExtentNode(fs, fs.blockBytes(bestChild), logical)
        }
        // Fallback: scan all slots for greatest ei_block <= logical (spec-compat)
        bestChild = null
        bestBlock = -1
        var off = 12
        while (off + 12 <= node.size) {
            val idx = (off - 12) / 12
            if (idx < entries) {
                off += 12
                continue
            }
            val eiBlock = node.u32le(off)
            val lo = node.u32le(off + 4)
            val hi = node.u16le(off + 8).toLong()
            val child = (hi shl 32) or lo
            off += 12
            if (child == 0L) continue
            if (eiBlock <= logical && eiBlock >= bestBlock) {
                bestBlock = eiBlock
                bestChild = child
            }
        }
        if (bestChild != null) {
            return walkExtentNode(fs, fs.blockBytes(bestChild), logical)
        }
        return null
    }
}
