package app.franticg33k.patches.providelite.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.providelite.shared.Constants.COMPATIBILITY_PROVIDELITE

@Suppress("unused")
val enableProvidelitePremiumPatch = rawResourcePatch(
    name = "Unlock Premium",
    description = "Patches the Dart AOT-compiled libapp.so to unlock all premium / VIP features. " +
        "Forces the VIP state to always be true by rewriting the constant-pool loads " +
        "(add xN, x22, #0x30 -> add xN, x22, #0x20) inside the cacheIsVipKey readers " +
        "(getter + 3 callers). Verified on v1.4.6 (Dart 3.7.0, arm64, obfuscated) — " +
        "flutter.cacheIsVipKey=true on device.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PROVIDELITE)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()

        // Every get<VIP flag> in the app funnels through the cacheIsVipKey pool constant
        // (pp+0x15308). Each reader loads the Dart boolean constant:
        //   false = add xN, x22, #0x30   (cN c2 00 91)
        //   true  = add xN, x22, #0x20   (cN 82 00 91)
        // Flipping the 2nd byte (0x30 -> 0x20) forces the reader to return true.
        // validatePurchase (0x5d5780) was checked and contains NO false-literal returns,
        // so the four sites below are the complete set. Verified on v1.4.6
        // (Dart 3.7.0, arm64, obfuscated) — flutter.cacheIsVipKey=true on device.

        // cacheIsVipKey getter (0x1cf3b0)
        applyPatch(bytes, 0x1cf3f4, b(0xc1, 0xc2, 0x00, 0x91), b(0xc1, 0x82, 0x00, 0x91), "cacheIsVipKey.getter")
        // cacheIsVipKey reader #1 (0x5d487c)
        applyPatch(bytes, 0x5d4cd0, b(0xc3, 0xc2, 0x00, 0x91), b(0xc3, 0x82, 0x00, 0x91), "cacheIsVipKey.reader1")
        // cacheIsVipKey reader #2 (0x5d4bcc)
        applyPatch(bytes, 0x5d5de0, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "cacheIsVipKey.reader2")
        // cacheIsVipKey reader #3 (0x5d69cc)
        applyPatch(bytes, 0x5d6a9c, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "cacheIsVipKey.reader3")

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
