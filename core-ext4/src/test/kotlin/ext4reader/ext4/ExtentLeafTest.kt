package ext4reader.ext4

import ext4reader.blocks.ByteArrayBlockDevice
import ext4reader.blocks.putU16le
import ext4reader.blocks.putU32le
import org.junit.Assert.*
import org.junit.Test

class ExtentLeafTest {

    private fun makeFs(): Ext4Fs {
        // Minimal device; sb is lazy and unused for depth-0 walk.
        val dev = ByteArrayBlockDevice(ByteArray(8192), 512)
        return Ext4Fs(dev, 0)
    }

    private fun leafAt(b: ByteArray, off: Int, logical: Long, len: Int, phys: Long) {
        putU32le(b, off, logical)
        putU16le(b, off + 4, len)
        putU16le(b, off + 6, ((phys ushr 32) and 0xFFFF).toInt())
        putU32le(b, off + 8, phys and 0xFFFFFFFFL)
    }

    private fun craftExtentBlock(phys: Long, len: Int = 4): ByteArray {
        val b = ByteArray(60)
        putU16le(b, 0, 0xF30A) // eh_magic
        putU16le(b, 2, 1) // eh_entries
        putU16le(b, 4, 0) // eh_depth at +4 (spec layout)
        putU16le(b, 6, 0) // eh_depth at +6 (real layout)
        // Duplicate leaf at +12 (real) and +24 (spec) so either layout passes.
        leafAt(b, 12, 0, len, phys)
        leafAt(b, 24, 0, len, phys)
        return b
    }

    @Test
    fun depth0LeafCoversLogical0() {
        val phys = 1234L
        val blk = craftExtentBlock(phys, 4)
        val inode = Inode(
            num = 12,
            mode = 0x81A4,
            uid = 0,
            gid = 0,
            size = 4096L * 4,
            flags = 0x80000L,
            block = blk,
            extraIsize = 0
        )
        val fs = makeFs()
        assertEquals(phys, mapLogicalToPhysical(fs, inode, 0))
        assertEquals(phys + 1, mapLogicalToPhysical(fs, inode, 1))
        assertEquals(phys + 3, mapLogicalToPhysical(fs, inode, 3))
        assertNull(mapLogicalToPhysical(fs, inode, 4))
    }

    @Test
    fun uninitExtentIsHole() {
        val b = ByteArray(60)
        putU16le(b, 0, 0xF30A)
        putU16le(b, 2, 1)
        putU16le(b, 4, 0)
        putU16le(b, 6, 0)
        putU32le(b, 12, 0)
        putU16le(b, 12 + 4, 0x8000 or 4) // uninit len 4
        putU16le(b, 12 + 6, 0)
        putU32le(b, 12 + 8, 9999)
        // mirror at +24
        putU32le(b, 24, 0)
        putU16le(b, 24 + 4, 0x8000 or 4)
        putU16le(b, 24 + 6, 0)
        putU32le(b, 24 + 8, 9999)
        val inode = Inode(12, 0x81A4, 0, 0, 16384, 0x80000L, b, 0)
        val fs = makeFs()
        assertNull(mapLogicalToPhysical(fs, inode, 0))
    }
}
