package ext4reader.ext4

import ext4reader.blocks.putU16le
import ext4reader.blocks.putU32le
import org.junit.Assert.*
import org.junit.Test

class DirReaderTest {

    private fun entry(buf: ByteArray, off: Int, ino: Long, recLen: Int, name: String, fileType: Int): Int {
        putU32le(buf, off, ino)
        putU16le(buf, off + 4, recLen)
        buf[off + 6] = name.length.toByte()
        buf[off + 7] = fileType.toByte()
        for (i in name.indices) buf[off + 8 + i] = name[i].code.toByte()
        return off + recLen
    }

    @Test
    fun parseDirBlockTest() {
        val block = ByteArray(64)
        var o = 0
        o = entry(block, o, 2, 12, ".", 2)
        entry(block, o, 15, 24, "Downloads", 2)
        val list = parseDirBlock(block)
        assertEquals(2, list.size)
        assertEquals(".", list[0].name)
        assertEquals(2L, list[0].inode)
        assertEquals("Downloads", list[1].name)
        assertEquals(15L, list[1].inode)
    }

    @Test
    fun skipsZeroInodeAndBreaksOnBadRecLen() {
        val block = ByteArray(32)
        var o = 0
        o = entry(block, o, 0, 12, "x", 1) // should be skipped
        o = entry(block, o, 7, 12, "a", 1)
        // trailing rec_len < 8 -> break; fill zeros already
        val list = parseDirBlock(block)
        assertEquals(1, list.size)
        assertEquals("a", list[0].name)
        assertEquals(7L, list[0].inode)
    }
}
