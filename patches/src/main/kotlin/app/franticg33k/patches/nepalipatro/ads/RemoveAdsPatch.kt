package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/*
 * Nepali Patro 6.11.5 has two independent ad stacks and both have to go:
 *
 *  1. Google Mobile Ads — no ad actually renders today (every request dies with
 *     "Error building request URL"), but the SDK is still wired up, so the plugin's
 *     method-channel entry point is short-circuited for every load and show call. Nothing is
 *     ever constructed, so there is nothing left to show.
 *
 *  2. flutter_adserver — the first-party server that renders the ads you actually SEE
 *     (the "SLIDERWIDGET" / "Slider impression" logs). It loads HTML into a flutter_webview
 *     platform view, so neutralising the three loaders the Pigeon bridge exposes kills it at
 *     the only place that touches android.webkit.WebView.
 *
 * Both edits are insert-at-0 no-ops: no original instruction is removed or rewritten, so a
 * future build that adds code around them keeps working. Every edit is preceded by an
 * assertion on the shape it depends on, so a reshuffle fails the patch loudly instead of
 * silently corrupting the method.
 */

/**
 * `GoogleMobileAdsPlugin.onMethodCall` dispatches on `MethodCall.method`, so one guard at the
 * top of the method covers every ad format. Ad formats are always requested as `load*` and
 * presented as `show*`; everything else (`MobileAds#initialize`, `disposeAd`, `getAdSize`,
 * consent, settings) falls through untouched so the rest of the plugin behaves normally.
 *
 * Answering with `Result.success(null)` and returning means the Dart side sees a completed
 * `load` call and simply never receives an `onAdLoaded`/`onAdFailedToLoad` event — no error
 * is raised and no ad object exists to be shown.
 *
 * Needs two scratch registers (`v0`, `v1`) and must read `p1`/`p2`, so it is only valid for a
 * non-static method whose prologue writes `v0` then `v1` before anything reads them.
 *
 * `${'$'}` is Kotlin's escape for the `$` inside the `MethodChannel$Result` descriptor.
 */
private val ADMOB_LOAD_GUARD = """
    iget-object v0, p1, Lio/flutter/plugin/common/MethodCall;->method:Ljava/lang/String;
    const-string v1, "load"
    invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :morphe_block
    iget-object v0, p1, Lio/flutter/plugin/common/MethodCall;->method:Ljava/lang/String;
    const-string v1, "show"
    invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :morphe_run
    :morphe_block
    const/4 v0, 0x0
    invoke-interface {p2, v0}, Lio/flutter/plugin/common/MethodChannel${'$'}Result;->success(Ljava/lang/Object;)V
    return-void
    :morphe_run
    nop
"""

/**
 * `WebViewProxyApi.loadUrl(WebView, String, Map)` — the only loader that takes a URL we do not
 * control. Blocking it outright would break legitimate in-app browsing, so it is gated on the
 * ad server host and on `data:` (inline HTML) URLs, which is what an ad WebView navigates to.
 *
 * `.locals 0` in 6.11.5, so `v0` aliases `p0` (the receiver) — free only because the original
 * body never touches register 0, which the assertion in `execute` enforces. `p2` is the URL
 * (the fingerprint pins the parameter list, and the static check keeps `pN` aligned).
 */
private val WEBVIEW_LOAD_URL_GUARD = """
    if-eqz p2, :morphe_run
    const-string v0, "data:"
    invoke-virtual {p2, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :morphe_block
    const-string v0, "ads-delivery"
    invoke-virtual {p2, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :morphe_run
    :morphe_block
    return-void
    :morphe_run
    nop
"""

/**
 * Every register an instruction may touch. Order matters: the three-register and wider
 * interfaces all extend `OneRegisterInstruction`, so the wider shapes must be matched first.
 * Anything unhandled (payloads, switch tables, unknown opcodes) reports no registers, which is
 * what a conservative "does this instruction reference register 0" check wants.
 */
