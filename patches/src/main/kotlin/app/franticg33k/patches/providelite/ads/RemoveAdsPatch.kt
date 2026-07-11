package app.franticg33k.patches.providelite.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.providelite.shared.Constants.COMPATIBILITY_PROVIDELITE

@Suppress("unused")
val removeProvideliteAdsPatch = rawResourcePatch(
    name = "Remove Ads",
    description = "Blocks all ad display in Provide Lite by forcing the app's central ad gate " +
        "(in libapp.so) to always take its 'skip' path. Two branch instructions are rewritten " +
        "to unconditional skips: the global 'ad enabled' flag check (tbz -> b #skip) and the " +
        "interstitial/rewarded cooldown gate (b.ge -> b #skip). Verified on v1.4.6 " +
        "(Dart 3.7.0, arm64, obfuscated) — no ad-show invocation is reached.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PROVIDELITE)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()

        // Central ad gate function (~0x592c70). First skip decision: a global "ad enabled"
        // flag (tbz w0, #4, #0x592d88). Flip to an unconditional branch to the skip/return-false
        // path so ads never proceed.
        applyPatch(bytes, 0x592d78, b(0x80, 0x00, 0x20, 0x36), b(0x03, 0x00, 0x00, 0x14), "adGate.globalFlag")

        // Second skip decision: the show/cooldown gate (b.ge #0x592df8). Flip to always fall
        // through to the "ad is time space" / return-false path. Belt-and-suspenders coverage
        // if the global-flag check is not the universal gate.
        applyPatch(bytes, 0x592dd0, b(0x4a, 0x01, 0x00, 0x54), b(0x01, 0x00, 0x00, 0x14), "adGate.cooldown")

        libFile.writeBytes(bytes)
    }
}

private fun b(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }

private fun applyPatch(
    bytes: ByteArray,
    offset: Long,
    expected: ByteArray,
    replacement: ByteArray,
    label: String
) {
    val index = offset.toInt()
    if (index + expected.size > bytes.size) {
        error("$label patch offset 0x${offset.toString(16)} is out of bounds")
    }
    val actual = bytes.sliceArray(index until index + expected.size)
    if (!actual.contentEquals(expected)) {
        error(
            "$label patch mismatch at 0x${offset.toString(16)}: " +
            "expected ${expected.joinToString("") { "%02x".format(it) }}, " +
            "got ${actual.joinToString("") { "%02x".format(it) }}"
        )
    }
    replacement.forEachIndexed { i, byte -> bytes[index + i] = byte }
}
