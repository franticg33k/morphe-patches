package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/*
 * Nepali Patro 6.11.5 has two independent ad stacks and both have to go:
 *
 *  1. Google Mobile Ads - no ad actually renders today (every request dies with
 *     "Error building request URL"), but the SDK is still wired up, so the plugin's
 *     method-channel entry point is short-circuited for every load and show call. Nothing is
 *     ever constructed, so there is nothing left to show.
 *
 *  2. flutter_adserver - the first-party server that renders the ads you actually SEE
 *     (the "SLIDERWIDGET" / "Slider impression" logs). It loads HTML into a flutter_webview
 *     platform view, so neutralising the loaders the Pigeon bridge exposes kills it at the
 *     only place that touches android.webkit.WebView.
 *
 * Both edits are insert-at-n no-ops: no original instruction is removed or rewritten, so a
 * future build that adds code around them keeps working. Every edit is preceded by an
 * assertion on the shape it depends on, so a reshuffle fails the patch loudly instead of
 * silently corrupting the method.
 */

/**
 * Opcodes whose single register operand is a pure destination.
 *
 * This list is deliberately incomplete. `writtenRegisters` under-approximates the written
 * set on purpose: treating a register as "read" when it was actually written only costs us a
 * scratch candidate, whereas the reverse would let the guard clobber a live value.
 */
private val SINGLE_REGISTER_WRITES = setOf(
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
    Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT,
    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16,
    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO,
    Opcode.NEW_INSTANCE, Opcode.NEW_ARRAY, Opcode.FILL_ARRAY_DATA,
    Opcode.ARRAY_LENGTH, Opcode.INSTANCE_OF, Opcode.CHECK_CAST,
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN,
    Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
    Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN,
    Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT,
    Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN,
    Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT,
)

/** Opcodes whose two-register form writes the first operand. */
private val TWO_REGISTER_WRITES = setOf(
    Opcode.MOVE, Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_16,
    Opcode.MOVE_FROM16, Opcode.MOVE_OBJECT_FROM16,
    Opcode.INT_TO_LONG, Opcode.INT_TO_FLOAT, Opcode.INT_TO_DOUBLE,
    Opcode.LONG_TO_INT, Opcode.LONG_TO_FLOAT, Opcode.LONG_TO_DOUBLE,
    Opcode.FLOAT_TO_INT, Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE,
    Opcode.DOUBLE_TO_INT, Opcode.DOUBLE_TO_LONG, Opcode.DOUBLE_TO_FLOAT,
    Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT,
    Opcode.AGET, Opcode.AGET_WIDE, Opcode.AGET_OBJECT, Opcode.AGET_BOOLEAN,
    Opcode.AGET_BYTE, Opcode.AGET_CHAR, Opcode.AGET_SHORT,
)

/** Registers an instruction writes. See [SINGLE_REGISTER_WRITES] for the over- vs under-approximation. */
private fun writtenRegisters(instruction: Instruction): Set<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerD,
        instruction.registerE,
        instruction.registerF,
        instruction.registerG,
    ).take(instruction.registerCount).toSet()
    is RegisterRangeInstruction ->
        List(instruction.registerCount) { instruction.startRegister + it }.toSet()
    is ThreeRegisterInstruction -> setOf(instruction.registerA)
    is TwoRegisterInstruction ->
        if (instruction.opcode in TWO_REGISTER_WRITES) setOf(instruction.registerA) else emptySet()
    is OneRegisterInstruction ->
        if (instruction.opcode in SINGLE_REGISTER_WRITES) setOf(instruction.registerA) else emptySet()
    else -> emptySet()
}

/** Every register an instruction touches. Wider shapes must be matched first; they all extend [OneRegisterInstruction]. */
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

/**
 * Local registers that are safe to clobber from [startIndex] onwards.
 *
 * `addInstructions` runs the block through `InlineSmaliCompiler`, which **silently drops any
 * instruction that references a register outside the method's `.locals` range** - parameter
 * slots are rejected whether they are spelled `pN` or as their `vN` equivalent. Both spellings
 * were tried and both were dropped, producing a method whose first surviving instruction was
 *
 *     const-string v1, "load"
 *     invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(...)Z
 *
 * with `v0` never assigned, so the dex verifier rejected the class and the app died on launch:
 *
 *     VerifyError: ... onMethodCall failed to verify:
 *     [0x2] tried to get class from non-reference register v0 (type=Undefined)
 *
 * So a guard may only use locals. All 21 locals of `onMethodCall` are referenced somewhere in
 * the original body, so none is free for the whole method - but a register whose *first*
 * reference from [startIndex] onwards is a write is dead there, and the original overwrites it
 * before it can be read. Those are the candidates returned here.
 */
private fun MutableMethod.deadLocalsFrom(startIndex: Int, excluded: Set<Int>): List<Int> {
    val implementation = checkNotNull(implementation) { "method has no implementation" }
    val declared = parameterTypes.size
    val slots = declared + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val localCount = implementation.registerCount - slots
    if (localCount <= 0) return emptyList()

    val firstReference = HashMap<Int, Instruction>()
    implementation.instructions.forEachIndexed { index, instruction ->
        if (index < startIndex) return@forEachIndexed
        for (register in registersOf(instruction)) {
            firstReference.putIfAbsent(register, instruction)
        }
    }
    return (0 until localCount)
        .filter { it !in excluded }
        .filter { register ->
            val use = firstReference[register] ?: return@filter true // never referenced at all
            register in writtenRegisters(use)
        }
}

/**
 * Guards can only use registers inside `.locals`, so assert the patcher emitted everything
 * instead of letting a dropped instruction become an unlaunchable APK.
 */
