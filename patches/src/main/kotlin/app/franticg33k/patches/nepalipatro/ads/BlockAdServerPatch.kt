package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO

/*
 * Offsets re-located against Nepali Patro 6.11.6 (versionCode 2026100913) with a fresh blutter
 * run (Dart 3.8.1, snapshot hash 830f4f59e7969c70b595182826435c19). 6.11.5's snapshot was a
 * different build - 18.5 MB of the 20.1 MB file differs - so every offset moved, but the shape of
 * every target survived: the same instruction bytes are asserted at each new offset, and each was
 * confirmed against the disassembly before the constant was changed.
 *
 * RemoveAdsPatch stops the HTML creatives (the WebView HTML loaders) and the AdMob method channel,
 * and that is all it stops. What the HTML half leaves behind is the shell: Nepali Patro pushes a
 * full-screen interstitial route and runs a five second countdown on it, and with the content
 * removed you get a blank page for the full timer. What it leaves behind on the raster side is
 * worse - the image creatives keep rendering, because they are fetched by Dart and never touch a
 * WebView. That half is handled at the bottom of this file.
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
 * (6.11.6: closure `0x967d40`, in 6.11.5 `0x961380`) switches on `SliderType` (enum values read
 * out of `objs.txt`):
 *
 *   @11cca01 "popup_html"       -> showDialog(PopUpAdsDialog)          via shouldShowPopupUsingFrequencyCap
 *   @11cca21 "interstitial_html" -> Navigator.push(page)              after checkFrequencyCapReached
 *   @11ccae1 "popup"            -> showDialog(PopUpAdsDialog)          via shouldShowPopupUsingFrequencyCap
 *   @11cca81 "ad_mob"           -> showInterstitialAd()                (AdMob; already dead via Remove Ads)
 *
 * and the pushed page renders `HtmlInterstitialAdLauncher`, whose State.initState schedules a timer
 * that calls `showHtmlInterstitialAdDialog()` -> `showGeneralDialog(HtmlInterstitialAd)`.
 *
 * The original single retarget only forced `shouldShowPopupUsingFrequencyCap` false, i.e. it closed
 * the popup/popup_html branch. The calendar-tab interstitial is the "interstitial_html" variant
 * (its cached URL list entry is literally `calendar_interstitial -> interstitial_html`), and that
 * branch never consulted the popup gate - so the full-screen shell still came up. That is the
 * "popup shows, ad does not load" residual: the container is opened from cached remote config, and
 * only its content (the HTML creative) is network-gated.
 *
 * 6.11.6 added a frequency-cap check in front of the interstitial_html push, but it does not help:
 * a cap that passes still pushes the page, and the cap is remote config anyway.
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
 *
 * ---
 *
 * THE RASTER CREATIVES ARE A THIRD PATH, AND NOTHING ABOVE TOUCHES THEM. SliderType's ten formats
 * split into three rendering families:
 *
 *   raster (CachedNetworkImage, fed an absolute URL out of the ad response)
 *     0 "slider"      _SliderItemState::build -> cached_network_image
 *     1 "banner"      BannerView              -> cached_network_image
 *     2 "popup"       PopUpView               -> cached_network_image
 *     3 "card_view"   CardView1x1             -> cached_network_image
 *     4 "circular"    CircularWidget          -> cached_network_image
 *
 *   HTML (webview_flutter; already blank, because Remove Ads stubs the loaders)
 *     5 "circular_html", 6 "banner_html", 8 "popup_html"
 *
 *   presenters (a dialog or a pushed route; closed by (a)-(c) above)
 *     7 "interstitial_html", 9 "ad_mob"
 *
 * The raster family is invisible to every earlier edit for two independent reasons: the creative
 * URL is delivered inside the ad response, so there is nothing in the snapshot to rewrite, and
 * CachedNetworkImage is a Dart HTTP client rather than a WebView, so the loaders Remove Ads stubs
 * are never consulted. ads.nepalipatro.com.np - the host the creatives are actually served from -
 * occurs nowhere in libapp.so at all; it only ever exists in the payload. That is the residual
 * "the popup is gone but a banner image still shows" report.
 *
 * `_SliderViewState::build` (6.11.6: `0xc14d68`, in 6.11.5 `0xc0c4d0`) dispatches those formats
 * as a linear chain of SliderType tests, each shaped
 *
 *   r16 = Instance_SliderType   ; the static enum object for this format
 *   cmp w1, w16
 *   b.ne <next test>            ; not this format -> keep looking
 *   <build the creative>
 *
 * so replacing a raster format's `cmp` with an unconditional `b` to that same test's `b.ne` target
 * skips the body without disturbing the chain. The chain has exactly nine tests (every format but
 * `ad_mob`); the format then matches none of them and falls through to the method's own tail,
 *
 *   0xc164b4: r0 = SizedBox()   ; return SizedBox()
 *
 * which is exactly what the build already returns for the ad_mob format (9) - the app's own answer
 * for a format it renders nowhere inline. No widget is constructed and no creative is requested.
 * One edit per raster format.
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
 * Blutter address of the `b.ne` in `AdsBloc::shouldShowPopupUsingFrequencyCap` (0x968548) that the
 * popup/popup_html branch takes when ads are not disabled. Retargeted to the function's own
 * `false` return at 0x968728 so the branch never shows its dialog. Blutter's per-function `addr` is
 * the file offset in libapp.so, confirmed by the AArch64 prologue landing exactly on
 * `stp x29, x30, [sp, #-0x10]!`.
 *
 * 6.11.6 disassembly (the shape is unchanged from 6.11.5, only the addresses moved):
 *
 *   0x9686d0: x0 = isAdsDisabled
 *   0x9686d8: cmp   w0, true
 *   0x9686dc: b.ne  #0x968738            ; not disabled -> carry on and show
 *   0x9686e4: tbz   w0, #4, #0x968730    ; removal type allows it -> carry on
 *   0x968728: x0 = false; ReturnAsyncNotFuture
 */
