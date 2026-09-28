package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.Label
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/*
 * Nepali Patro 6.11.5 carries two independent ad stacks and both have to go:
 *
 *  1. Google Mobile Ads - `google_mobile_ads`, app id ca-app-pub-0951863942285424. Confirmed
 *     live on device (an interstitial fills; banners report "No fill" for their placement, which
 *     is an inventory result rather than an SDK fault). The plugin's method-channel entry point
 *     is short-circuited for every load and show call, so no ad is ever constructed.
 *
 *  2. flutter_adserver - Nepali Patro's own ad server, first party, no ad SDK involved. It
 *     pulls banner/creative metadata from api-news.nepalipatro.com.np, renders HTML in a
 *     flutter_webview platform view, and is what you actually see on screen. Turning wifi off
 *     makes every ad vanish, which confirms nothing is bundled locally - the creative only
 *     exists once the server answers. Neutralising the WebView loaders therefore stops it at the
 *     only place it touches android.webkit.WebView.
 *
 * ---------------------------------------------------------------------------
 * Why these guards are built with dexlib2 builders and not inline smali
 * ---------------------------------------------------------------------------
 * `InstructionExtensions.addInstructions(index, String)` routes the block through
 * `InlineSmaliCompiler`, which assembles it inside a *fixed 16-register template* rather than
 * the target method's own register file. Two consequences, both observed on device:
 *
 *   - anything naming v16 or above is rejected: "Invalid register: v22. Must be between v0 and
 *     v15, inclusive." The ceiling does not move with the registerCount handed to it.
 *   - a rejected line is dropped *silently*, so a block can assemble to fewer instructions than
 *     it has lines, with no exception. That produced a `onMethodCall` whose first surviving
 *     instruction was `const-string v1, "load"` followed by an `invoke-virtual` on a register
 *     that was never assigned, and the app died on launch with
 *         VerifyError: ... onMethodCall failed to verify:
 *         [0x2] tried to get class from non-reference register v0 (type=Undefined)
 *
 * The parser is the problem, not the bytecode. `addInstructions(index, List<BuilderInstruction>)`
 * takes instructions that are already built, so it never invokes smali at all: no register
 * ceiling, no silent drops, and parameter registers (`p1`, `p2`) become usable, which is what
 * makes the `MethodCall` readable and lets `WebViewProxyApi.loadUrl` be gated despite being a
 * `.locals 0` method. The same ceiling is documented by Nai64/Nai64Patches in
 * `universal/ads/util/SmaliUtils.kt`.
 */

/** An instruction to insert. */
private class Op(val instruction: BuilderInstruction)

/** A branch whose target is a [Target] name, to be resolved once positions are final. */
private class Branch(
    val opcode: Opcode,
    val register: Int,
    val target: String,
)

/** A label placed immediately after the [Op] with the given index (or after all ops). */
private class Target(val name: String, val afterOp: Int)

private val METHOD_CALL_METHOD_FIELD =
    "Lio/flutter/plugin/common/MethodCall;->method:Ljava/lang/String;"
private val STRING_STARTS_WITH = "Ljava/lang/String;->startsWith(Ljava/lang/String;)Z"
private val STRING_CONTAINS = "Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z"

/** `Lcom/Foo;->bar(Ljava/lang/String;)V` -> an [ImmutableMethodReference]. */
private fun methodReference(descriptor: String): ImmutableMethodReference {
    val arrow = descriptor.indexOf("->")
    val definingClass = descriptor.substring(0, arrow)
    val remainder = descriptor.substring(arrow + 2)
    val open = remainder.indexOf('(')
    val name = remainder.substring(0, open)
    val tail = remainder.substring(open + 1)
    val close = tail.lastIndexOf(')')
    val parameterTypes = tail.substring(0, close)
        .takeIf { it.isNotEmpty() }
        ?.split(";")
        ?.map { "$it;" }
        ?: emptyList()
    return ImmutableMethodReference(definingClass, name, parameterTypes, tail.substring(close + 1))
}

/** `Lcom/Foo;->bar:Ljava/lang/String;` -> an [ImmutableFieldReference]. */
private fun fieldReference(descriptor: String): ImmutableFieldReference {
    val arrow = descriptor.indexOf("->")
    val colon = descriptor.lastIndexOf(':')
    return ImmutableFieldReference(
        descriptor.substring(0, arrow),
        descriptor.substring(arrow + 2, colon),
        descriptor.substring(colon + 1),
    )
}

