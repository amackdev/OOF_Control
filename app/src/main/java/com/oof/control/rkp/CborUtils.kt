package com.oof.control.rkp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal CBOR encoder — covers the subset the RKP protocol needs:
 * integers, byte strings, text strings, arrays, maps, tags, and booleans.
 *
 * No external dependency.  Decoding uses Android's built-in CBOR support
 * would be heavy, so we decode just enough for the server response.
 */
object CborUtils {

    // ---- encoding ----

    fun encodeInt(value: Long): ByteArray {
        return if (value >= 0) encodeUnsigned(0, value)
        else encodeUnsigned(1, -1 - value)
    }

    fun encodeBytes(b: ByteArray): ByteArray =
        encodeUnsigned(2, b.size.toLong()) + b

    fun encodeText(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        return encodeUnsigned(3, utf8.size.toLong()) + utf8
    }

    fun encodeArray(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeUnsigned(4, items.size.toLong()))
        items.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun encodeMap(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeUnsigned(5, entries.size.toLong()))
        entries.forEach { (k, v) -> out.write(k); out.write(v) }
        return out.toByteArray()
    }

    fun encodeTag(tag: Long, content: ByteArray): ByteArray =
        encodeUnsigned(6, tag) + content

    fun encodeBool(v: Boolean): ByteArray =
        byteArrayOf(if (v) 0xf5.toByte() else 0xf4.toByte())

    fun encodeNull(): ByteArray = byteArrayOf(0xf6.toByte())

    // Encode a COSE_Sign1 structure as a CBOR array of 4 items
    fun encodeCoseSign1(protected: ByteArray, unprotected: ByteArray, payload: ByteArray, signature: ByteArray): ByteArray =
        encodeArray(listOf(encodeBytes(protected), unprotected, encodeBytes(payload), encodeBytes(signature)))

    private fun encodeUnsigned(majorType: Int, value: Long): ByteArray {
        val mt = (majorType shl 5).toByte()
        return when {
            value < 24 -> byteArrayOf((mt.toInt() or value.toInt()).toByte())
            value < 0x100 -> byteArrayOf((mt.toInt() or 24).toByte(), value.toByte())
            value < 0x10000 -> {
                val buf = ByteBuffer.allocate(3)
                buf.put((mt.toInt() or 25).toByte())
                buf.putShort(value.toShort())
                buf.array()
            }
            value < 0x100000000L -> {
                val buf = ByteBuffer.allocate(5)
                buf.put((mt.toInt() or 26).toByte())
                buf.putInt(value.toInt())
                buf.array()
            }
            else -> {
                val buf = ByteBuffer.allocate(9)
                buf.put((mt.toInt() or 27).toByte())
                buf.putLong(value)
                buf.array()
            }
        }
    }

    // ---- decoding (just enough for the server response) ----

    class CborReader(private val data: ByteArray) {
        var pos = 0

        fun peek(): Int = data[pos].toInt() and 0xFF

        fun readItem(): Any? {
            val initial = data[pos].toInt() and 0xFF
            val major = initial shr 5
            return when (major) {
                0 -> readUnsigned() // positive int
                1 -> -1L - readUnsigned() // negative int
                2 -> readByteString()
                3 -> readTextString()
                4 -> readArray()
                5 -> readMap()
                6 -> { val tag = readUnsigned(); readItem() } // tag — unwrap
                7 -> readSimple()
                else -> throw IllegalStateException("Unknown CBOR major type $major")
            }
        }

        private fun readUnsigned(): Long {
            val initial = data[pos++].toInt() and 0xFF
            val additional = initial and 0x1F
            return when {
                additional < 24 -> additional.toLong()
                additional == 24 -> (data[pos++].toInt() and 0xFF).toLong()
                additional == 25 -> {
                    val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2; v.toLong()
                }
                additional == 26 -> {
                    val v = ByteBuffer.wrap(data, pos, 4).int.toLong() and 0xFFFFFFFFL
                    pos += 4; v
                }
                additional == 27 -> {
                    val v = ByteBuffer.wrap(data, pos, 8).long
                    pos += 8; v
                }
                else -> throw IllegalStateException("Unsupported additional info $additional")
            }
        }

        private fun readByteString(): ByteArray {
            val initial = data[pos].toInt() and 0xFF
            // don't consume — readUnsigned will
            val len = readUnsigned().toInt()
            val result = data.copyOfRange(pos, pos + len)
            pos += len
            return result
        }

        // re-read the unsigned from same position
        private fun readTextString(): String {
            val len = readUnsigned().toInt()
            val result = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return result
        }

        private fun readArray(): List<Any?> {
            val len = readUnsigned().toInt()
            return (0 until len).map { readItem() }
        }

        private fun readMap(): Map<Any?, Any?> {
            val len = readUnsigned().toInt()
            val m = LinkedHashMap<Any?, Any?>()
            repeat(len) { m[readItem()] = readItem() }
            return m
        }

        private fun readSimple(): Any? {
            val initial = data[pos++].toInt() and 0xFF
            val v = initial and 0x1F
            return when (v) {
                20 -> false
                21 -> true
                22 -> null
                else -> v.toLong()
            }
        }
    }

    fun decode(data: ByteArray): Any? = CborReader(data).readItem()
}
