package app.franticg33k.patches.atlasphoto.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.atlasphoto.shared.Constants.COMPATIBILITY_ATLASPHOTO

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Byte helpers (local, dependency-free).
// ─────────────────────────────────────────────────────────────────────────────────────────────

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }

private fun ByteArray.indexOfBytes(needle: ByteArray, fromIndex: Int = 0): Int {
    if (needle.isEmpty() || fromIndex < 0) return -1
    var i = fromIndex
    while (i + needle.size <= size) {
        var match = true
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return i
        i++
    }
    return -1
}

private fun ByteArray.matchesAt(offset: Int, needle: ByteArray): Boolean {
    if (offset < 0 || offset + needle.size > size) return false
    for (j in needle.indices) if (this[offset + j] != needle[j]) return false
    return true
}

private fun ByteArray.occurrencesOf(needle: ByteArray): List<Int> {
    val out = mutableListOf<Int>()
    var i = indexOfBytes(needle)
    while (i >= 0) {
        out.add(i)
        i = indexOfBytes(needle, i + 1)
    }
    return out
}

private fun u16le(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun u32le(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Target shape. The setIsProMember setter compiles to (verified in 2.6.2 and 2.7.3):
//
//   GetParentEnvironment r1, 0          34 01 00
//   LoadFromEnvironment r2, r1, 0       3b 02 01 00
//   NewObjectWithBuffer r1, K, V        01 01 KK KK VV VV   <- K,V are build constants that drift
//   LoadParam         r3, 1             89 03 01            <- patch target (stable)
//   PutOwnBySlotIdx   r1, r3, 0         52 01 03 00
//   LoadConstUndefined r0               93 00
//   Call2             r1, r2, r0, r1    6e 01 02 00 01
//   Ret               r0                76
//
// Only the NewObjectWithBuffer operands changed between releases (6291,6647 -> 7211,6999),
// which is why raw 16-byte signatures rot while the surrounding instructions do not.
// ─────────────────────────────────────────────────────────────────────────────────────────────

private val PROLOGUE = hexToBytes("3401003b0201000101") // bytes [0..8]: prefix before the operands
private val LOAD_PARAM = hexToBytes("890301") // bytes [13..15]: the instruction we replace
private val SETTER_TAIL = hexToBytes("5201030093006e0102000176") // bytes [16..27]
private val PATCHED_LOAD = hexToBytes("8b0301") // LoadConstUInt8 r3, 1 (what we write)

// Locator A: full 16-byte signatures verified against extracted bundles, newest first.
// Each signature must occur EXACTLY ONCE; add a new entry per release after confirming it
// with apks/extracted/atlas-analysis/check_sig.mjs + verify_sig.mjs.
private val KNOWN_SIGNATURES = listOf(
    "3401003b02010001012b1c571b890301" to "2.7.3",
    "3401003b02010001019318f719890301" to "2.6.2",
)

private class SetterCandidate(val offset: Int, val keyOperand: Int)

// Locator B scan: every function in the bundle with the setter shape above (operands wild).
// Single pass over the bundle: collect all PROLOGUE hits first, then validate the full shape.
private fun findSetterShapeCandidates(bytes: ByteArray): List<SetterCandidate> {
    val out = mutableListOf<SetterCandidate>()
    for (i in bytes.occurrencesOf(PROLOGUE)) {
        if (bytes.matchesAt(i + 13, LOAD_PARAM) && bytes.matchesAt(i + 16, SETTER_TAIL)) {
            out.add(SetterCandidate(i, u16le(bytes, i + 9)))
        }
    }
    return out
}


// Locator B selection: the paywall store's sibling setters share the exact same shape and are
// emitted back-to-back with strictly consecutive NewObjectWithBuffer key operands (2.7.3
// observed: 7211..7216). Other stores in the bundle reuse the shape but not the tight
// consecutive-key layout. setIsProMember is the first-declared store field, so it carries the
// lowest key operand of its cluster. Require exactly one qualifying cluster; anything else
// fails closed with a candidate dump (see the error text in execute below).
private fun selectIsProMemberFromClusters(candidates: List<SetterCandidate>): Int? {
    if (candidates.isEmpty()) return null
    val sorted = candidates.sortedBy { it.offset }
    val clusters = mutableListOf<MutableList<SetterCandidate>>(mutableListOf(sorted.first()))
    for (c in sorted.drop(1)) {
        val last = clusters.last()
        if (c.offset - last.last().offset < 0x400) last.add(c) else clusters.add(mutableListOf(c))
    }
    val qualifying = clusters.filter { cl ->
        cl.size in 3..16 && cl.zipWithNext().all { (a, b) -> b.keyOperand == a.keyOperand + 1 }
    }
    if (qualifying.size != 1) return null
    return qualifying.single().minByOrNull { it.keyOperand }!!.offset
}

@Suppress("unused")
val unlockAtlasPhotoProPatch = rawResourcePatch(
    name = "Unlock Pro",
    description = "Forces Atlas Photo's Hermes (React Native) JS runtime to treat every " +
        "session as a Pro subscriber by patching the setIsProMember reducer so its isPro " +
        "field is always truthy. Unlocks all Pro features and removes the Pro paywall.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ATLASPHOTO)

    execute {
        val bundleFile = get("assets/index.android.bundle", false)
        val bytes = bundleFile.readBytes()
        val hbcVersion = u32le(bytes, 8)

        fun fail(message: String): Nothing =
            error("Atlas Photo Pro patch: $message [HBC v$hbcVersion, ${bytes.size} bytes]")

        // Idempotency: a patched bundle loads LoadConstUInt8 r3,1 (8b) where the original
        // loads LoadParam r3,1 (89). Detect it instead of reporting a confusing "not found".
        if (bytes.occurrencesOf(PROLOGUE).any { bytes.matchesAt(it + 13, PATCHED_LOAD) }) {
            fail("the bundle appears to be patched already")
        }

        // Locator A - exact per-release signatures. Each must match exactly once; the first
        // one that does wins. This is the fast, precise path for all known releases.
        for ((signature, release) in KNOWN_SIGNATURES) {
            val hits = bytes.occurrencesOf(hexToBytes(signature))
            if (hits.size > 1) fail("signature for $release matches ${hits.size} locations")
            if (hits.size == 1) {
                val target = hits.single() + 13
                bytes[target] = PATCHED_LOAD[0]
                bytes[target + 1] = PATCHED_LOAD[1]
                bytes[target + 2] = PATCHED_LOAD[2]
                bundleFile.writeBytes(bytes)
                return@execute
            }
        }

        // Locator B - structural fallback for future releases: same instruction shape with
        // the drifting NewObjectWithBuffer operands treated as wildcards, then disambiguated
        // by the paywall store's consecutive-key setter cluster (see selectIsProMemberFromClusters).
        val candidates = findSetterShapeCandidates(bytes)
        val chosen = selectIsProMemberFromClusters(candidates)
        if (chosen == null) {
            val dump = candidates.joinToString(", ") {
                "0x${it.offset.toString(16)}(key=${it.keyOperand})"
            }
            fail(
                "setIsProMember could not be located: no known signature matched and the " +
                    "structural fallback found ${candidates.size} setter-shaped candidates " +
                    "without a unique consecutive-key store cluster [$dump]. Disassemble the " +
                    "bundle (docs/atlas-photo-pro-analysis-2.7.3.md, section 12), locate " +
                    "NCFunction<setIsProMember>, and add its 16-byte prologue to KNOWN_SIGNATURES."
            )
        }
        bytes[chosen + 13] = PATCHED_LOAD[0]
        bytes[chosen + 14] = PATCHED_LOAD[1]
        bytes[chosen + 15] = PATCHED_LOAD[2]
        bundleFile.writeBytes(bytes)
    }
}
