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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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

/** A label placed at this point in the program; the following instruction is its target. */
private class Target(val name: String)

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
    // A branch needs a Label to construct, but the real target positions only exist once the
    // whole block is in place. Park every branch on a throwaway label, then rewrite them.
    val parkingLabel = implementation.newLabelForIndex(0)

    // Insert in program order, so a Target's final index is simply the number of instructions
    // that precede it. Doing this arithmetically instead (an explicit offset plus a correction
    // for later insertions) double-counted, which only showed up on WebViewProxyApi.loadUrl: it
    // is a two-instruction method, so the overshoot ran past the end of the instruction list.
    var cursor = index
    val branchPositions = ArrayList<Pair<Int, Branch>>()
    program.forEach { item ->
        when (item) {
            is Op -> { implementation.addInstruction(cursor, item.instruction); cursor++ }
            is Branch -> {
                implementation.addInstruction(cursor, labelledBranch(item, parkingLabel))
                branchPositions += cursor to item
                cursor++
            }
            is Target -> Unit
        }
    }

    val labels = HashMap<String, Label>()
    program.filterIsInstance<Target>().forEach { target ->
        var preceding = 0
        for (item in program) {
            if (item === target) break
            if (item is Op || item is Branch) preceding++
        }
        labels[target.name] = implementation.newLabelForIndex(index + preceding)
    }
    branchPositions.forEach { (position, branch) ->
        implementation.replaceInstruction(
            position,
            labelledBranch(branch, requireNotNull(labels[branch.target]) { "unknown ${branch.target}" }),
        )
    }
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
  * The `MethodCall` is read from **v1**, not from its parameter slot. That is not a stylistic
  * choice: `iget-object` is dex format `22c`, whose two register operands are encoded as 4-bit
  * nibbles, so the instruction physically cannot name a register above v15. The parameter slot
  * for the `MethodCall` is v22, and constructing that instruction throws
  * `IllegalArgumentException: Invalid register: v22. Must be between v0 and v15, inclusive.`
  * The prologue already parks the receiver, the `MethodCall` and the `Result` in v0/v1/v2, so
  * reading v1 is both legal and equivalent.
  *
  * A blocked call returns void: the Dart side never receives `onAdLoaded` / `onAdFailedToLoad`, so
  * no ad object is ever created. Completing the channel call instead would need a null reference,
  * and dex has no encoding of null that the verifier accepts in a `Ljava/lang/Object;` slot
  * (`const/4 vN, 0x0` is an int).
 */
private fun admobLoadGuard(scratch: Int, needle: Int) = listOf(
    iGetObject(scratch, 1, METHOD_CALL_METHOD_FIELD),
    constString(needle, "load"),
    invokeVirtual(scratch, needle, STRING_STARTS_WITH),
    moveResult(scratch),
    branch(Opcode.IF_NEZ, scratch, BLOCK),
    iGetObject(scratch, 1, METHOD_CALL_METHOD_FIELD),
    constString(needle, "show"),
    invokeVirtual(scratch, needle, STRING_STARTS_WITH),
    moveResult(scratch),
    branch(Opcode.IF_EQZ, scratch, RUN),
    Target(BLOCK),
    returnVoid(),
    Target(RUN),
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
    Target(BLOCK),
    returnVoid(),
    Target(RUN),
)

private const val BLOCK = "morphe_block"
private const val RUN = "morphe_run"

/**
 * Highest register any instruction we build can name.
 *
 * Every format used here (`21c`, `22c`, `21t`, `11x`, `10x`, `35c`) encodes its register operands
 * as 4-bit nibbles, so v16 and above are unrepresentable - dexlib2 rejects them in the
 * constructor with "Invalid register: v22. Must be between v0 and v15, inclusive." This is a dex
 * format property, not a patcher limitation; Nai64Patches notes the same constraint.
 */
private const val MAX_NIBBLE_REGISTER = 15

/** Opcodes whose single register operand is a pure destination. Under-approximated on purpose. */
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
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
    Opcode.INT_TO_LONG, Opcode.INT_TO_FLOAT, Opcode.INT_TO_DOUBLE,
    Opcode.LONG_TO_INT, Opcode.LONG_TO_FLOAT, Opcode.LONG_TO_DOUBLE,
    Opcode.FLOAT_TO_INT, Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE,
    Opcode.DOUBLE_TO_INT, Opcode.DOUBLE_TO_LONG, Opcode.DOUBLE_TO_FLOAT,
    Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT,
    Opcode.AGET, Opcode.AGET_WIDE, Opcode.AGET_OBJECT, Opcode.AGET_BOOLEAN,
    Opcode.AGET_BYTE, Opcode.AGET_CHAR, Opcode.AGET_SHORT,
)

