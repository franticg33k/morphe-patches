package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO

/*
 * The ad content itself is already stopped in RemoveAdsPatch (the WebView HTML loaders and the
 * AdMob method channel). What that leaves behind is the shell: Nepali Patro pushes a full-screen
 * interstitial route and runs a five second countdown on it, and with the content removed you get
 * a blank page for the full timer.
 *
 * Two independent things drive that overlay, and only one of them is the network:
 *
 *  1. The creative comes from the first-party ad server, whose host is ad-only - the rest of the
 *     app talks to api.nepalipatro.com.np, api-news.nepalipatro.com.np and friends.
 *  2. Whether to open the overlay at all is decided in Dart, from the removal-type list that
 *     fetchSubscriptionTypeFromRemoteConfig fills in and AdsPrefDao caches as
 *     PREFS_ADS_REMOVAL_TYPE. That list is remote config, and the app rewrites it on every launch,
 *     so patching the host alone does not stop the overlay - a device test confirmed the overlay
 *     still opens with the ad server unreachable, it just has nothing to show.
 *
 * So the host rewrite handles (1) and a one-instruction branch flip in
 * AdsBloc::handleInterstitialAdsOnDashboardBottomMenuNavigation handles (2), which is the part
 * that actually produces the five second page.
 *
 * Confirmed empirically before writing this: blocking only 157.10.100.114 (the address of
 * ads-delivery.nepalipatro.com.np) removed the overlay with no other loss of function, which is
 * what identified the ad server host in the first place.
 */

private const val AD_SERVER_HOST = "ads-delivery.nepalipatro.com.np"

/**
 * Same length as [AD_SERVER_HOST], so the snapshot is rewritten in place with no reflow. Only the
 * top level domain changes; `.np` -> `.xx` is not an assigned TLD, so the lookup fails with
 * NXDOMAIN rather than being redirected somewhere unexpected.
 */
private const val BLOCKED_AD_SERVER_HOST = "ads-delivery.nepalipatro.com.xx"

private const val LIBAPP = "lib/arm64-v8a/libapp.so"

/**
 * Blutter address of the `cbz x3, ...` that guards the interstitial call inside
 * `AdsBloc::handleInterstitialAdsOnDashboardBottomMenuNavigation` (function addr 0xc531bc).
 * Blutter's per-function `addr` is the file offset in libapp.so, confirmed by the AArch64
 * prologue landing exactly on `stp x29, x30, [sp, #-0x10]!`.
 */
private const val INTERSTITIAL_GATE_OFFSET = 0xc531d4L

private fun ByteArray.matchesAt(offset: Int, needle: ByteArray): Boolean {
    if (offset < 0 || offset + needle.size > size) return false
    for (i in needle.indices) if (this[offset + i] != needle[i]) return false
    return true
}

private fun ByteArray.occurrencesOf(needle: ByteArray): List<Int> {
    val hits = ArrayList<Int>()
    var from = 0
    while (from <= size - needle.size) {
        val at = indexOfSub(needle, from)
        if (at < 0) break
        hits += at
        from = at + 1
    }
    return hits
}

private fun ByteArray.indexOfSub(needle: ByteArray, from: Int): Int {
    for (i in from..size - needle.size) if (matchesAt(i, needle)) return i
    return -1
}

/**
 * Rewrites bytes at [offset] after asserting they are exactly [expected].
 *
 * Every offset here was read out of a blutter disassembly of this exact snapshot, so a byte
 * mismatch means the app was updated and the recipe no longer applies - fail loudly rather than
 * patch the wrong instruction.
 */
private fun patchAt(
    bytes: ByteArray,
    offset: Long,
    expected: ByteArray,
    replacement: ByteArray,
    label: String,
) {
    require(expected.size == replacement.size) { "$label: replacement must be the same size" }
    val at = offset.toInt()
    if (at < 0 || at + expected.size > bytes.size) {
        error("$label: offset 0x${offset.toString(16)} is outside $LIBAPP (${bytes.size} bytes)")
    }
    val actual = bytes.copyOfRange(at, at + expected.size)
    if (!actual.contentEquals(expected)) {
        error(
            "$label: byte mismatch at 0x${offset.toString(16)}; expected " +
                expected.joinToString(" ") { "%02x".format(it) } + " but found " +
                actual.joinToString(" ") { "%02x".format(it) }
        )
    }
    replacement.forEachIndexed { i, byte -> bytes[at + i] = byte }
}

private fun b(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }

@Suppress("unused")
val blockNepalipatroAdServerPatch = rawResourcePatch(
    name = "Block Ad Server",
    description = "Stops Nepali Patro's first-party ads in libapp.so. Two edits: the ad-only " +
        "host ads-delivery.nepalipatro.com.np is rewritten to an unresolvable .xx domain of the " +
        "same length, so the ad request always fails; and the bottom-menu interstitial handler " +
        "is forced down its already-present 'return null' path so the full-screen ad page and " +
        "its five second countdown never open. The ad switches themselves come from remote " +
        "config and are cached in SharedPreferences, which an APK patch cannot write and which " +
        "the app rewrites on every launch - hence the two edits above. Pairs with Remove Ads, " +
        "which suppresses the ad content itself and the AdMob interstitials.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NEPALIPATRO)

    execute {
        val lib = get(LIBAPP, false)
        val bytes = lib.readBytes()
        val needle = AD_SERVER_HOST.toByteArray(Charsets.US_ASCII)
        val replacement = BLOCKED_AD_SERVER_HOST.toByteArray(Charsets.US_ASCII)

        // Same length by construction - the rewrite is in place, so nothing else moves.
        if (needle.size != replacement.size) {
            error("$AD_SERVER_HOST and $BLOCKED_AD_SERVER_HOST must be the same length")
        }

        val hits = bytes.occurrencesOf(needle)
        if (hits.size != 1) {
            error(
                "$AD_SERVER_HOST occurs ${hits.size} times in $LIBAPP, expected exactly 1 " +
                    "(offsets $hits); refusing to guess which one is the ad server"
            )
        }

        val at = hits.single()
        // Verify what we are about to overwrite really is the host, not a lookalike.
        if (!bytes.matchesAt(at, needle)) {
            error("byte verification failed for $AD_SERVER_HOST at 0x${at.toString(16)}")
        }
        replacement.forEachIndexed { i, byte -> bytes[at + i] = byte }

        // AdsBloc::handleInterstitialAdsOnDashboardBottomMenuNavigation (blutter addr 0xc531bc)
        // is the bottom-menu interstitial handler, and the only direct caller of
        // featureInterstitialAdWithHtmlPopup. Its body is:
        //
        //   0xc531d4: cbz  x3, #0xc531e8     ; x3 == null -> return null
        //   0xc531d8: mov  x0, NULL          ; the "no ad" return
        //   0xc531dc: mov  SP, fp
        //   0xc531e0: ldp  fp, lr, [SP], #0x10
        //   0xc531e4: ret
        //   0xc531e8: ...                     ; bl featureInterstitialAdWithHtmlPopup
        //
        // Turning that one conditional into an unconditional branch to the existing return keeps
        // the frame setup and teardown intact and skips only the ad. Same size, so nothing in the
        // instruction stream moves.
        patchAt(
            bytes = bytes,
            offset = INTERSTITIAL_GATE_OFFSET,
            expected = b(0xa3, 0x00, 0x00, 0xb4), // cbz x3, #0xc531e8
            replacement = b(0x01, 0x00, 0x00, 0x14), // b #0xc531d8
            label = "interstitial gate",
        )

        lib.writeBytes(bytes)
    }
}
