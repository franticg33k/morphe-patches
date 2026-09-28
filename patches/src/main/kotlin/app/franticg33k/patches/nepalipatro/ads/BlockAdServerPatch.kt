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
 * So the host rewrite handles (1) and a one-instruction retarget inside
 * featureInterstitialAdWithHtmlPopup handles (2), which is the part that actually produces the
 * five second page.
 *
 * A device test is what separated the two. The overlay still opened with this patch applied and
 * the ad server unreachable, and the app had cached the full ad URL list in SharedPreferences
 * under PREF_SLIDER_DATA - so the snapshot rewrite never even got consulted, and the decision to
 * push the route was being made from remote config, not from the network.
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
 * Blutter address of the "premium / removal type" branch inside
 * `AdsBloc::featureInterstitialAdWithHtmlPopup` (async wrapper 0x9619fc, state machine 0x961b88).
 * Blutter's per-function `addr` is the file offset in libapp.so, confirmed by the AArch64
 * prologue landing exactly on `stp x29, x30, [sp, #-0x10]!`.
 */
private const val INTERSTITIAL_GATE_OFFSET = 0x961d1cL

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
    description = "Stops Nepali Patro's interstitial ads. Two edits to libapp.so: the ad-only " +
        "host ads-delivery.nepalipatro.com.np is rewritten to an unresolvable .xx domain of the " +
        "same length, and AdsBloc::featureInterstitialAdWithHtmlPopup - the single callee behind " +
        "all 24 interstitial call sites - is forced down its existing 'popup blocked' return, so " +
        "the full-screen ad page and its countdown never open. The host rewrite alone is not " +
        "enough: the ad URLs are also cached in SharedPreferences under PREF_SLIDER_DATA, and " +
        "whether to show the page at all comes from remote config, which the app rewrites on " +
        "every launch. Pairs with Remove Ads, which stops the ad content itself and the AdMob " +
        "interstitials.",
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

        // featureInterstitialAdWithHtmlPopup is the single callee behind all 24
        // `bl #0x9619fc` call sites in the app (calendar tab, my_calendar, home, weather, forex,
        // blog, petroleum, rashifal, suya_sait, vegetable, government holiday, date conversion,
        // radio, bullion, kundali, unit conversion, dashboard, app_web_view, app_utils, and the
        // bottom-menu navigation handler), so one edit here covers every HTML interstitial.
        // Patching the callers instead would mean 24 fragile offsets.
        //
        // Its state machine opens with the app's own "should I show this?" decision:
        //
        //   0x961d10: ldur  x0, [fp, #-0xd8]     ; the premium / removal-type flag
        //   0x961d18: cmp   w0, true
        //   0x961d1c: b.ne  #0x961d78            ; not premium -> carry on and show the ad
        //   0x961d20: ldur  x0, [fp, #-0xe8]     ; build the log line
        //   0x961d24: tbz   w0, #4, #0x961d70
        //   0x961d28: ...   "POPUP_ADS: scope=... blocked due to premium/removal-type rules"
        //   0x961d68: add   x0, xzr, #0x30        ; false
        //   0x961d6c: b     ReturnAsyncNotFutureStub
        //
        // The app is not premium, so control always takes the `b.ne`. Retargeting that one branch
        // at 0x961d68 sends every interstitial down the app's own "blocked" return, which is
        // already the well-trodden path whenever there is nothing to show - it completes the
        // future with `false` and the route is simply never pushed, so no page and no countdown.
        //
        // Branching to 0x961d68 rather than NOPing the instruction matters: the fall-through
        // contains a `tbz` that rejoins the show path, so a NOP would not reliably block. Jumping
        // straight to the return skips the log string as a side effect, and the print it would
        // have emitted is the only thing lost.
        patchAt(
            bytes = bytes,
            offset = INTERSTITIAL_GATE_OFFSET,
            expected = b(0xe1, 0x02, 0x00, 0x54), // b.ne #0x961d78
            replacement = b(0x13, 0x00, 0x00, 0x14), // b #0x961d68
            label = "interstitial gate",
        )

        lib.writeBytes(bytes)
    }
}