private fun writtenRegisters(instruction: Instruction): Set<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
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

private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> List(instruction.registerCount) { instruction.startRegister + it }
    is ThreeRegisterInstruction ->
        listOf(instruction.registerA, instruction.registerB, instruction.registerC)
    is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
    is OneRegisterInstruction -> listOf(instruction.registerA)
    else -> emptyList()
}

/**
 * Registers in `0..v15` that are safe to clobber from [startIndex] onwards.
 *
 * All 21 locals of `onMethodCall` are referenced *somewhere*, so none is free for the whole
 * method. But a register whose first reference from [startIndex] onwards is a write is dead at
 * that point - the original overwrites it before it can be read - which is what this returns.
 */
private fun MutableMethod.deadLocalsFrom(startIndex: Int, excluded: Set<Int>): List<Int> {
    val implementation = checkNotNull(implementation) { "method has no implementation" }
    val firstReference = HashMap<Int, Instruction>()
    implementation.instructions.forEachIndexed { index, instruction ->
        if (index < startIndex) return@forEachIndexed
        for (register in registersOf(instruction)) firstReference.putIfAbsent(register, instruction)
    }
    return (0..MAX_NIBBLE_REGISTER)
        .filter { it !in excluded }
        .filter { register ->
            val use = firstReference[register] ?: return@filter true
            register in writtenRegisters(use)
        }
}

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
                    "from the local the prologue parks it in"
            )
        }
        // The guard is inserted straight after the prologue, which in 6.11.5 parks the receiver,
        // the MethodCall and the Result in v0/v1/v2. It reads the MethodCall from v1 and must
        // therefore leave all three alone; the assertion pins that shape so a reshuffle fails
        // here rather than corrupting the method.
        val parameterBase = adMobImplementation.registerCount - (adMob.parameterTypes.size + 1)
        val prologue = adMobImplementation.instructions.take(3).map { it as? TwoRegisterInstruction }
        val prologueWrites = prologue.map { it?.registerA }
        val prologueReads = prologue.map { it?.registerB }
        if (prologueWrites != listOf(0, 1, 2) ||
            prologueReads != (0..2).map { parameterBase + it }
        ) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall prologue is no longer three moves of p0/p1/p2 " +
                    "into v0/v1/v2 (writes=$prologueWrites reads=$prologueReads); the guard " +
                    "inserts at offset 3 and reads the MethodCall from v1"
            )
        }
        val scratch = adMob.deadLocalsFrom(startIndex = 3, excluded = setOf(0, 1, 2))
        if (scratch.size < 2) {
            throw PatchException(
                "Nepali Patro: AdMob onMethodCall offers only ${scratch.size} clobberable local(s) " +
                    "in v0..v$MAX_NIBBLE_REGISTER after the prologue ($scratch), need 2"
            )
        }
        adMob.insertProgram(
            3,
            admobLoadGuard(scratch = scratch[0], needle = scratch[1]),
            "AdMob onMethodCall load/show guard",
        )

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
        // `.locals 0`, so p0..p3 *are* v0..v3 and every one is nibble-representable. v0 (the
        // receiver) is the only register the original delegate does not need, so it doubles as
        // scratch - its body is a single `invoke-virtual {p1, p2, p3}` that never reads v0.
        val readsReceiver = urlImplementation.instructions.any { instruction ->
            0 in registersOf(instruction)
        }
        if (readsReceiver) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl reads register 0; the URL gate needs it as " +
                    "scratch because the method is .locals 0"
            )
        }
        val urlSlots = urlLoader.parameterTypes.size + 1
        val urlRegister = urlImplementation.registerCount - urlSlots + 2
        if (urlRegister > MAX_NIBBLE_REGISTER) {
            throw PatchException(
                "Nepali Patro: WebViewProxyApi.loadUrl url register v$urlRegister exceeds the " +
                    "v$MAX_NIBBLE_REGISTER limit of the 22c/21c register encodings"
            )
        }
        urlLoader.insertProgram(
            0,
            webViewLoadUrlGuard(urlRegister),
            "WebViewProxyApi.loadUrl host gate",
        )
    }
}
