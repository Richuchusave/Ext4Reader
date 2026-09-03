package ext4reader.blocks

fun ByteArray.u16le(o: Int): Int =
    (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

fun ByteArray.u32le(o: Int): Long =
    (this[o].toLong() and 0xFF) or
        ((this[o + 1].toLong() and 0xFF) shl 8) or
        ((this[o + 2].toLong() and 0xFF) shl 16) or
        ((this[o + 3].toLong() and 0xFF) shl 24)

fun ByteArray.u64le(o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((this[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}

fun ByteArray.i32le(o: Int): Int = u32le(o).toInt()

fun putU16le(a: ByteArray, o: Int, v: Int) {
    a[o] = (v and 0xFF).toByte()
    a[o + 1] = ((v ushr 8) and 0xFF).toByte()
}

fun putU32le(a: ByteArray, o: Int, v: Long) {
    for (i in 0 until 4) a[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
}

fun putU64le(a: ByteArray, o: Int, v: Long) {
    for (i in 0 until 8) a[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
}
