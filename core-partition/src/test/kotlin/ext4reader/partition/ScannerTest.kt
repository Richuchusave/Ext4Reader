package ext4reader.partition

import ext4reader.blocks.ByteArrayBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {

    @Test
    fun wholeDiskAlwaysPresent() {
        val ss = 512
        val sectors = 100
        val img = ByteArray(sectors * ss)
        val dev = ByteArrayBlockDevice(img, ss)
        val list = collectCandidates(dev)
        val whole = list.filter { it.typeHint == "whole-disk" }
        assertTrue("whole-disk must always be present, got: $list", whole.isNotEmpty())
        val w = whole[0]
        assertEquals(100, w.index)
        assertEquals(0L, w.startLba)
        assertEquals((sectors - 1).toLong(), w.endLbaInclusive)
    }

    @Test
    fun wholeDiskPresentEvenWithMbr() {
        val ss = 512
        val sectors = 50
        val img = ByteArray(sectors * ss)
        // minimal MBR: boot sig + one Linux entry
        img[510] = 0x55.toByte()
        img[511] = 0xAA.toByte()
        val o = 0x1BE
        img[o + 4] = 0x83.toByte()
        // start=1, size=10 little endian
        img[o + 8] = 1
        img[o + 12] = 10
        val dev = ByteArrayBlockDevice(img, ss)
        val list = collectCandidates(dev)
        assertTrue("whole-disk must be present even with MBR", list.any { it.typeHint == "whole-disk" })
    }
}
