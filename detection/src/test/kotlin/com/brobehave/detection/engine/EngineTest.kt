package com.brobehave.detection.engine

import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Builds a DNS query for [name] (RFC 1035): header + one question. */
private fun dnsQuery(name: String, flags: Int = 0x0100, questions: Int = 1): ByteArray {
    val out = mutableListOf<Byte>()
    fun u16(v: Int) {
        out += (v shr 8).toByte()
        out += v.toByte()
    }
    u16(0x1234)
    u16(flags)
    u16(questions)
    repeat(3) { u16(0) }
    name.split('.').filter { it.isNotEmpty() }.forEach { label ->
        out += label.length.toByte()
        label.forEach { out += it.code.toByte() }
    }
    out += 0
    u16(1)
    u16(1)
    return out.toByteArray()
}

class DnsMessageTest {
    @Test
    fun `reads the question name in lowercase without a trailing dot`() {
        assertEquals("www.example.com", DnsMessage.questionName(dnsQuery("WWW.Example.COM")))
        assertEquals("a.b.c.d.example.org", DnsMessage.questionName(dnsQuery("a.b.c.d.example.org.")))
    }

    @Test
    fun `reads a query embedded at an offset`() {
        val query = dnsQuery("example.com")
        val padded = ByteArray(28) + query + ByteArray(5)

        assertEquals("example.com", DnsMessage.questionName(padded, offset = 28, length = query.size))
    }

    @Test
    fun `responses, empty questions and the root name are not site names`() {
        assertNull(DnsMessage.questionName(dnsQuery("example.com", flags = 0x8180)))
        assertNull(DnsMessage.questionName(dnsQuery("example.com", questions = 0)))
        assertNull(DnsMessage.questionName(dnsQuery("")))
    }

    @Test
    fun `truncated, oversized and malformed queries are rejected`() {
        val query = dnsQuery("www.example.com")
        for (cut in 0 until query.size - 4) {
            assertNull(DnsMessage.questionName(query.copyOf(cut)), "cut at $cut")
        }

        val pointer = dnsQuery("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.questionName(pointer), "compression pointer in a question")

        val longLabel = dnsQuery("example.com").also { it[12] = 64 }
        assertNull(DnsMessage.questionName(longLabel), "label longer than 63")

        val tooLong = dnsQuery((1..60).joinToString(".") { "abcde" })
        assertNull(DnsMessage.questionName(tooLong), "name longer than 253")

        val control = dnsQuery("exa mple.com")
        assertNull(DnsMessage.questionName(control), "space in a label")

        assertNull(DnsMessage.questionName(query, offset = -1, length = query.size))
        assertNull(DnsMessage.questionName(query, offset = 0, length = query.size + 1))
    }

    @Test
    fun `fuzzing with random and mutated packets never throws`() {
        val random = Random(20261112)
        val valid = dnsQuery("video.example.com")
        repeat(20_000) {
            val bytes = if (it % 2 == 0) {
                ByteArray(random.nextInt(0, 80)).also(random::nextBytes)
            } else {
                valid.copyOf().also { copy ->
                    repeat(random.nextInt(1, 6)) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
                }.copyOf(random.nextInt(0, valid.size + 8))
            }
            val name = DnsMessage.questionName(bytes)
            if (name != null) {
                assertTrue(name.length in 1..253)
                assertEquals(name.lowercase(), name)
            }
            DnsMessage.isTruncatedResponse(bytes)
            IpPackets.parseUdp(bytes, bytes.size)
            IpPackets.parseTcpSyn(bytes, bytes.size)
        }
    }

    @Test
    fun `truncation is read from responses only`() {
        assertTrue(DnsMessage.isTruncatedResponse(dnsQuery("example.com", flags = 0x8380)))
        assertFalse(DnsMessage.isTruncatedResponse(dnsQuery("example.com", flags = 0x8180)))
        assertFalse(DnsMessage.isTruncatedResponse(dnsQuery("example.com", flags = 0x0300)))
        assertFalse(DnsMessage.isTruncatedResponse(ByteArray(3)))
    }
}

class IpPacketsTest {
    private val client = byteArrayOf(10, 111, 222.toByte(), 1)
    private val server = byteArrayOf(10, 111, 222.toByte(), 2)

