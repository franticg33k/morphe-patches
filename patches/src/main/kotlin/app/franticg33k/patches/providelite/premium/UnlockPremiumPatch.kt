package app.franticg33k.patches.providelite.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.providelite.shared.Constants.COMPATIBILITY_PROVIDELITE

@Suppress("unused")
val enableProvidelitePremiumPatch = rawResourcePatch(
    name = "Unlock Premium",
    description = "Patches the Dart AOT-compiled libapp.so to unlock all premium / VIP features. " +
        "Forces the VIP state to always be true by rewriting the constant-pool loads " +
        "(add xN, x22, #0x30 -> add xN, x22, #0x20) inside the cacheIsVipKey readers and " +
        "the validatePurchase function. Verified on v1.4.0 (Dart 3.7.0, arm64, obfuscated).",
    default = true
) {
    compatibleWith(COMPATIBILITY_PROVIDELITE)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()

        // validatePurchase (0x5c5a98) — force the purchase-validation result to true.
        applyPatch(bytes, 0x5c60f8, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "validatePurchase.result")
        applyPatch(bytes, 0x5c6730, b(0xc4, 0xc2, 0x00, 0x91), b(0xc4, 0x82, 0x00, 0x91), "validatePurchase.a")
        applyPatch(bytes, 0x5c6748, b(0xc4, 0xc2, 0x00, 0x91), b(0xc4, 0x82, 0x00, 0x91), "validatePurchase.b")
        applyPatch(bytes, 0x5c676c, b(0xc2, 0xc2, 0x00, 0x91), b(0xc2, 0x82, 0x00, 0x91), "validatePurchase.c")

        // cacheIsVipKey reader (0x5c09a8)
        applyPatch(bytes, 0x5c0aac, b(0xc3, 0xc2, 0x00, 0x91), b(0xc3, 0x82, 0x00, 0x91), "isVipReader1.a")
        applyPatch(bytes, 0x5c0b20, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader1.b")
        applyPatch(bytes, 0x5c1374, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader1.c")
        applyPatch(bytes, 0x5c14a4, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader1.d")

        // cacheIsVipKey reader (0x5cb6e4)
        applyPatch(bytes, 0x5cb7b4, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader2.a")
        applyPatch(bytes, 0x5cba80, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader2.b")
        applyPatch(bytes, 0x5cbb00, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader2.c")
        applyPatch(bytes, 0x5cbc0c, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader2.d")
        applyPatch(bytes, 0x5cbc9c, b(0xc0, 0xc2, 0x00, 0x91), b(0xc0, 0x82, 0x00, 0x91), "isVipReader2.e")
        applyPatch(bytes, 0x5cbd24, b(0xd1, 0xc2, 0x00, 0x91), b(0xd1, 0x82, 0x00, 0x91), "isVipReader2.f")

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