private fun iGetObject(register: Int, source: Int, reference: String) =
    Op(BuilderInstruction22c(Opcode.IGET_OBJECT, register, source, fieldReference(reference)))

private fun constString(register: Int, value: String) =
    Op(BuilderInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value)))

private fun invokeVirtual(registerA: Int, registerB: Int, reference: String) =
    Op(
        BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, registerA, registerB, 0, 0, 0, methodReference(reference),
        ),
    )

private fun moveResult(register: Int) = Op(BuilderInstruction11x(Opcode.MOVE_RESULT, register))

private fun branch(opcode: Opcode, register: Int, target: String) = Branch(opcode, register, target)

private fun returnVoid() = Op(BuilderInstruction10x(Opcode.RETURN_VOID))

/**
 * Inserts a small program at [index], resolving branch targets afterwards.
 *
 * Insertion is done in three passes because `newLabelForIndex` records a position, and every
 * insert before an existing position shifts it: the plain instructions go in first, then the
 * branches, and only then are the labels created and the branches rewritten to point at them.
 */
private fun MutableMethod.insertProgram(
    index: Int,
    program: List<Any>,
    label: String,
) {
    val implementation = checkNotNull(implementation) { "Nepali Patro: $label has no implementation" }
    val ops = program.filterIsInstance<Op>()
    val branches = program.filterIsInstance<Branch>()
    val targets = program.filterIsInstance<Target>()

    ops.forEachIndexed { offset, op -> implementation.addInstruction(index + offset, op.instruction) }
    // A branch needs a Label to be constructed, but the final target positions only exist once
    // everything is in place. Park the branches on a throwaway label, then rewrite them.
    val parkingLabel = implementation.newLabelForIndex(0)
    branches.sortedByDescending { it.positionIn(program) }.forEach { b ->
        implementation.addInstruction(index + b.positionIn(program), labelledBranch(b, parkingLabel))
    }

    val labels = HashMap<String, Label>()
    targets.forEach { t ->
        val shift = branches.count { b -> b.positionIn(program) < t.afterOp }
        labels[t.name] = implementation.newLabelForIndex(index + t.afterOp + shift)
    }
    branches.forEach { b ->
        implementation.replaceInstruction(
            index + b.positionIn(program),
            labelledBranch(b, requireNotNull(labels[b.target]) { "unknown target ${b.target}" }),
        )
    }
}

/** Where a branch sits in the final instruction stream: the number of ops that precede it. */
private fun Branch.positionIn(program: List<Any>): Int {
    var seen = 0
    for (item in program) {
        if (item === this) return seen
        if (item is Op) seen++
    }
    return seen
}

private fun labelledBranch(branch: Branch, label: Label) =
    BuilderInstruction21t(branch.opcode, branch.register, label)

/**
 * `GoogleMobileAdsPlugin.onMethodCall` dispatches on `MethodCall.method`, so one guard covers
 * every ad format. Formats are requested as `load*` and presented as `show*`; everything else
 * (`MobileAds#initialize`, `disposeAd`, `getAdSize`, consent, settings) falls through so the rest
 * of the plugin keeps working - notably `MobileAds#initialize` still completes, so the Dart side
 * does not hang on startup.
 *
 * `p1` (the `MethodCall`) is read directly, which is only possible because this guard is built
 * with dexlib2 rather than the inline compiler. A blocked call returns void: the Dart side never
 * receives `onAdLoaded` / `onAdFailedToLoad`, so no ad object is ever created. Completing the
 * channel call instead would need a null reference, and dex has no encoding of null that the
 * verifier accepts in a `Ljava/lang/Object;` slot (`const/4 vN, 0x0` is an int).
 *
 * `v0`/`v1` are scratch: the guard runs at offset 0 and the original prologue immediately
 * overwrites both before reading them, which `execute` asserts.
 */
private fun admobLoadGuard(call: Int) = listOf(
    iGetObject(0, call, METHOD_CALL_METHOD_FIELD),
    constString(1, "load"),
    invokeVirtual(0, 1, STRING_STARTS_WITH),
    moveResult(0),
    branch(Opcode.IF_NEZ, 0, BLOCK),
    iGetObject(0, call, METHOD_CALL_METHOD_FIELD),
    constString(1, "show"),
    invokeVirtual(0, 1, STRING_STARTS_WITH),
    moveResult(0),
    branch(Opcode.IF_EQZ, 0, RUN),
    returnVoid(),
    Target(BLOCK, 9),
    Target(RUN, 10),
)

