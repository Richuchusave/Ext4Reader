package ext4reader.partition

import ext4reader.blocks.ByteArrayBlockDevice
import ext4reader.blocks.putU16le
import ext4reader.blocks.putU32le
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProberTest {

    private fun plantSuperblock(img: ByteArray, sbOff: Int, logBlock: Long = 2) {
        putU32le(img, sbOff + 0, 1024)
        putU32le(img, sbOff + 24, logBlock)
        putU32le(img, sbOff + 32, 8192)
        putU32le(img, sbOff + 40, 2048)
        putU16le(img, sbOff + 0x38, 0xEF53)
    }

    @Test
    fun validSuperblockIsDetected() {
        val sectors = 64
        val ss = 512
        val img = ByteArray(sectors * ss)
        val startLba = 4L
        val sbOff = (startLba * ss + 1024).toInt()
        plantSuperblock(img, sbOff, 2)
        val dev = ByteArrayBlockDevice(img, ss)
        val c = PartitionCandidate(0, startLba, startLba + 20, "MBR:0x83", "test")
        val r = probeExt4(dev, c)
        assertTrue("expected ext4 true, got: " + r.detail, r.isExt4)
        assertTrue(r.detail.contains("EF53"))
    }

    @Test
    fun randomBytesIsNotExt4() {
        val sectors = 64
        val ss = 512
        val img = ByteArray(sectors * ss)
        val rnd = java.util.Random(42)
        rnd.nextBytes(img)
        // force bad magic deterministically
        img[1024 + 0x38] = 0x12
        img[1024 + 0x38 + 1] = 0x34
        val dev = ByteArrayBlockDevice(img, ss)
        val c = PartitionCandidate(0, 0, 10, "whole-disk", "whole-disk")
        val r = probeExt4(dev, c)
        assertFalse("random bytes should not probe as ext4: " + r.detail, r.isExt4)
    }

    @Test
    fun ntfsHintWithEf53StillTrue() {
        val sectors = 64
        val ss = 512
        val img = ByteArray(sectors * ss)
        val startLba = 8L
        val sbOff = (startLba * ss + 1024).toInt()
        plantSuperblock(img, sbOff, 1)
        val dev = ByteArrayBlockDevice(img, ss)
        // NTFS GPT type GUID hex (EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 LE bytes example)
        // Prober must NOT trust GUID: EF53 still means true.
        val ntfsHint = "GPT:ebd0a0a2b9e5443387c068b6b72699c7"
        val c = PartitionCandidate(10, startLba, startLba + 30, ntfsHint, "gpt-p10")
        val r = probeExt4(dev, c)
        assertTrue("NTFS hint with EF53 must still probe true, got: " + r.detail, r.isExt4)
    }
}
