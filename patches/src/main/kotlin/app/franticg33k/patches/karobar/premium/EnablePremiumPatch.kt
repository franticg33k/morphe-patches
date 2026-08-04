package app.franticg33k.patches.karobar.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.karobar.shared.Constants.COMPATIBILITY_KAROBAR

@Suppress("unused")
val enableKarobarPremiumPatch = rawResourcePatch(
    name = "Unlock Premium",
    description = "Patches Dart AOT-compiled libapp.so to unlock all premium features. " +
        "Forces hasPremiumAccess and isPaidUser to always return true by NOPing " +
        "their ARM64 conditional branch instructions.",
    default = true
) {
    compatibleWith(COMPATIBILITY_KAROBAR)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()

        // Patch 1: hasPremiumAccess @ 0xdf89a0
        // NOP the `tbnz w0, #4, #0xdf89ac` branch that skips the return-true path.
        // After patch: always falls through to `add x0, NULL, #0x20` (return true).
        applyPatch(
            bytes = bytes,
            offset = 0xdf89a0,
            expected = byteArrayOf(0x60, 0x00, 0x20, 0x37), // tbnz w0, #4, <rel>
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "hasPremiumAccess"
        )

        // Patch 2: isPaidUser @ 0xdfa878
        // NOP the `tbz w1, #4, #0xdfa88c` branch that skips the return-true path.
        // After patch: always falls through to `add x0, NULL, #0x20` (return true).
        applyPatch(
            bytes = bytes,
            offset = 0xdfa878,
            expected = byteArrayOf(0xa1.toByte(), 0x00, 0x20, 0x36), // tbz w1, #4, <rel>
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "isPaidUser"
        )

        // Patch 3: showSubscriptionExpiredSheetForAdmin @ 0xdcabe4
        // NOP the BL call so the "subscription expired" bottom sheet is never shown
        // to admin users after login.
        applyPatch(
            bytes = bytes,
            offset = 0xdcabe4,
            expected = byteArrayOf(0x67, 0xa7.toByte(), 0x00, 0x94.toByte()), // bl <showSubscriptionExpiredSheetForAdmin>
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "expiredAdminSheet"
        )

        // Patch 4: showSubscriptionExpiredSheetForStaff @ 0xdcabec
        // NOP the BL call so the "subscription expired" bottom sheet is never shown
        // to staff users after login.
        applyPatch(
            bytes = bytes,
            offset = 0xdcabec,
            expected = byteArrayOf(0x42, 0x03, 0x00, 0x94.toByte()), // bl <showSubscriptionExpiredSheetForStaff>
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "expiredStaffSheet"
        )

        libFile.writeBytes(bytes)
    }
}

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