/**
 * `WebViewProxyApi.loadUrl(WebView, String, Map)` - the only loader that takes a URL we do not
 * control. Blocking it outright would break legitimate in-app browsing, so it is gated on the ad
 * server host and on `data:` (inline HTML), which is what an ad WebView navigates to.
 *
 * The method is `.locals 0`, so every register is a parameter. `p2` is the URL and `p0` (the
 * receiver) is the only register the original body does not need, so it doubles as scratch - the
 * original delegate is a single `invoke-virtual {p1, p2, p3}` that never reads `p0`.
 */
private fun webViewLoadUrlGuard(url: Int) = listOf(
    branch(Opcode.IF_EQZ, url, RUN),
    constString(0, "data:"),
    invokeVirtual(url, 0, STRING_CONTAINS),
    moveResult(0),
    branch(Opcode.IF_NEZ, 0, BLOCK),
    constString(0, "ads-delivery"),
    invokeVirtual(url, 0, STRING_CONTAINS),
    moveResult(0),
    branch(Opcode.IF_EQZ, 0, RUN),
    returnVoid(),
    Target(BLOCK, 9),
    Target(RUN, 10),
)

private const val BLOCK = "morphe_block"
private const val RUN = "morphe_run"

@Suppress("unused")
val removeNepalipatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables both ad stacks in Nepali Patro: Google Mobile Ads (AdMob) and the " +
        "first-party flutter_adserver HTML ad server. AdMob's method-channel entry point is " +
        "short-circuited for every load and show call so no ad is ever created, and the " +
        "WebView loaders the ad server uses are neutralised - loadData and loadDataWithBaseUrl " +
        "become no-ops, while loadUrl only refuses the ads-delivery.nepalipatro.com.np host and " +
        "data: URLs so normal in-app browsing keeps working.",
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
                "Nepali Patro: AdMob onMethodCall became static; the guard reads the MethodCall " +
                    "from the first parameter slot"
            )
        }
        // The guard clobbers v0 and v1 before the original body runs, so its prologue has to
        // overwrite both before reading them. In 6.11.5 that is
        // `move-object/from16 v0, p0` then `... v1, p1`.
        val prologueWrites = adMobImplementation.instructions.take(2).map { instruction ->
            (instruction as? OneRegisterInstruction)?.registerA ?: -1
        }
        if (prologueWrites != listOf(0, 1)) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall prologue no longer writes v0 then v1 " +
                    "(found $prologueWrites); the load/show guard needs both as scratch registers"
            )
        }
        val declaredSlots = adMob.parameterTypes.size + 1
        val callRegister = adMobImplementation.registerCount - declaredSlots + 1
        adMob.insertProgram(0, admobLoadGuard(callRegister), "AdMob onMethodCall load/show guard")

        // ------------------------------------------------------------ WebView
        // flutter_adserver renders HTML, so the loaders that feed a WebView are the chokepoint
        // for everything it draws. loadData/loadDataWithBaseUrl are pure one-instruction
        // delegates and never legitimate here, so they become unconditional no-ops.
        WebViewLoadDataFingerprint.method.insertProgram(0, listOf(returnVoid()), "WebViewProxyApi.loadData")
        WebViewLoadDataWithBaseUrlFingerprint.method.insertProgram(
            0,
            listOf(returnVoid()),
            "WebViewProxyApi.loadDataWithBaseUrl",
        )

        // loadUrl is the one loader whose target we do not own, so it gets a host gate rather
        // than a blanket no-op.
        val urlLoader = WebViewLoadUrlFingerprint.method
        val urlImplementation = checkNotNull(urlLoader.implementation) {
            "Nepali Patro: WebViewProxyApi.loadUrl has no implementation"
        }
        if (AccessFlags.STATIC.isSet(urlLoader.accessFlags)) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl became static; the guard reads the url " +
                    "from its second parameter slot"
            )
        }
        // The gate uses p0 as scratch. That is only safe if the original body never reads it.
        val readsReceiver = urlImplementation.instructions.any { instruction ->
            instruction is OneRegisterInstruction && instruction.registerA == 0 ||
                instruction is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction &&
                (instruction.registerA == 0 || instruction.registerB == 0)
        }
        if (readsReceiver) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl reads register 0; the URL gate needs it as " +
                    "scratch because the method is .locals 0"
            )
        }
        val urlSlots = urlLoader.parameterTypes.size + 1
        val urlRegister = urlImplementation.registerCount - urlSlots + 2
        urlLoader.insertProgram(
            0,
            webViewLoadUrlGuard(urlRegister),
            "WebViewProxyApi.loadUrl host gate",
        )
    }
}
