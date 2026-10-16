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
 * So the host rewrite handles (1) and three one-instruction retargets in the Dart snapshot handle
 * (2), which is the part that actually produces the visible shell.
 *
 * A device test is what separated the two. The overlay still opened with the ad server unreachable,
 * and the app had cached the full ad URL list in SharedPreferences under PREF_SLIDER_DATA - so the
 * snapshot rewrite never even got consulted, and the decision to present the shell was being made
 * from remote config, not from the network.
 *
 * Confirmed empirically before writing this: blocking only 157.10.100.114 (the address of
 * ads-delivery.nepalipatro.com.np) removed the overlay with no other loss of function, which is
 * what identified the ad server host in the first place.
 *
 * ---
 *
 * THE SHELL HAS MORE THAN ONE PRESENTER. `AdsBloc::checkAdsQueueLoadable`'s per-item callback
 * (closure `0x961380`) switches on `SliderType` (enum values read out of `objs.txt`):
 *
 *   @11cbde1 "popup"           -> showDialog(PopUpAdsDialog)          via shouldShowPopupUsingFrequencyCap
 *   @11cbd01 "popup_html"      -> showDialog(PopUpAdsDialog)          via shouldShowPopupUsingFrequencyCap
 *   @11cbd81 "ad_mob"          -> showInterstitialAd()                (AdMob; already dead via Remove Ads)
 *   @11cbd21 "interstitial_html" -> Navigator.push(page)              NOT gated by the frequency cap at all
 *
 * and the pushed page renders `HtmlInterstitialAdLauncher`, whose State.initState schedules a timer
 * that calls `showHtmlInterstitialAdDialog()` -> `showGeneralDialog(HtmlInterstitialAd)`.
 *
 * The original single retarget only forced `shouldShowPopupUsingFrequencyCap` false, i.e. it closed
 * the popup/popup_html branch. The calendar-tab interstitial is the "interstitial_html" variant
 * (its cached URL list entry is literally `calendar_interstitial -> interstitial_html`), and that
 * branch never consults the frequency cap - so the full-screen shell still came up. That is the
 * "popup shows, ad does not load" residual: the container is opened from cached remote config, and
 * only its content (the HTML creative) is network-gated.
 *
 * The fix therefore closes both remaining presenters:
 *
 *   * the interstitial_html dispatch in the callback is retargeted to fall through to the
 *     ad_mob/popup tests (which then reject the item, since its type matches neither), so the page
 *     is never pushed; and
 *   * `showHtmlInterstitialAdDialog` itself is stubbed to an immediate `ret`, so no caller -
 *     launcher timer, inline SliderView slot, or anything added later - can raise the roadblock
 *     dialog.
 *
 * Both are single-instruction, byte-asserted, and confined to ad presentation; `ad_mob` remains
 * covered by Remove Ads' method-channel guard.
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
 * Blutter address of the `b.ne` in `AdsBloc::shouldShowPopupUsingFrequencyCap` (0x961b88) that the
 * popup/popup_html branch takes when ads are not disabled. Retargeted to the function's own
 * `false` return at 0x961d68 so the branch never shows its dialog. Blutter's per-function `addr` is
 * the file offset in libapp.so, confirmed by the AArch64 prologue landing exactly on
 * `stp x29, x30, [sp, #-0x10]!`.
 */
private const val POPUP_GATE_OFFSET = 0x961d1cL

/**
 * Blutter address of the `cmp w2, SliderType.interstitial_html` dispatch inside the callback at
 * `0x961380`. Its `b.ne` is what lets an interstitial_html item reach the `Navigator.push` at
 * `0x9616f8`; retargeted to an unconditional `b` the item falls through to the ad_mob/popup tests
 * and is dropped, so the route is never pushed.
 */
private const val INTERSTITIAL_HTML_DISPATCH_OFFSET = 0x96160cL

/**
 * `[package:flutter_adserver/html_interstitial_page.dart] ::showHtmlInterstitialAdDialog` - the
 * only function that raises the HTML interstitial roadblock (`showGeneralDialog(HtmlInterstitialAd)`).
 * Its sole caller is the launcher's timer callback, which ignores the (void) result.
 */
