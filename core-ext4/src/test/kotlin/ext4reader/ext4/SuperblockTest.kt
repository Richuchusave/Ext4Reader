package ext4reader.ext4

import ext4reader.blocks.ByteArrayBlockDevice
import ext4reader.blocks.putU16le
import ext4reader.blocks.putU32le
import org.junit.Assert.*
import org.junit.Test

class SuperblockTest {

    private fun craftValid(offset: Int = 1024): ByteArray {
        val img = ByteArray(8192)
        putU32le(img, offset + 0, 8192) // s_inodes_count
        putU32le(img, offset + 4, 100000) // s_blocks_count_lo
        putU32le(img, offset + 20, 0) // s_first_data_block (4k blocks)
        putU32le(img, offset + 24, 2) // s_log_block_size -> 4096
        putU32le(img, offset + 32, 32768) // s_blocks_per_group
        putU32le(img, offset + 40, 8192) // s_inodes_per_group
        putU32le(img, offset + 84, 11) // s_first_ino
        putU16le(img, offset + 88, 256) // s_inode_size
        putU32le(img, offset + 96, 0x40L or 0x80L) // incompat: extents + 64bit
        putU32le(img, offset + 100, 0) // ro_compat
        putU32le(img, offset + 336, 1) // s_blocks_count_hi
        putU16le(img, offset + 0x38, 0xEF53)
        return img
    }

    @Test
    fun craft1024EF53True() {
        val img = craftValid()
        val dev = ByteArrayBlockDevice(img, 512)
        val sb = parseSuperblock(dev, 0)
        assertEquals(4096, sb.blockSize)
        assertEquals(32768L, sb.blocksPerGroup)
        assertEquals(8192L, sb.inodesPerGroup)
        assertEquals(8192L, sb.inodeCount)
        assertEquals(256, sb.inodeSize)
        assertEquals(0L, sb.firstDataBlock)
        assertTrue(sb.is64bit)
        assertTrue(sb.usesExtents)
        assertFalse(sb.hasInlineData)
        assertEquals((1L shl 32) or 100000L, sb.blocksCount)
        assertEquals(11L, sb.firstIno)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badMagicThrows() {
        val img = craftValid()
        putU16le(img, 1024 + 0x38, 0x1234)
        val dev = ByteArrayBlockDevice(img, 512)
        parseSuperblock(dev, 0)
    }

    @Test
    fun defaultsAndInlineFlag() {
        val img = ByteArray(8192)
        val off = 1024
        putU32le(img, off + 0, 128)
        putU32le(img, off + 4, 1024)
        putU32le(img, off + 20, 1)
        putU32le(img, off + 24, 0) // 1024
        putU32le(img, off + 32, 8192)
        putU32le(img, off + 40, 128)
        // inode_size left 0 -> default 128
        putU32le(img, off + 96, 0x8000) // inline_data
        putU16le(img, off + 0x38, 0xEF53)
        val dev = ByteArrayBlockDevice(img, 512)
        val sb = parseSuperblock(dev, 0)
        assertEquals(1024, sb.blockSize)
        assertEquals(128, sb.inodeSize)
        assertTrue(sb.hasInlineData)
        assertFalse(sb.is64bit)
        assertEquals(1024L, sb.blocksCount)
    }
}