    @Test
    fun `a built UDP packet parses back and has valid checksums`() {
        val payload = dnsQuery("example.com")
        val packet = IpPackets.buildUdp(client, 40_000, server, 53, payload)

        assertTrue(IpPackets.verifyIpHeader(packet))
        assertTrue(IpPackets.verifyTransport(packet, IpPackets.PROTOCOL_UDP))
        val parsed = IpPackets.parseUdp(packet, packet.size)
        assertNotNull(parsed)
        assertArrayEquals(client, parsed!!.sourceAddress)
        assertArrayEquals(server, parsed.destinationAddress)
        assertEquals(40_000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertEquals("example.com", DnsMessage.questionName(packet, parsed.payloadOffset, parsed.payloadLength))
    }

    @Test
    fun `odd-length payloads still checksum correctly`() {
        val packet = IpPackets.buildUdp(client, 1234, server, 53, byteArrayOf(1, 2, 3))

        assertTrue(IpPackets.verifyTransport(packet, IpPackets.PROTOCOL_UDP))
    }

    @Test
    fun `non-UDP, fragmented, IPv6 and short packets are ignored`() {
        val packet = IpPackets.buildUdp(client, 40_000, server, 53, dnsQuery("example.com"))

        assertNull(IpPackets.parseUdp(packet.copyOf().also { it[9] = 6 }, packet.size), "TCP")
        assertNull(IpPackets.parseUdp(packet.copyOf().also { it[0] = 0x65 }, packet.size), "IPv6 version nibble")
        assertNull(IpPackets.parseUdp(packet.copyOf().also { it[6] = 0x20 }, packet.size), "fragment")
        assertNull(IpPackets.parseUdp(packet, 19), "shorter than a header")
        assertNull(IpPackets.parseUdp(packet, packet.size - 1), "shorter than its stated length")
    }

    @Test
    fun `a TCP connection attempt is answered with a valid reset`() {
        val syn = ByteArray(40).also {
            it[0] = 0x45
            it[3] = 40
            it[9] = 6
            client.copyInto(it, 12)
            server.copyInto(it, 16)
            it[20] = 0xC3.toByte()
            it[21] = 0x50
            it[23] = 53
            it[24] = 0x01
            it[27] = 0x0A
            it[32] = 0x50
            it[33] = 0x02
        }

        val parsed = IpPackets.parseTcpSyn(syn, syn.size)
        assertNotNull(parsed)
        val reset = IpPackets.buildTcpReset(parsed!!)

        assertTrue(IpPackets.verifyIpHeader(reset))
        assertTrue(IpPackets.verifyTransport(reset, IpPackets.PROTOCOL_TCP))
        assertArrayEquals(server, reset.copyOfRange(12, 16))
        assertArrayEquals(client, reset.copyOfRange(16, 20))
        assertEquals(0x14, reset[33].toInt(), "RST + ACK")
        assertEquals(0x0B, reset[31].toInt(), "acknowledges sequence + 1")
        assertNull(IpPackets.parseTcpSyn(reset, reset.size), "a reset is not a connection attempt")
    }
}

class DomainMatcherTest {
    private val matcher = DomainMatcher.fromDomains(listOf("listed.example", "Sub.Other.Example."))

    @Test
    fun `matches the listed domain and any subdomain of it`() {
        assertTrue(matcher.matches("listed.example"))
        assertTrue(matcher.matches("www.listed.example"))
        assertTrue(matcher.matches("a.b.c.listed.example"))
        assertTrue(matcher.matches("sub.other.example"))
        assertTrue(matcher.matches("deep.sub.other.example"))
    }

    @Test
    fun `ignores case and trailing dots`() {
        assertTrue(matcher.matches("WWW.Listed.EXAMPLE."))
        assertTrue(matcher.matches("  listed.example..  "))
    }

    @Test
    fun `does not match parents, look-alikes or bare top-level domains`() {
        assertFalse(matcher.matches("other.example"), "parent of a listed subdomain")
        assertFalse(matcher.matches("notlisted.example"))
        assertFalse(matcher.matches("listed.example.com"))
        assertFalse(matcher.matches("mylisted.example"))
        assertFalse(matcher.matches("example"))
        assertFalse(matcher.matches(""))
        assertFalse(DomainMatcher.fromDomains(listOf("example")).matches("anything.example"), "TLD alone never matches")
    }

    @Test
    fun `extra domains match like listed ones`() {
        val withExtra = DomainMatcher.fromDomains(emptyList(), extraDomains = listOf("test.invalid"))

        assertTrue(withExtra.matches("x.test.invalid"))
        assertFalse(withExtra.matches("other.invalid"))
    }

    @Test
    fun `the binary format round-trips and rejects damaged data`() {
        val bytes = DomainMatcher.toBytes(listOf("b.example", "a.example", "a.example"))
        val loaded = DomainMatcher.fromBytes(bytes)

        assertNotNull(loaded)
        assertEquals(2, loaded!!.size)
        assertTrue(loaded.matches("www.a.example"))
        assertFalse(loaded.matches("c.example"))

        assertNull(DomainMatcher.fromBytes(bytes.copyOf(bytes.size - 1)), "truncated")
        assertNull(DomainMatcher.fromBytes(bytes.copyOf().also { it[0] = 0 }), "wrong magic")
        assertNull(DomainMatcher.fromBytes(ByteArray(4)), "too short")
        val unsorted = bytes.copyOf().also { copy ->
            val first = copy.copyOfRange(8, 16)
            copy.copyInto(copy, 8, 16, 24)
            first.copyInto(copy, 16)
        }
        assertNull(DomainMatcher.fromBytes(unsorted), "not sorted")
    }
}

class DailyFlaggerTest {
    @Test
    fun `many matches on one day flag once, and a new day flags again`() = runTest {
        var today = LocalDate.of(2026, 11, 12)
        val flagged = mutableListOf<LocalDate>()
        val flagger = DailyFlagger(today = { today }, flag = { flagged += it })

        repeat(50) { flagger.onMatch() }
        assertEquals(listOf(LocalDate.of(2026, 11, 12)), flagged)

        today = today.plusDays(1)
        repeat(3) { flagger.onMatch() }
        assertEquals(2, flagged.size)
    }

    @Test
    fun `a failed flag is retried on the next match`() = runTest {
        var fail = true
        val flagged = mutableListOf<LocalDate>()
        val flagger = DailyFlagger(today = { LocalDate.of(2026, 11, 12) }) {
            if (fail) error("database busy")
            flagged += it
        }

        runCatching { flagger.onMatch() }
        fail = false
        flagger.onMatch()

        assertEquals(1, flagged.size)
    }
}
