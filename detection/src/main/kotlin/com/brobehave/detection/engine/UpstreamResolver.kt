package com.brobehave.detection.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a DNS query, byte for byte as the app made it, to the network's own DNS server
 * and returns the server's answer unchanged. Nothing is blocked, rewritten or cached.
 *
 * If the UDP answer is truncated, the same query is repeated over TCP and the complete
 * answer is returned when it fits in [maxAnswerBytes]; otherwise the truncated answer is
 * passed on as it is.
 *
 * @param servers the network's DNS servers, in order of preference.
 * @param prepareUdp / [prepareTcp] called on every new socket before use, so the VPN
 *   service can exempt it from the tunnel and bind it to the real network.
 */
class UpstreamResolver(
    private val servers: () -> List<InetAddress>,
    private val maxAnswerBytes: Int,
    private val port: Int = DNS_PORT,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val prepareUdp: (DatagramSocket) -> Unit = {},
    private val prepareTcp: (Socket) -> Unit = {},
) {
    /** The answer, or null when no server replied in time. */
    suspend fun resolve(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        servers().take(MAX_SERVERS_TRIED).firstNotNullOfOrNull { server ->
            try {
                val answer = exchangeUdp(server, query)
                if (DnsMessage.isTruncatedResponse(answer)) completeOverTcp(server, query) ?: answer else answer
            } catch (_: IOException) {
                null
            }
        }
    }

    private fun exchangeUdp(server: InetAddress, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            prepareUdp(socket)
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(query, query.size, server, port))
            val buffer = ByteArray(MAX_UDP_PAYLOAD)
            val reply = DatagramPacket(buffer, buffer.size)
            // Ignore anything that is not the answer to this query (same id, from the server asked).
            // receive() throws on timeout, which ends the loop.
            while (true) {
                socket.receive(reply)
                val matches = reply.address == server &&
                    reply.length >= ID_BYTES &&
                    buffer[0] == query[0] &&
                    buffer[1] == query[1]
                if (matches) return buffer.copyOf(reply.length)
            }
        }
    }

    /** The full answer over TCP (RFC 1035 §4.2.2: two-byte length prefix), or null. */
    private fun completeOverTcp(server: InetAddress, query: ByteArray): ByteArray? = try {
        Socket().use { socket ->
            prepareTcp(socket)
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(server, port), timeoutMillis)
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length > maxAnswerBytes) null else ByteArray(length).also(input::readFully)
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val DNS_PORT = 53
        const val DEFAULT_TIMEOUT_MILLIS = 4_000
        const val MAX_SERVERS_TRIED = 2
        const val MAX_UDP_PAYLOAD = 65_535
        const val ID_BYTES = 2
    }
}