private const val POPUP_GATE_OFFSET = 0x9686dcL

/**
 * Blutter address of the `cmp w2, SliderType.interstitial_html` dispatch inside the queue callback
 * at `0x967d40` (in 6.11.5 this was the callback at `0x961380`). Its `b.ne` is what lets an
 * interstitial_html item reach `Navigator.push(PageRouteBuilder)` at `0x96815c`; retargeted to an
 * unconditional `b` the item falls through to the ad_mob test (`0x968164`), then to the
 * popup/popup_html test (`0x968214`), matches neither, and is dropped at `0x968328`, so the route
 * is never pushed.
 */
private const val INTERSTITIAL_HTML_DISPATCH_OFFSET = 0x967fccL

/**
 * `[package:flutter_adserver/html_interstitial_page.dart] ::showHtmlInterstitialAdDialog` - the
 * only function that raises the HTML interstitial roadblock (`showGeneralDialog(HtmlInterstitialAd)`).
 * Its sole caller is the launcher's timer callback (`0xec6640`), which ignores the (void) result.
 * 6.11.6 address; 6.11.5 was `0xebdcd8`.
 */
private const val SHOW_HTML_INTERSTITIAL_DIALOG_OFFSET = 0xec6664L


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

/** The `cmp w1, w16` that opens every SliderType test in `_SliderViewState::build`. */
private val COMPARE_SLIDER_TYPE = b(0x3f, 0x00, 0x10, 0x6b)

private data class RasterFormat(
    /** SliderType name, for the mismatch message. */
    val type: String,
    /** Blutter address of this format's `cmp w1, w16`. */
    val offset: Long,
    /** `b` to that same test's `b.ne` target - the next test in the chain. */
    val replacement: ByteArray,
)

/**
 * The five `SliderType` formats that render a raster creative, and the edit that drops each one.
 *
 * Every offset is asserted to hold [COMPARE_SLIDER_TYPE] before it is replaced, so an upstream
 * reshuffle of `_SliderViewState::build` fails on the first format that moved instead of silently
 * patching the wrong instruction. See the file header for why `b` (rather than a NOP or a branch
 * into another body) is the right shape: it makes the format match nothing, so the value keeps
 * running down the test chain to the method's shared `return SizedBox()` tail.
 */
private val RASTER_CREATIVE_DISPATCH = listOf(
    RasterFormat("slider", 0xc15218L, b(0x78, 0x01, 0x00, 0x14)), // b #0xc157f8
    RasterFormat("banner", 0xc1580cL, b(0x8e, 0x00, 0x00, 0x14)), // b #0xc15a44
    RasterFormat("popup", 0xc15a4cL, b(0x40, 0x00, 0x00, 0x14)), // b #0xc15b4c
    RasterFormat("card_view", 0xc15b54L, b(0x7d, 0x00, 0x00, 0x14)), // b #0xc15d48
    RasterFormat("circular", 0xc15d50L, b(0x63, 0x00, 0x00, 0x14)), // b #0xc15edc
)