private const val SHOW_HTML_INTERSTITIAL_DIALOG_OFFSET = 0xebdcd8L

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
    description = "Stops Nepali Patro's interstitial ads. Three byte-asserted edits to libapp.so: " +
        "the ad-only host ads-delivery.nepalipatro.com.np is rewritten to an unresolvable .xx " +
        "domain of the same length, the interstitial_html dispatch in the ad-queue callback is " +
        "retargeted so the full-screen page is never pushed, and " +
        "showHtmlInterstitialAdDialog - the single function that raises the HTML interstitial " +
        "roadblock - is stubbed out. Also forces the popup/popup_html branch down the app's own " +
        "'blocked' return. The host rewrite alone is not enough: the ad URLs are also cached in " +
        "SharedPreferences under PREF_SLIDER_DATA, and whether to present the shell at all comes " +
        "from remote config, which the app rewrites on every launch. Pairs with Remove Ads, which " +
        "stops the ad content itself and the AdMob interstitials.",
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

        // The callback at 0x961380 is the single consumer of the interstitial queue: whatever
        // `bl #0x9619fc` (featureInterstitialAdWithHtmlPopup) is awaited from, the queue items
        // reach this closure. Both of the shell presenters it can reach are closed here.
        //
        // (a) The popup/popup_html branch asks the app's own "should I show this?" gate:
        //
        //   0x961d10: ldur  x0, [fp, #-0xd8]     ; isAdsDisabled
        //   0x961d18: cmp   w0, true
        //   0x961d1c: b.ne  #0x961d78            ; not disabled -> carry on and show
        //   0x961d20: ldur  x0, [fp, #-0xe8]     ; build the log line
        //   0x961d24: tbz   w0, #4, #0x961d70
        //   0x961d28: ...   "POPUP_ADS: scope=... blocked due to premium/removal-type rules"
        //   0x961d68: add   x0, xzr, #0x30        ; false
        //   0x961d6c: b     ReturnAsyncNotFutureStub
        //
        // Retargeting that one branch to 0x961d68 sends every popup down the app's own "blocked"
        // return. Branching to 0x961d68 rather than NOPing matters: the fall-through contains a
        // `tbz` that rejoins the show path, so a NOP would not reliably block. Jumping straight to
        // the return skips the log string as a side effect, and the print it would have emitted is
        // the only thing lost.
        patchAt(
            bytes = bytes,
            offset = POPUP_GATE_OFFSET,
            expected = b(0xe1, 0x02, 0x00, 0x54), // b.ne #0x961d78
            replacement = b(0x13, 0x00, 0x00, 0x14), // b #0x961d68
            label = "popup gate",
        )

        // (b) The interstitial_html branch never consults the gate above. Its dispatch is:
        //
        //   0x961600: r16 = Instance_SliderType    ; Obj!SliderType@11cbd21 "interstitial_html"
        //   0x961608: cmp   w2, w16
        //   0x96160c: b.ne  #0x9617a4              ; anything else -> the ad_mob test
        //   ...                                    ; interstitial_html -> push the full-screen page
        //   0x9616f8: Navigator.of().push(PageRouteBuilder(...))
        //
        // Making the branch unconditional sends interstitial_html down the ad_mob test too, where
        // it matches nothing and is dropped (b.ne -> 0x961854 -> b.ne -> return), so the page - and
        // with it the five second countdown - is never pushed.
        patchAt(
            bytes = bytes,
            offset = INTERSTITIAL_HTML_DISPATCH_OFFSET,
            expected = b(0xc1, 0x0c, 0x00, 0x54), // b.ne #0x9617a4
            replacement = b(0x66, 0x00, 0x00, 0x14), // b #0x9617a4
            label = "interstitial_html dispatch",
        )

        // (c) Belt and braces for the shell itself. Even with (b) in place, an interstitial_html
        // queue item placed inline in a SliderView slot still renders HtmlInterstitialAdLauncher,
        // whose State.initState schedules a timer that calls showHtmlInterstitialAdDialog()
        // -> showGeneralDialog(HtmlInterstitialAd). Stubbing the function to an immediate `ret`
        // removes the roadblock no matter which caller asks for it. It is void and its only caller
        // discards the result, so an early return is safe; nothing else in the snapshot calls it.
        patchAt(
            bytes = bytes,
            offset = SHOW_HTML_INTERSTITIAL_DIALOG_OFFSET,
            expected = b(0xfd, 0x79, 0xbf, 0xa9), // stp fp, lr, [sp, #-0x10]!
            replacement = b(0xc0, 0x03, 0x5f, 0xd6), // ret
            label = "showHtmlInterstitialAdDialog",
        )

        lib.writeBytes(bytes)
    }
}