private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC,
        instruction.registerD,
        instruction.registerE,
        instruction.registerF,
        instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> List(instruction.registerCount) { instruction.startRegister + it }
    is ThreeRegisterInstruction -> listOf(
        instruction.registerA,
        instruction.registerB,
        instruction.registerC,
    )
    is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
    is OneRegisterInstruction -> listOf(instruction.registerA)
    else -> emptyList()
}

@Suppress("unused")
val removeNepalipatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables both ad stacks in Nepali Patro: Google Mobile Ads (AdMob) and the " +
        "first-party flutter_adserver HTML ad server. AdMob's method-channel entry point is " +
        "short-circuited for every load and show call so no ad is ever created, and the three " +
        "WebView loaders the ad server uses are neutralised - loadData and loadDataWithBaseUrl " +
        "become no-ops, while loadUrl only refuses the ads-delivery.nepalipatro.com.np host " +
        "and data: URLs so normal in-app browsing keeps working.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NEPALIPATRO)

    execute {
        // ---------------------------------------------------------------- AdMob
        val adMob = AdMobOnMethodCallFingerprint.method
        val adMobImplementation = checkNotNull(adMob.implementation) {
            "Nepali Patro: AdMob onMethodCall has no implementation"
        }
        if (AccessFlags.STATIC.isSet(adMob.accessFlags)) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall became static; the guard addresses p1/p2 by " +
                    "their instance-method positions"
            )
        }

        // The guard clobbers v0 and v1 before branching, so the original body has to overwrite
        // both itself before it can read them — otherwise our constants leak into its work.
        // In 6.11.5 the prologue is exactly `move-object/from16 v0, p0` then `... v1, p1`.
        val prologueWrites = adMobImplementation.instructions.take(2).map { instruction ->
            (instruction as? OneRegisterInstruction)?.registerA ?: -1
        }
        if (prologueWrites != listOf(0, 1)) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall prologue no longer writes v0 then v1 " +
                    "(found $prologueWrites); the load/show guard needs both as scratch registers"
            )
        }
        adMob.addInstructions(0, ADMOB_LOAD_GUARD)

        // ------------------------------------------------------------ WebView
        // flutter_adserver renders HTML ads, so the loaders that feed a WebView are the
        // chokepoint for everything it draws. Both HTML loaders are pure one-instruction
        // delegates in `.locals 0` methods; a bare `return-void` needs no registers at all,
        // so no scratch-register precondition has to hold for them.
        WebViewLoadDataFingerprint.method.addInstructions(0, "return-void")
        WebViewLoadDataWithBaseUrlFingerprint.method.addInstructions(0, "return-void")

        // loadUrl is the one loader whose target we do not own, so it gets a host gate rather
        // than a blanket no-op — but the gate needs `v0` as scratch, which under `.locals 0`
        // aliases the receiver. Refuse to patch if a future build starts using register 0.
        val urlLoader = WebViewLoadUrlFingerprint.method
        val urlImplementation = checkNotNull(urlLoader.implementation) {
            "Nepali Patro: WebViewProxyApi.loadUrl has no implementation"
        }
        if (AccessFlags.STATIC.isSet(urlLoader.accessFlags)) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl became static; the guard addresses p2 " +
                    "by its instance-method position"
            )
        }
        // `v0` has to be a register the original body leaves alone: under `.locals 0` it aliases
        // the receiver, under `.locals N` it is the first free local. Either way, if the
        // original touches it anywhere, our constants would leak into its work.
        val touchedByOriginal = urlImplementation.instructions.firstOrNull { instruction ->
            0 in registersOf(instruction)
        }
        if (touchedByOriginal != null) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl touches register 0 " +
                    "(${touchedByOriginal.opcode}); the URL gate needs that register as scratch"
            )
        }
        urlLoader.addInstructions(0, WEBVIEW_LOAD_URL_GUARD)
    }
}