@Suppress("unused")
val blockNepalipatroAdServerPatch = rawResourcePatch(
    name = "Block Ad Server",
    description = "Stops Nepali Patro's ads. Eight byte-asserted edits to libapp.so plus an " +
        "in-place host rewrite: the ad-only host ads-delivery.nepalipatro.com.np is rewritten to " +
        "an unresolvable .xx domain of the same length; the interstitial_html dispatch in the " +
        "ad-queue callback is retargeted so the full-screen page is never pushed; " +
        "showHtmlInterstitialAdDialog - the single function that raises the HTML interstitial " +
        "roadblock - is stubbed out; the popup/popup_html branch is forced down the app's own " +
        "'blocked' return; and the five raster formats (slider, banner, popup, card_view, " +
        "circular) are dropped to the build's own empty-widget tail, so the image creatives " +
        "Remove Ads cannot see - they are fetched by Dart through CachedNetworkImage, not by a " +
        "WebView - are never requested at all. The host rewrite alone is not enough: the ad URLs " +
        "are also cached in SharedPreferences under PREF_SLIDER_DATA, and whether to present the " +
        "shell at all comes from remote config, which the app rewrites on every launch. Pairs " +
        "with Remove Ads, which stops the HTML creatives and the AdMob interstitials.",
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

        // The callback at 0x967d40 is the single consumer of the interstitial queue: whatever
        // `bl #0x9683bc` (featureInterstitialAdWithHtmlPopup) is awaited from, the queue items
        // reach this closure. Both of the shell presenters it can reach are closed here.
        //
        // (a) The popup/popup_html branch asks the app's own "should I show this?" gate:
        //
        //   0x9686d0: x0 = isAdsDisabled
        //   0x9686d8: cmp   w0, true
        //   0x9686dc: b.ne  #0x968738            ; not disabled -> carry on and show
        //   0x9686e4: tbz   w0, #4, #0x968730    ; removal type allows it -> carry on
        //   0x9686e8: ...   "POPUP_ADS: scope=... blocked due to premium/removal-type rules"
        //   0x968728: x0 = false; ReturnAsyncNotFuture
        //
        // Retargeting that one branch to 0x968728 sends every popup down the app's own "blocked"
        // return. Branching to 0x968728 rather than NOPing matters: the fall-through contains a
        // `tbz` that rejoins the show path, so a NOP would not reliably block. Jumping straight to
        // the return skips the log string as a side effect, and the print it would have emitted is
        // the only thing lost.
        patchAt(
            bytes = bytes,
            offset = POPUP_GATE_OFFSET,
            expected = b(0xe1, 0x02, 0x00, 0x54), // b.ne #0x968738
            replacement = b(0x13, 0x00, 0x00, 0x14), // b #0x968728
            label = "popup gate",
        )

        // (b) The interstitial_html branch does not consult the popup gate. Its dispatch is:
        //
        //   0x967fc0: r16 = Instance_SliderType    ; Obj!SliderType@11cca21 "interstitial_html"
        //   0x967fc8: cmp   w2, w16
        //   0x967fcc: b.ne  #0x968164              ; anything else -> the ad_mob test
        //   ...                                    ; interstitial_html -> push the full-screen page
        //   0x96815c: Navigator.of().push(PageRouteBuilder(...))
        //
        // Making the branch unconditional sends interstitial_html down the ad_mob test too, where
        // it matches nothing and is dropped (b.ne -> 0x968214 -> b.ne -> return at 0x968328), so the
        // page - and with it the countdown - is never pushed.
        patchAt(
            bytes = bytes,
            offset = INTERSTITIAL_HTML_DISPATCH_OFFSET,
            expected = b(0xc1, 0x0c, 0x00, 0x54), // b.ne #0x968164
            replacement = b(0x66, 0x00, 0x00, 0x14), // b #0x968164
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

        // (d)-(h) The raster creatives. These five formats build a CachedNetworkImage from a URL
        // that arrives in the ad response, so neither the host rewrite above nor Remove Ads'
        // WebView stubs can reach them - and the host the creatives come from is not in libapp.so
        // to begin with. The ad server hands back an absolute URL, the widget renders it, and the
        // only place the decision lives is this dispatch. Dropping each format's test sends it to
        // the method's own `return SizedBox()` tail, the same answer it already gives for ad_mob.
        for (format in RASTER_CREATIVE_DISPATCH) {
            patchAt(
                bytes = bytes,
                offset = format.offset,
                expected = COMPARE_SLIDER_TYPE,
                replacement = format.replacement,
                label = "raster creative dispatch (${format.type})",
            )
        }

        lib.writeBytes(bytes)
    }
}
