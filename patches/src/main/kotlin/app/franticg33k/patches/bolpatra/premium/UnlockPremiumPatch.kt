package app.franticg33k.patches.bolpatra.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.bolpatra.shared.Constants.COMPATIBILITY_BOLPATRA

@Suppress("unused")
val unlockBolpatraPremiumPatch = rawResourcePatch(
    name = "Unlock Premium, Skip Login & Make Free Trial Permanent",
    description = "Patches the Dart AOT-compiled libapp.so of Bolpatra Nepal to " +
        "skip the login screen, unlock premium/paid features, and make the " +
        "7-day free trial permanent. " +
        "LoginManager::isNotValidatedUser is forced to always return false, " +
        "lifting the Free-plan paywall and the trial-expiry gate: the trial " +
        "expiry is computed client-side from the server plan_end_date into " +
        "statics (isPaid = (plan_end_date - now).inDays <= 0) that only " +
        "isNotValidatedUser reads, so with it pinned to false the free trial " +
        "never expires and premium features stay unlocked forever (PPMO/EGP " +
        "contact redirect, hidden works tab, document download and gallery " +
        "gates). The isActive() result is left " +
        "honest so the app stops attaching the invalid Authorization header, and " +
        "the four login redirects (notice View, document download, gallery, " +
        "subscription purchase) are bypassed at the branch level so actions run " +
        "straight through without sending the user to the login page. " +
        "Verified on v1.1.33 (Dart 3.9.2, arm64, non-obfuscated).",
    default = true
) {
    compatibleWith(COMPATIBILITY_BOLPATRA)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()

        // Patch 1: LoginManager::isNotValidatedUser @ 0x69669c — flag path
        // `add x0, x22, #0x20` (return true) -> `add x0, x22, #0x30` (return false).
        // This is the trial-expiry branch: the server sets isPaid when
        // (plan_end_date - now).inDays <= 0, so once the trial runs out the old
        // code returned true (premium blocked). Pinning it to false keeps the
        // free trial active forever.
        applyPatch(
            bytes = bytes,
            offset = 0x6966c0,
            expected = byteArrayOf(0xc0.toByte(), 0x82.toByte(), 0x00, 0x91.toByte()), // add x0, x22, #0x20
            replacement = byteArrayOf(0xc0.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte()), // add x0, x22, #0x30
            label = "isNotValidatedUser.flagPath"
        )

        // Patch 2: LoginManager::isNotValidatedUser @ 0x69669c — plan path
        // NOP the `b #0x696708` after the planType=="Free" comparison so execution
        // falls through to `add x0, x22, #0x30` (return false). This is the
        // Free-plan branch of the trial gate; making it false means a Free user
        // is never treated as trial-expired either.
        applyPatch(
            bytes = bytes,
            offset = 0x696700,
            expected = byteArrayOf(0x02, 0x00, 0x00, 0x14), // b <skip false-return>
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "isNotValidatedUser.planPath"
        )

        // Patch 3: _NoticeDetailsContainerState._handleView @ 0x9afb68
        // The `tbz w0, #4, #0x9afce0` login gate is rewritten as an unconditional
        // `b #0x9afce0`, so pressing View on a notice always proceeds to the
        // (now always-false) isNotValidatedUser check and opens the notice
        // details directly instead of redirecting to login.
        applyPatch(
            bytes = bytes,
            offset = 0x9afc78,
            expected = byteArrayOf(0x40, 0x03, 0x20, 0x36), // tbz w0, #4, #0x9afce0
            replacement = byteArrayOf(0x1a, 0x00, 0x00, 0x14), // b #0x9afce0
            label = "handleView.skipLogin"
        )

        // Patch 4: pdf_view document download gate
        // NOP the `tbnz w0, #4, #0x9b2724` that routes logged-out users to
        // navigateToLogin. After patch the code falls through to
        // isNotValidatedUser() (forced false) and starts the download.
        applyPatch(
            bytes = bytes,
            offset = 0x9b2578,
            expected = byteArrayOf(0x60, 0x0d, 0x20, 0x37), // tbnz w0, #4, #0x9b2724
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "pdfView.skipLogin"
        )

        // Patch 5: gallery_notice open gate
        // NOP the `tbnz w0, #4, #0x9f7f24` that routes logged-out users to
        // navigateToLogin; falls through to isNotValidatedUser() (forced false)
        // and opens the gallery page.
        applyPatch(
            bytes = bytes,
            offset = 0x9f7e80,
            expected = byteArrayOf(0x20, 0x05, 0x20, 0x37), // tbnz w0, #4, #0x9f7f24
            replacement = byteArrayOf(0x1f, 0x20, 0x03.toByte(), 0xd5.toByte()), // NOP
            label = "galleryNotice.skipLogin"
        )

        // Patch 6: subscription_card.handleOnTap
        // The `tbz w0, #4, #0xa095dc` is rewritten as `b #0xa095dc` so tapping a
        // plan card always continues into the purchase flow instead of pushing
        // the login page first.
        applyPatch(
            bytes = bytes,
            offset = 0xa095b0,
            expected = byteArrayOf(0x60, 0x01, 0x20, 0x36), // tbz w0, #4, #0xa095dc
            replacement = byteArrayOf(0x0b, 0x00, 0x00, 0x14), // b #0xa095dc
            label = "subscriptionCard.skipLogin"
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
