package com.brobehave.detection.engine

import java.nio.ByteBuffer
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers "is this site name on the adult-domain list?".
 *
 * The list is a sorted array of 64-bit hashes rather than names: compact (8 bytes per
 * domain), fast (binary search), and the app never carries a readable list of adult sites.
 * A name matches when it, or any parent domain of it, is on the list — so
 * "video.cdn.example.com" matches a listed "example.com". Bare top-level domains never match.
 */
class DomainMatcher(private val sortedHashes: LongArray, extraDomains: Collection<String> = emptyList()) {
    private val extraHashes: Set<Long> = extraDomains.mapTo(mutableSetOf()) { hash(normalize(it)) }

    val size: Int get() = sortedHashes.size + extraHashes.size

    fun matches(name: String): Boolean {
        var candidate = normalize(name)
        while (candidate.contains('.')) {
            val hash = hash(candidate)
            if (sortedHashes.binarySearch(hash) >= 0 || hash in extraHashes) return true
            candidate = candidate.substringAfter('.')
        }
        return false
    }

    companion object {
        private const val MAGIC = 0x42424C31 // "BBL1"
        private const val HEADER_BYTES = 8
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L
        private const val BYTE_MASK = 0xFF

        fun normalize(name: String): String = name.trim().trimEnd('.').lowercase()

        /** FNV-1a 64-bit over the UTF-8 bytes of an already-normalized name. */
        fun hash(normalizedName: String): Long {
            var hash = FNV_OFFSET
            for (byte in normalizedName.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and BYTE_MASK.toLong())
                hash *= FNV_PRIME
            }
            return hash
        }

        fun fromDomains(domains: Collection<String>, extraDomains: Collection<String> = emptyList()): DomainMatcher =
            DomainMatcher(domains.map { hash(normalize(it)) }.distinct().sorted().toLongArray(), extraDomains)

        /**
         * Reads the binary blocklist format: "BBL1", a count, then that many sorted
         * big-endian 64-bit hashes. Returns null when the bytes are not a valid list.
         */
        fun fromBytes(bytes: ByteArray, extraDomains: Collection<String> = emptyList()): DomainMatcher? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            val magic = buffer.int
            val count = buffer.int
            val valid =
                magic == MAGIC && count > 0 && bytes.size.toLong() == HEADER_BYTES + count.toLong() * Long.SIZE_BYTES
            if (!valid) return null
            val hashes = LongArray(count) { buffer.long }
            val sorted = (1 until count).all { hashes[it - 1] < hashes[it] }
            return if (sorted) DomainMatcher(hashes, extraDomains) else null
        }

        /** Writes the binary blocklist format read by [fromBytes]. */
        fun toBytes(domains: Collection<String>): ByteArray {
            val hashes = domains.map { hash(normalize(it)) }.distinct().sorted()
            val buffer = ByteBuffer.allocate(HEADER_BYTES + hashes.size * Long.SIZE_BYTES)
            buffer.putInt(MAGIC).putInt(hashes.size)
            hashes.forEach(buffer::putLong)
            return buffer.array()
        }
    }
}

/**
 * Turns "a listed site was looked up" into at most one flag per local day. The site name
 * is not passed in at all: by the time this is called it has already been discarded.
 */
class DailyFlagger(private val today: () -> LocalDate, private val flag: suspend (LocalDate) -> Unit) {
    private val mutex = Mutex()
    private var lastFlagged: LocalDate? = null

    suspend fun onMatch() {
        mutex.withLock {
            val date = today()
            if (date != lastFlagged) {
                flag(date)
                lastFlagged = date
            }
        }
    }
}
