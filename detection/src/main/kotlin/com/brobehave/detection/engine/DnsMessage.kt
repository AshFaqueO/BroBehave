package com.brobehave.detection.engine

/**
 * Just enough DNS (RFC 1035) to read the name being asked for. Every function is total:
 * malformed or hostile input yields null/false, never an exception, because the bytes
 * come straight from the network.
 *
 * Names read here are only ever compared against the blocklist and then dropped. They
 * must never be stored or logged.
 */
object DnsMessage {
    private const val HEADER_LENGTH = 12
    private const val FLAGS_OFFSET = 2
    private const val QDCOUNT_OFFSET = 4
    private const val QR_RESPONSE_BIT = 0x80
    private const val TRUNCATED_BIT = 0x02
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 253
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val UPPER_TO_LOWER = 32

    /**
     * The first question's name in lowercase without a trailing dot (e.g. "www.example.com"),
     * or null when [payload] is not a well-formed DNS query.
     */
    fun questionName(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): String? {
        val end = offset + length
        val wellFormedQuery = offset >= 0 &&
            length >= HEADER_LENGTH &&
            end <= payload.size &&
            payload[offset + FLAGS_OFFSET].toInt() and QR_RESPONSE_BIT == 0 &&
            readUnsignedShort(payload, offset + QDCOUNT_OFFSET) >= 1
        return if (wellFormedQuery) readName(payload, offset + HEADER_LENGTH, end) else null
    }

    /** True when a response was cut short and the full answer needs TCP (the TC bit). */
    fun isTruncatedResponse(payload: ByteArray, length: Int = payload.size): Boolean = length >= HEADER_LENGTH &&
        length <= payload.size &&
        payload[FLAGS_OFFSET].toInt() and QR_RESPONSE_BIT != 0 &&
        payload[FLAGS_OFFSET].toInt() and TRUNCATED_BIT != 0

    private fun readName(payload: ByteArray, start: Int, end: Int): String? {
        val name = StringBuilder()
        var position = start
        while (true) {
            if (position >= end) return null
            val labelLength = payload[position].toInt() and BYTE_MASK
            position++
            if (labelLength == 0) break
            // Compression pointers (top bits set) never appear in a query's question.
            val fits = labelLength <= MAX_LABEL_LENGTH && position + labelLength <= end
            if (!fits || !appendLabel(name, payload, position, labelLength)) return null
            position += labelLength
        }
        return name.toString().takeIf { it.isNotEmpty() }
    }

    private fun appendLabel(name: StringBuilder, payload: ByteArray, start: Int, labelLength: Int): Boolean {
        if (name.isNotEmpty()) name.append('.')
        for (i in start until start + labelLength) {
            val c = payload[i].toInt() and BYTE_MASK
            // Hostnames are printable ASCII without dots; anything else is not a site name.
            if (c <= ' '.code || c >= DEL || c == '.'.code) return false
            name.append((if (c in 'A'.code..'Z'.code) c + UPPER_TO_LOWER else c).toChar())
        }
        return name.length <= MAX_NAME_LENGTH
    }

    private const val DEL = 0x7F

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and BYTE_MASK) shl BYTE_BITS) or (bytes[offset + 1].toInt() and BYTE_MASK)
}
