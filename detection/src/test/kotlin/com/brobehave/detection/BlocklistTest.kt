package com.brobehave.detection

import com.brobehave.detection.engine.DnsMessage
import com.brobehave.detection.engine.DomainMatcher
import com.brobehave.detection.engine.UpstreamResolver
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundledBlocklistTest {
    /** Unit tests run with the module directory as the working directory. */
    private val asset = File("src/main/assets/blocklist.bin")

    @Test
    fun `the bundled list is valid, large, and hashed the same way as the Kotlin matcher`() {
        val matcher = DomainMatcher.fromBytes(asset.readBytes())

        assertNotNull(matcher, "blocklist.bin must parse")
        assertTrue(matcher!!.size > 50_000, "expected a full list, got ${matcher.size}")
        // tools/build_blocklist.py adds this canary; matching it proves both sides hash identically.
        assertTrue(matcher.matches("www.blocklist-canary.brobehave.invalid"))
        assertFalse(matcher.matches("example.com"))
        assertFalse(matcher.matches("wikipedia.org"))
    }
}

class BlocklistVerifierTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val list = DomainMatcher.toBytes(listOf("a.example", "b.example"))

    private fun sign(bytes: ByteArray, with: java.security.PrivateKey = keys.private): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(with)
                update(bytes)
                sign()
            },
        )

    private fun manifest(bytes: ByteArray = list, version: Long = 2, signature: String = sign(bytes)) =
        BlocklistManifest(version, bytes.size, BlocklistVerifier.sha256Hex(bytes), signature)

    @Test
    fun `a newer, correctly signed list is accepted`() {
        assertTrue(BlocklistVerifier.accept(manifest(), list, currentVersion = 1, publicKeyBase64 = publicKey))
    }

    @Test
    fun `old or equal versions are ignored`() {
        assertFalse(
            BlocklistVerifier.accept(manifest(version = 2), list, currentVersion = 2, publicKeyBase64 = publicKey),
        )
        assertFalse(
            BlocklistVerifier.accept(manifest(version = 1), list, currentVersion = 2, publicKeyBase64 = publicKey),
        )
    }

    @Test
    fun `a list signed with someone else's key is rejected even if the hash matches`() {
        val attacker = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val forged = manifest(signature = sign(list, attacker.private))

        assertFalse(BlocklistVerifier.accept(forged, list, currentVersion = 1, publicKeyBase64 = publicKey))
    }

    @Test
    fun `tampered bytes, wrong size and garbage signatures are rejected`() {
        val tampered = list.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(BlocklistVerifier.accept(manifest(), tampered, 1, publicKey), "hash mismatch")
        assertFalse(
            BlocklistVerifier.accept(manifest().copy(size = list.size + 1), list, 1, publicKey),
            "size mismatch",
        )
        assertFalse(BlocklistVerifier.accept(manifest(signature = "not base64!!"), list, 1, publicKey))
        assertFalse(BlocklistVerifier.accept(manifest(signature = ""), list, 1, publicKey))
        assertFalse(BlocklistVerifier.accept(manifest(), list, 1, publicKeyBase64 = "AAAA"), "bad public key")
    }

    @Test
    fun `a correctly signed file that is not a blocklist is rejected`() {
        val junk = ByteArray(64) { it.toByte() }

        assertFalse(BlocklistVerifier.accept(manifest(junk), junk, currentVersion = 1, publicKeyBase64 = publicKey))
    }
}

/** A tiny local DNS server so the resolver is tested over real sockets. */
class UpstreamResolverTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val query = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'a'.code.toByte(), 0, 0, 1, 0, 1)

    private fun response(truncated: Boolean, extra: Int = 0): ByteArray =
        query.copyOf(query.size + extra).also { it[2] = if (truncated) 0x83.toByte() else 0x81.toByte() }

    /** Answers one UDP query on a free port; returns the port. */
    private fun udpServer(reply: ByteArray, onQuery: (ByteArray) -> Unit = {}): DatagramSocket {
        val socket = DatagramSocket(0, loopback)
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching {
                socket.receive(packet)
                onQuery(buffer.copyOf(packet.length))
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
        return socket
    }

    @Test
    fun `the query is forwarded unchanged and the answer returned unchanged`() = runTest {
        var received: ByteArray? = null
        val answer = response(truncated = false, extra = 16)
        udpServer(answer) { received = it }.use { server ->
            val resolver = UpstreamResolver({ listOf(loopback) }, maxAnswerBytes = 4096, port = server.localPort)

            assertArrayEquals(answer, resolver.resolve(query))
            assertArrayEquals(query, received)
        }
    }

    @Test
    fun `a truncated answer is completed over TCP`() = runTest {
        val full = response(truncated = false, extra = 900)
        val tcp = ServerSocket(0, 1, loopback)
        // The UDP server must listen on the same port number as the TCP server.
        val udp = DatagramSocket(tcp.localPort, loopback)
        thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val cut = response(truncated = true)
                udp.send(DatagramPacket(cut, cut.size, packet.address, packet.port))
                tcp.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    ByteArray(input.readUnsignedShort()).also(input::readFully)
                    DataOutputStream(client.getOutputStream()).apply {
                        writeShort(full.size)
                        write(full)
                        flush()
                    }
                }
            }
        }
        try {
            val resolver = UpstreamResolver({ listOf(loopback) }, maxAnswerBytes = 4096, port = tcp.localPort)
            val answer = resolver.resolve(query)

            assertArrayEquals(full, answer)
            assertFalse(DnsMessage.isTruncatedResponse(answer!!))
        } finally {
            udp.close()
            tcp.close()
        }
    }

    @Test
    fun `when TCP is refused the truncated answer is passed on as it is`() = runTest {
        val cut = response(truncated = true)
        udpServer(cut).use { server ->
            val resolver = UpstreamResolver(
                { listOf(loopback) },
                maxAnswerBytes = 4096,
                port = server.localPort,
                timeoutMillis = 500,
            )

            assertArrayEquals(cut, resolver.resolve(query))
        }
    }

    @Test
    fun `no reply and no servers both give null instead of hanging`() = runTest {
        DatagramSocket(0, loopback).use { silent ->
            val resolver = UpstreamResolver(
                { listOf(loopback) },
                maxAnswerBytes = 4096,
                port = silent.localPort,
                timeoutMillis = 300,
            )
            assertNull(resolver.resolve(query))
        }
        assertNull(UpstreamResolver({ emptyList() }, maxAnswerBytes = 4096).resolve(query))
        assertEquals(19, query.size)
    }
}
