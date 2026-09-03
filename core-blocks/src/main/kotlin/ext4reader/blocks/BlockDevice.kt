package ext4reader.blocks

import java.io.Closeable

interface BlockDevice : Closeable {
    val sectorSize: Int
    val sectorCount: Long
    fun readSectors(lba: Long, count: Int, out: ByteArray, outOffset: Int = 0)
    fun readBytes(offset: Long, len: Int): ByteArray
}

abstract class BaseBlockDevice : BlockDevice {
    override fun readBytes(offset: Long, len: Int): ByteArray {
        require(offset >= 0 && len >= 0) { "bad range" }
        val out = ByteArray(len)
        if (len == 0) return out
        val ss = sectorSize
        var remaining = len
        var outPos = 0
        var cur = offset
        val sectorBuf = ByteArray(ss)
        while (remaining > 0) {
            val lba = cur / ss
            val offInSector = (cur % ss).toInt()
            readSectors(lba, 1, sectorBuf, 0)
            val take = minOf(ss - offInSector, remaining)
            System.arraycopy(sectorBuf, offInSector, out, outPos, take)
            outPos += take
            cur += take
            remaining -= take
        }
        return out
    }
}

class ByteArrayBlockDevice(
    private val data: ByteArray,
    override val sectorSize: Int = 512
) : BaseBlockDevice() {
    override val sectorCount: Long get() = data.size.toLong() / sectorSize
    override fun readSectors(lba: Long, count: Int, out: ByteArray, outOffset: Int) {
        if (lba < 0 || count <= 0 || lba + count > sectorCount) throw IllegalArgumentException("lba out of range: $lba+$count")
        System.arraycopy(data, (lba * sectorSize).toInt(), out, outOffset, count * sectorSize)
    }
    override fun close() {}
}

class FileBlockDevice(
    file: java.io.File,
    override val sectorSize: Int = 512
) : BaseBlockDevice() {
    private val raf = java.io.RandomAccessFile(file, "r")
    override val sectorCount: Long get() = raf.length() / sectorSize
    @Synchronized
    override fun readSectors(lba: Long, count: Int, out: ByteArray, outOffset: Int) {
        if (lba < 0 || count <= 0 || lba + count > sectorCount) throw IllegalArgumentException("lba out of range")
        raf.seek(lba * sectorSize)
        raf.readFully(out, outOffset, count * sectorSize)
    }
    override fun close() = raf.close()
}
