package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO

/*
 * The ad content itself is already stopped in RemoveAdsPatch (the WebView HTML loaders and the
 * AdMob method channel). What that leaves behind is the shell: Nepali Patro pushes a full-screen
 * interstitial route and runs a five second countdown on it, and with the content removed you get
 * a blank page for the full timer.
 *
 * Verified on device: with wifi and mobile data off, the interstitial is never shown at all. The
 * overlay is therefore only opened when the ad fetch succeeds, not merely when ad content is
 * suppressed.
 *
 * That makes the ad server host itself the cheapest correct chokepoint. The creative host is a
 * separate, ad-only name - the rest of the app talks to api.nepalipatro.com.np,
 * api-news.nepalipatro.com.np, blog.nepalipatro.com.np and friends - so failing just this one
 * lookup reproduces the offline behaviour for ads while leaving the app fully online.
 *
 * Confirmed empirically before writing this: blocking only 157.10.100.114 (the address of
 * ads-delivery.nepalipatro.com.np) removed the overlay with no other loss of function.
 */

private const val AD_SERVER_HOST = "ads-delivery.nepalipatro.com.np"

/**
 * Same length as [AD_SERVER_HOST], so the snapshot is rewritten in place with no reflow. Only the
 * top level domain changes; `.np` -> `.xx` is not an assigned TLD, so the lookup fails with
 * NXDOMAIN rather than being redirected somewhere unexpected.
 */
private const val BLOCKED_AD_SERVER_HOST = "ads-delivery.nepalipatro.com.xx"

private const val LIBAPP = "lib/arm64-v8a/libapp.so"

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

@Suppress("unused")
val blockNepalipatroAdServerPatch = rawResourcePatch(
    name = "Block Ad Server",
    description = "Stops Nepali Patro's interstitial ad page from ever opening. The overlay is " +
        "only shown when the first-party ad fetch succeeds (with no network at all it is skipped " +
        "entirely), so this rewrites the ad-only host ads-delivery.nepalipatro.com.np in " +
        "libapp.so to an unresolvable .xx domain of the same length. The request then fails " +
        "exactly as it does offline and the five second blank ad page never appears, while the " +
        "rest of the app stays online. Pairs with Remove Ads, which suppresses the ad content and " +
        "the AdMob interstitials themselves.",
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
        lib.writeBytes(bytes)
    }
}