private fun String.instructionCount(): Int =
    lineSequence().count { val line = it.trim(); line.isNotEmpty() && !line.startsWith(":") }

private fun MutableMethod.insertGuard(index: Int, block: String, label: String) {
    val before = implementation?.instructions?.size ?: 0
    addInstructions(index, block)
    val after = implementation?.instructions?.size ?: 0
    val expected = block.instructionCount()
    if (after - before != expected) {
        throw PatchException(
            "Nepali Patro: $label - the patcher emitted ${after - before} of $expected " +
                "instructions; InlineSmaliCompiler silently drops instructions that reference " +
                "a register outside the method's .locals range, so a guard may only use locals"
        )
    }
}

/**
 * `GoogleMobileAdsPlugin.onMethodCall` dispatches on `MethodCall.method`, so one guard covers
 * every ad format. Ad formats are always requested as `load*` and presented as `show*`;
 * everything else (`MobileAds#initialize`, `disposeAd`, `getAdSize`, consent, settings) falls
 * through untouched so the rest of the plugin behaves normally.
 *
 * The guard runs *after* the prologue, which in 6.11.5 is three moves that park the receiver
 * and both arguments in `v0`/`v1`/`v2`. Reading the `MethodCall` from `v1` is what makes this
 * expressible: the parameter slot `p1`/`v22` is out of reach of `InlineSmaliCompiler`.
 *
 * A blocked call is answered with a bare `return-void`. The Dart side then never receives
 * `onAdLoaded` / `onAdFailedToLoad`, so no ad object is ever created and no error is raised.
 * It does leave the pending `invokeMethod` future unresolved, which is inert for a fire-and-
 * forget ad request. Completing it properly would need `Result.success(null)`, but a null
 * reference has no dex encoding that the verifier accepts in a `Ljava/lang/Object;` slot
 * (`const/4 vN, 0x0` is an *int*), and the `Result` argument itself is out of reach anyway.
 */
private fun admobLoadGuard(scratch: String, needle: String) = """
    iget-object $scratch, v1, Lio/flutter/plugin/common/MethodCall;->method:Ljava/lang/String;
    const-string $needle, "load"
    invoke-virtual {$scratch, $needle}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result $scratch
    if-nez $scratch, :morphe_block
    iget-object $scratch, v1, Lio/flutter/plugin/common/MethodCall;->method:Ljava/lang/String;
    const-string $needle, "show"
    invoke-virtual {$scratch, $needle}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result $scratch
    if-eqz $scratch, :morphe_run
    :morphe_block
    return-void
    :morphe_run
"""

@Suppress("unused")
val removeNepalipatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables both ad stacks in Nepali Patro: Google Mobile Ads (AdMob) and the " +
        "first-party flutter_adserver HTML ad server. AdMob's method-channel entry point is " +
        "short-circuited for every load and show call so no ad is ever created, and the two " +
        "WebView HTML loaders the ad server uses (loadData and loadDataWithBaseUrl) become " +
        "no-ops, so no ad markup ever reaches a WebView.",
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
                    "from the local the prologue parks it in"
            )
        }

        // The guard is inserted after the prologue, which must therefore be exactly
        // `move-object/from16 v0, p0`, `... v1, p1`, `... v2, p2` - the guard reads the
        // MethodCall out of v1, and v0/v2 stay live for the original body.
        val declaredSlots = adMob.parameterTypes.size + 1
        val parameterBase = adMobImplementation.registerCount - declaredSlots
        val prologue = adMobImplementation.instructions.take(3).map { it as? TwoRegisterInstruction }
        val prologueWrites = prologue.map { it?.registerA }
        val prologueReads = prologue.map { it?.registerB }
        if (prologueWrites != listOf(0, 1, 2) ||
            prologueReads != (0..2).map { parameterBase + it }
        ) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall prologue is no longer three moves of p0/p1/p2 " +
                    "into v0/v1/v2 (writes=$prologueWrites reads=$prologueReads, expected " +
                    "parameterBase=$parameterBase); the load/show guard depends on the MethodCall " +
                    "being in v1"
            )
        }

        val scratch = adMob.deadLocalsFrom(startIndex = 3, excluded = setOf(0, 1, 2))
        if (scratch.size < 2) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall offers only ${scratch.size} clobberable " +
                    "local(s) after the prologue ($scratch), need 2 for the load/show guard"
            )
        }
        adMob.insertGuard(
            3,
            admobLoadGuard(scratch = "v${scratch[0]}", needle = "v${scratch[1]}"),
            "AdMob onMethodCall load/show guard",
        )

        // ------------------------------------------------------------ WebView
        // flutter_adserver renders HTML ads (the `555HTML: SLIDERWIDGET` baseline logs), so the
        // loaders that feed HTML into a WebView are the chokepoint. Both are pure
        // one-instruction delegates, so a bare `return-void` needs no registers at all.
        //
        // `WebViewProxyApi.loadUrl` is intentionally left alone. It is `.locals 0`, so it has no
        // register a guard may use, which leaves only a blanket `return-void` - and that would
        // kill every in-app web page, not just ads. The ad's markup is loaded through
        // loadData/loadDataWithBaseUrl, so blocking those is enough to keep it off screen.
        WebViewLoadDataFingerprint.method.insertGuard(0, "return-void", "WebViewProxyApi.loadData")
        WebViewLoadDataWithBaseUrlFingerprint.method.insertGuard(
            0,
            "return-void",
            "WebViewProxyApi.loadDataWithBaseUrl",
        )
    }
}
