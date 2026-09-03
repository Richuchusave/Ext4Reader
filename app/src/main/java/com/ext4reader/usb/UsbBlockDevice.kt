package com.ext4reader.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import ext4reader.blocks.BaseBlockDevice
import java.io.IOException

/**
 * USB Mass Storage Bulk-Only Transport (BOT) [ext4reader.blocks.BlockDevice].
 *
 * Speaks SCSI over two bulk endpoints (CBW 0x43425355 / CSW) using only
 * android.hardware.usb bulkTransfer. No external libs.
 *
 * Supported CDBs: INQUIRY, READ CAPACITY(10/16), READ(10/16), REQUEST SENSE.
 */
class UsbBlockDevice(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    override val sectorSize: Int,
    override val sectorCount: Long,
    private val usbInterface: UsbInterface? = null,
) : BaseBlockDevice() {

    private val session = BotSession(connection, bulkIn, bulkOut)

    @Synchronized
    override fun readSectors(lba: Long, count: Int, out: ByteArray, outOffset: Int) {
        require(lba >= 0 && count > 0 && lba + count <= sectorCount) { "lba out of range: $lba+$count" }
        require(outOffset >= 0 && outOffset + count.toLong() * sectorSize <= out.size) { "out overflow" }
        var cur = lba
        var pos = outOffset
        var remaining = count
        val maxPerXfer = maxOf(1, MAX_XFER_BYTES / sectorSize)
        while (remaining > 0) {
            val n = minOf(remaining, maxPerXfer)
            val cdb = if (cur <= 0xFFFF_FFFFL && n <= 0xFFFF) read10(cur, n) else read16(cur, n)
            session.exec(cdb, LUN, out, pos, n * sectorSize, dirIn = true)
            cur += n
            pos += n * sectorSize
            remaining -= n
        }
    }

    /** Sector-aligned multi-block loop (much cheaper than one round-trip per sector). */
    override fun readBytes(offset: Long, len: Int): ByteArray {
        require(offset >= 0 && len >= 0) { "bad range" }
        val out = ByteArray(len)
        if (len == 0) return out
        val ss = sectorSize
        var cur = offset
        var outPos = 0
        var remaining = len
        val maxPerXfer = maxOf(1, MAX_XFER_BYTES / ss)
        while (remaining > 0) {
            val lba = cur / ss
            val offIn = (cur % ss).toInt()
            val take = minOf(remaining, maxPerXfer * ss - offIn)
            val count = (offIn + take + ss - 1) / ss
            val tmp = ByteArray(count * ss)
            readSectors(lba, count, tmp, 0)
            System.arraycopy(tmp, offIn, out, outPos, take)
            outPos += take
            cur += take
            remaining -= take
        }
        return out
    }

    override fun close() {
        usbInterface?.let { iface -> runCatching { connection.releaseInterface(iface) } }
    }

    companion object {
        private const val CBW_SIG = 0x4342_5355L
        private const val CSW_SIG = 0x5342_5355L
        private const val LUN = 0
        private const val TIMEOUT_MS = 15_000
        private const val MAX_XFER_BYTES = 32 * 1024

        /**
         * Finds the first Mass Storage class (8) interface with bulk IN+OUT
         * endpoints, claims it, probes INQUIRY + READ CAPACITY and returns a
         * ready device, or null when unsupported.
         */
        fun claim(device: UsbDevice, connection: UsbDeviceConnection): UsbBlockDevice? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
                }
                if (bulkIn == null || bulkOut == null) continue
                if (!connection.claimInterface(iface, true)) continue
                val session = BotSession(connection, bulkIn, bulkOut)
                try {
                    session.exec(INQUIRY_CDB, LUN, ByteArray(36), 0, 36, dirIn = true)
                    val (count, size) = session.readCapacity()
                    return UsbBlockDevice(connection, bulkIn, bulkOut, size, count, iface)
                } catch (t: Throwable) {
                    runCatching { connection.releaseInterface(iface) }
                }
            }
            return null
        }

        private val INQUIRY_CDB = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        private val RC10_CDB = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        private val SENSE_CDB = byteArrayOf(0x03, 0, 0, 0, 18, 0)

        private fun read10(lba: Long, count: Int): ByteArray = ByteArray(10).apply {
            this[0] = 0x28
            putU32BE(this, 2, lba)
            putU16BE(this, 7, count)
        }

        private fun read16(lba: Long, count: Int): ByteArray = ByteArray(16).apply {
            this[0] = 0x88.toByte()
            putU64BE(this, 2, lba)
            putU32BE(this, 10, count.toLong())
        }

        private fun rc16Cdb(): ByteArray = ByteArray(16).apply {
            this[0] = 0x9E.toByte()
            this[1] = 0x10
            putU32BE(this, 10, 32)
        }
    }

    /** Raw CBW -> data -> CSW session. All calls are synchronized. */
    private class BotSession(
        private val conn: UsbDeviceConnection,
        private val inEp: UsbEndpoint,
        private val outEp: UsbEndpoint,
    ) {
        private var tag = 1

        @Synchronized
        fun exec(
            cdb: ByteArray,
            lun: Int,
            buf: ByteArray?,
            off: Int,
            len: Int,
            dirIn: Boolean,
            senseOnError: Boolean = true,
        ): Int {
            val cbw = ByteArray(31)
            putU32LE(cbw, 0, CBW_SIG)
            val t = tag++
            putU32LE(cbw, 4, t.toLong())
            putU32LE(cbw, 8, len.toLong())
            cbw[12] = (if (dirIn) 0x80 else 0x00).toByte()
            cbw[13] = (lun and 0xFF).toByte()
            cbw[14] = (cdb.size and 0xFF).toByte()
            System.arraycopy(cdb, 0, cbw, 15, cdb.size)
            if (conn.bulkTransfer(outEp, cbw, cbw.size, TIMEOUT_MS) != cbw.size) {
                throw IOException("CBW send failed")
            }
            if (len > 0 && buf != null) {
                if (dirIn) {
                    var got = 0
                    while (got < len) {
                        val r = conn.bulkTransfer(inEp, buf, off + got, len - got, TIMEOUT_MS)
                        if (r < 0) throw IOException("bulk IN failed")
                        got += r
                    }
                } else {
                    val w = conn.bulkTransfer(outEp, buf, off, len, TIMEOUT_MS)
                    if (w != len) throw IOException("bulk OUT short: $w/$len")
                }
            }
            val csw = ByteArray(13)
            var read = 0
            while (read < csw.size) {
                val r = conn.bulkTransfer(inEp, csw, read, csw.size - read, TIMEOUT_MS)
                if (r < 0) throw IOException("CSW read failed")
                read += r
            }
            if (getU32LE(csw, 0) != CSW_SIG || getU32LE(csw, 4) != t.toLong()) {
                throw IOException("bad CSW signature/tag")
            }
            val status = csw[12].toInt() and 0xFF
            if (status != 0) {
                val sense = if (senseOnError) {
                    runCatching {
                        val s = ByteArray(18)
                        exec(SENSE_CDB, lun, s, 0, s.size, dirIn = true, senseOnError = false)
                        s
                    }.getOrDefault(ByteArray(0))
                } else ByteArray(0)
                throw IOException("SCSI status=$status sense=${sense.toHex()}")
            }
            return len
        }

        /** Returns Pair(sectorCount, sectorSize). Falls back to RC16 when needed. */
        fun readCapacity(): Pair<Long, Int> {
            val d10 = ByteArray(8)
            exec(RC10_CDB, LUN, d10, 0, d10.size, dirIn = true)
            var lastLba = getU32BE(d10, 0)
            var blk = getU32BE(d10, 4).toInt()
            if (lastLba == 0xFFFF_FFFFL) {
                val d16 = ByteArray(32)
                exec(rc16Cdb(), LUN, d16, 0, d16.size, dirIn = true)
                lastLba = getU64BE(d16, 0)
                blk = getU32BE(d16, 8).toInt()
            }
            require(blk == 512 || blk == 1024 || blk == 2048 || blk == 4096) { "odd block size: $blk" }
            require(lastLba > 0) { "empty device" }
            return Pair(lastLba + 1, blk)
        }
    }
}

private fun putU16BE(a: ByteArray, o: Int, v: Int) {
    a[o] = ((v ushr 8) and 0xFF).toByte()
    a[o + 1] = (v and 0xFF).toByte()
}

private fun putU32BE(a: ByteArray, o: Int, v: Long) {
    for (i in 0 until 4) a[o + i] = ((v ushr (8 * (3 - i))) and 0xFF).toByte()
}

private fun putU64BE(a: ByteArray, o: Int, v: Long) {
    for (i in 0 until 8) a[o + i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
}

private fun putU32LE(a: ByteArray, o: Int, v: Long) {
    for (i in 0 until 4) a[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
}

private fun getU32LE(a: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = v or ((a[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun getU32BE(a: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = v or ((a[o + i].toLong() and 0xFF) shl (8 * (3 - i)))
    return v
}

private fun getU64BE(a: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((a[o + i].toLong() and 0xFF) shl (8 * (7 - i)))
    return v
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
