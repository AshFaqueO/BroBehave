package com.brobehave.detection.engine

/** A UDP datagram found inside an IPv4 packet read from the tunnel. */
class UdpDatagram(
    val sourceAddress: ByteArray,
    val sourcePort: Int,
    val destinationAddress: ByteArray,
    val destinationPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/** The parts of a TCP connection attempt needed to refuse it. */
class TcpSyn(
    val sourceAddress: ByteArray,
    val sourcePort: Int,
    val destinationAddress: ByteArray,
    val destinationPort: Int,
    val sequenceNumber: Long,
)

/**
 * Minimal IPv4 packet reading and writing for the DNS-only tunnel. Parsing is total:
 * anything unexpected returns null. Only the virtual DNS address is routed into the
 * tunnel, so nothing but DNS traffic should ever arrive here.
 */
@Suppress("MagicNumber") // Field offsets and sizes of the IPv4, UDP and TCP wire formats (RFC 791/768/793).
object IpPackets {
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17
    private const val IPV4_MIN_HEADER = 20
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20
    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_RST = 0x04
    private const val TCP_FLAG_ACK = 0x10
    private const val DEFAULT_TTL = 64

    /** Protocol number of a valid IPv4 packet, or null. */
    private fun ipv4HeaderLength(packet: ByteArray, length: Int): Int? {
        if (length < IPV4_MIN_HEADER || length > packet.size) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        val totalLength = u16(packet, 2)
        val fragmented = u16(packet, 6) and 0x3FFF != 0
        val valid =
            version == 4 && headerLength >= IPV4_MIN_HEADER && totalLength in headerLength..length && !fragmented
        return headerLength.takeIf { valid }
    }

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        val headerLength = ipv4HeaderLength(packet, length) ?: return null
        val totalLength = u16(packet, 2)
        val isUdp = (packet[9].toInt() and 0xFF) == PROTOCOL_UDP && totalLength >= headerLength + UDP_HEADER
        if (!isUdp) return null
        val udpLength = u16(packet, headerLength + 4)
        if (udpLength < UDP_HEADER || headerLength + udpLength > totalLength) return null
        return UdpDatagram(
            sourceAddress = packet.copyOfRange(12, 16),
            sourcePort = u16(packet, headerLength),
            destinationAddress = packet.copyOfRange(16, 20),
            destinationPort = u16(packet, headerLength + 2),
            payloadOffset = headerLength + UDP_HEADER,
            payloadLength = udpLength - UDP_HEADER,
        )
    }

    /** A TCP connection attempt (SYN without ACK), or null for anything else. */
    fun parseTcpSyn(packet: ByteArray, length: Int): TcpSyn? {
        val headerLength = ipv4HeaderLength(packet, length) ?: return null
        val isTcp = (packet[9].toInt() and 0xFF) == PROTOCOL_TCP && u16(packet, 2) >= headerLength + TCP_HEADER
        if (!isTcp) return null
        val flags = packet[headerLength + 13].toInt()
        if (flags and TCP_FLAG_SYN == 0 || flags and TCP_FLAG_ACK != 0) return null
        return TcpSyn(
            sourceAddress = packet.copyOfRange(12, 16),
            sourcePort = u16(packet, headerLength),
            destinationAddress = packet.copyOfRange(16, 20),
            destinationPort = u16(packet, headerLength + 2),
            sequenceNumber = u32(packet, headerLength + 4),
        )
    }

    /** An IPv4 + UDP packet carrying [payload], with both checksums filled in. */
    fun buildUdp(
        sourceAddress: ByteArray,
        sourcePort: Int,
        destinationAddress: ByteArray,
        destinationPort: Int,
        payload: ByteArray,
        payloadLength: Int = payload.size,
    ): ByteArray {
        val udpLength = UDP_HEADER + payloadLength
        val packet = ipv4Header(PROTOCOL_UDP, sourceAddress, destinationAddress, udpLength)
        putU16(packet, IPV4_MIN_HEADER, sourcePort)
        putU16(packet, IPV4_MIN_HEADER + 2, destinationPort)
        putU16(packet, IPV4_MIN_HEADER + 4, udpLength)
        System.arraycopy(payload, 0, packet, IPV4_MIN_HEADER + UDP_HEADER, payloadLength)
        val checksum = transportChecksum(packet, PROTOCOL_UDP, udpLength)
        // A computed checksum of zero is sent as all ones (zero means "no checksum" in UDP).
        putU16(packet, IPV4_MIN_HEADER + 6, if (checksum == 0) 0xFFFF else checksum)
        return packet
    }

    /**
     * The reply that refuses a TCP connection attempt (RST + ACK). The tunnel has no TCP
     * stack, so DNS-over-TCP and DNS-over-TLS attempts to the virtual server are refused
     * at once and the system resolver falls back to UDP instead of waiting for a timeout.
     */
    fun buildTcpReset(syn: TcpSyn): ByteArray {
        val packet = ipv4Header(PROTOCOL_TCP, syn.destinationAddress, syn.sourceAddress, TCP_HEADER)
        val tcp = IPV4_MIN_HEADER
        putU16(packet, tcp, syn.destinationPort)
        putU16(packet, tcp + 2, syn.sourcePort)
        putU32(packet, tcp + 4, 0)
        putU32(packet, tcp + 8, (syn.sequenceNumber + 1) and 0xFFFFFFFFL)
        packet[tcp + 12] = (5 shl 4).toByte()
        packet[tcp + 13] = (TCP_FLAG_RST or TCP_FLAG_ACK).toByte()
        putU16(packet, tcp + 16, transportChecksum(packet, PROTOCOL_TCP, TCP_HEADER))
        return packet
    }

    private fun ipv4Header(protocol: Int, source: ByteArray, destination: ByteArray, bodyLength: Int): ByteArray {
        val packet = ByteArray(IPV4_MIN_HEADER + bodyLength)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = protocol.toByte()
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        putU16(packet, 10, finish(sum(packet, 0, IPV4_MIN_HEADER)))
        return packet
    }

    /** Checksum over the pseudo-header plus the transport header and data. */
    private fun transportChecksum(packet: ByteArray, protocol: Int, transportLength: Int): Int {
        val pseudo = sum(packet, 12, 8) + protocol + transportLength
        return finish(pseudo + sum(packet, IPV4_MIN_HEADER, transportLength))
    }

    /** Internet checksum (RFC 1071) of a whole IPv4 header or transport segment, for tests. */
    fun verifyIpHeader(packet: ByteArray): Boolean = finish(sum(packet, 0, IPV4_MIN_HEADER)) == 0

    fun verifyTransport(packet: ByteArray, protocol: Int): Boolean =
        transportChecksum(packet, protocol, packet.size - IPV4_MIN_HEADER) == 0

    private fun sum(bytes: ByteArray, offset: Int, length: Int): Long {
        var total = 0L
        var i = offset
        while (i + 1 < offset + length) {
            total += u16(bytes, i)
            i += 2
        }
        if (i < offset + length) total += (bytes[i].toInt() and 0xFF) shl 8
        return total
    }

    private fun finish(total: Long): Int {
        var folded = total
        while (folded shr 16 != 0L) folded = (folded and 0xFFFF) + (folded shr 16)
        return folded.toInt().inv() and 0xFFFF
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (u16(bytes, offset).toLong() shl 16) or u16(bytes, offset + 2).toLong()

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        putU16(bytes, offset, (value shr 16).toInt() and 0xFFFF)
        putU16(bytes, offset + 2, value.toInt() and 0xFFFF)
    }
}
