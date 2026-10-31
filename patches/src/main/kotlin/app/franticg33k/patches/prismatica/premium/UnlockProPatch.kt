package app.franticg33k.patches.prismatica.premium

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.prismatica.shared.Constants.COMPATIBILITY_PRISMATICA
import java.util.logging.Logger

/*
 * Unlocks Prismatica's Pro tier by pinning one Dart AOT field at its single writer.
 *
 * The model
 * ---------
 * Prismatica's entitlement is a plain Dart bool, `PurchaseService._pro`, and nothing else:
 * there is no receipt, no signature and no server check in the client. It reaches the native
 * renderer only as a `setIsPro` method-channel argument, and the only native consumer of that
 * is a `uIsPro == 2` test in the embedded GLSL shader (see RemoveWatermarkPatch - on 1.5.8
 * that branch is already unreachable in every tier).
 *
 * In the AOT snapshot (Dart 3.13.4, arm64-v8a) the field is `field_27` of class id 1643
 * (`PurchaseService`, size 0x54) and it holds the canonical tagged bool - the *same* value a
 * real purchase produces:
 *
 *     false   0x9100c2c0   add x0, NULL, #0x30     bit 4 set
 *     true    0x910082c0   add x0, NULL, #0x20     bit 4 clear
 *
 * Every consumer loads it and branches on one bit, with no other state involved:
 *
 *     ldur  w1, [x0, #0x27]
 *     add   x1, x1, HEAP, lsl #32
 *     tbz   w1, #4, <pro path>      ; bit clear == true == Pro
 *
 * so "Pro" is bit 4 clear. The four gated sites in `projects_page.dart` (0x5d7588, 0x5d7b8c,
 * 0x5e3320, 0x5f5e3c) are the Batch Export and cross-project grade copy/paste paywalls, plus the
 * "Pro" menu entry. `showProOfferSheet` itself has seven call sites; the other three are plain
 * buttons that never test the flag (0x5c8aa4, 0x5d19bc, 0x603b9c). The only other reader of the
 * field in the whole snapshot is a `() => PurchaseService._instance.field_27` closure installed by
 * `BatchVideoExportPreparer` (0x5f1ce8, called at 0x5ef2bc) - a reader, not a gate. All grading
 * tools are free and are not touched by this patch.
 *
 * Why the writer, not the readers
 * -------------------------------
 * Rule 7: patch the single writer, not the many consumers of a flag. In the whole snapshot
 * `field_27` has exactly four stores, all inside `purchase_service.dart`:
 *
 *     0x5f1d9c   PurchaseService._internal     false   (constructor default; initialize overwrites)
 *     0x7c9a40   PurchaseService.initialize    getBool("pro_purchase_confirmed") ?? false
 *     0x7c9a58   PurchaseService.initialize    false   (the async try/catch handler)
 *     0x7ca06c   PurchaseService._grantEntitlement  true  (a real, acknowledged purchase)
 *
 * `_queryActivePurchases` never writes it, and `is_pro_premium` (the SharedPreferences key
 * written by `_persistEntitlement`) is write-only in this snapshot - nothing reads it back.
 * So `initialize` is the startup truth source and the only writer that can *reset* the flag
 * after construction. Pinning it there covers all four gated sites at once and cannot be
 * undone by a later purchase-stream callback, which only ever moves the field to true.
 *
 * The two edits (length-preserving, 12 bytes total)
 * -----------------------------------------------
 * 1. success path, 0x7c9a34..0x7c9a3c:
 *
 *        0x7c9a30  cmp  w0, NULL          ; getBool returned null?
 *        0x7c9a34  b.ne 0x7c9a3c          ; ---- becomes  b 0x7c9a38
 *        0x7c9a38  add  x0, NULL, #0x30   ; ---- becomes  add x0, NULL, #0x20
 *        0x7c9a3c  ldur x2, [fp, #-0x68]
 *        0x7c9a40  stur w0, [x2, #0x27]
 *
 *    The conditional is retargeted (not NOP'd) at the *false* materialisation, and that
 *    materialisation is then rewritten to `true`. Only r0 is touched, and r0 is the value
 *    about to be stored into field_27 - the surrounding register state (x2 = `this`, fp) is
 *    intact, and the null-coalesce branch is the same one the compiler emits for
 *    `prefs.getBool(k) ?? false`.
 *
 * 2. async catch handler, 0x7c9a54:
 *
 *        0x7c9a44  b    0x7c9a5c          ; success path skips the handler
 *        0x7c9a48  sub  sp, fp, #0x88
 *        0x7c9a4c  ldur x2, [fp, #-0x68]
 *        0x7c9a50  add  x3, NULL, #0x30   ; dead copy of the compiler's false materialisation
 *        0x7c9a54  add  x3, NULL, #0x30   ; ---- becomes  add x3, NULL, #0x20
 *        0x7c9a58  stur w3, [x2, #0x27]
 *
 *    Reached only if `SharedPreferences.getInstance()`/`getBool` throws. Left alone it would
 *    hand the paywall back on a device whose prefs load fails, so it is pinned too. x3 is
 *    dead after the store (0x7c9a5c reloads x1 from x2), so nothing else observes the change.
 *
 * Deliberately not done
 * ---------------------
 *  - Patching the readers instead. Four sites plus the paywall sheet, more edit surface, and
 *    it has to be redone for every future gate; the writer is one place.
 *  - Flipping `_grantEntitlement` / `_persistEntitlement` / the Pigeon purchase flow. Those
 *    need a real Play Billing transaction; the app never trusts a locally forged one, and
 *    `is_pro_premium` is never read back, so writing it would change nothing.
 *  - Injecting `pro_purchase_confirmed` into FlutterSharedPreferences from the device side.
 *    The server/Dart code rewrites that file on launch, and this repo does not ship state
 *    injection as a patch anyway.
 *
 * Evidence and status
 * -------------------
 * static: the anchors below are read out of the 1.5.8 snapshot
 * (apks/extracted/prismatica-blutter, Dart 3.13.4, snapshot hash 0451907c...), each occurs
 * exactly once in libapp.so, and the value written is byte-identical to the one the app's own
 * `_grantEntitlement` writes for a real purchase.
 *
 * packaging: proven with tools/patch.py - the edited entry reads back changed and the merged
 * split set stays installable.
 *
 * runtime: NOT exercised. No device or emulator was attached in the environment where this
 * was written, so this patch is statically and packaging-verified only. See
 * docs/prismatica-premium-analysis-1.5.8.md before calling it fixed.
 */

private val logger = Logger.getLogger("morphe.prismatica.pro")

/**
 * `initialize` success path, 0x7c9a30..0x7c9a44. Every instruction here is intrinsic - no
 * PC-relative call, no pool index - so a rebuild that keeps the same shape keeps the anchor.
 */
private val INIT_SUCCESS_ANCHOR = byteArrayOf(
    0x1f, 0x00, 0x16, 0x6b,                         // cmp  w0, NULL
    0x41, 0x00, 0x00, 0x54,                         // b.ne #0x7c9a3c
    0xc0.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte(), // add  x0, NULL, #0x30   (false)
    0xa2.toByte(), 0x83.toByte(), 0x59, 0xf8.toByte(), // ldur x2, [fp, #-0x68]
    0x40, 0x70, 0x02, 0xb8.toByte(),                // stur w0, [x2, #0x27]
)

/** Offset of the 8 edited bytes inside [INIT_SUCCESS_ANCHOR] (`b.ne` + the false literal). */
private const val INIT_SUCCESS_EDIT_AT = 4

private val INIT_SUCCESS_FROM = byteArrayOf(
    0x41, 0x00, 0x00, 0x54,                         // b.ne #0x7c9a3c
    0xc0.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte(), // add  x0, NULL, #0x30
)

private val INIT_SUCCESS_TO = byteArrayOf(
    0x01, 0x00, 0x00, 0x14,                         // b    #0x7c9a38
    0xc0.toByte(), 0x82.toByte(), 0x00, 0x91.toByte(), // add  x0, NULL, #0x20   (true)
)

/** `initialize` async catch handler, 0x7c9a44..0x7c9a60. */
private val INIT_CATCH_ANCHOR = byteArrayOf(
    0x06, 0x00, 0x00, 0x14,                         // b    #0x7c9a5c
    0xaf.toByte(), 0x23, 0x02, 0xd1.toByte(),       // sub  sp, fp, #0x88
    0xa2.toByte(), 0x83.toByte(), 0x59, 0xf8.toByte(), // ldur x2, [fp, #-0x68]
    0xc3.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte(), // add  x3, NULL, #0x30   (dead copy)
    0xc3.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte(), // add  x3, NULL, #0x30
    0x43, 0x70, 0x02, 0xb8.toByte(),                // stur w3, [x2, #0x27]
    0xe1.toByte(), 0x03, 0x02, 0xaa.toByte(),       // mov  x1, x2
)

/** Offset of the edited `add x3, NULL, #0x30` inside [INIT_CATCH_ANCHOR]. */
private const val INIT_CATCH_EDIT_AT = 16

private val INIT_CATCH_FROM = byteArrayOf(
    0xc3.toByte(), 0xc2.toByte(), 0x00, 0x91.toByte(), // add x3, NULL, #0x30
)

private val INIT_CATCH_TO = byteArrayOf(
    0xc3.toByte(), 0x82.toByte(), 0x00, 0x91.toByte(), // add x3, NULL, #0x20   (true)
)

@Suppress("unused")
val unlockPrismaticaProPatch = rawResourcePatch(
    name = "Unlock Pro",
    description = "Unlocks Prismatica Pro by pinning PurchaseService._pro at its single startup " +
        "writer in the Dart AOT snapshot (libapp.so). The pro flag is a plain bool with no " +
        "receipt or signature check; `initialize` restores it from one SharedPreferences bool " +
        "and every paywall reads one bit of it. The patch retargets the null-coalesce branch " +
        "and rewrites the false literal to the exact value a real purchase writes (NULL+0x20), " +
        "and does the same in the async catch handler, so no Pro feature can fall back to the " +
        "paywall. Covered: Batch Export, cross-project grade copy/paste. Grading tools are free " +
        "and unchanged. Statically and packaging verified on 1.5.8; not yet exercised on a device.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PRISMATICA)

    execute {
        val libFile = get("lib/arm64-v8a/libapp.so", false)
        val bytes = libFile.readBytes()
        val edits = listOf(
            AnchorEdit(
                label = "PurchaseService::initialize (pro restore)",
                anchor = INIT_SUCCESS_ANCHOR,
                editAt = INIT_SUCCESS_EDIT_AT,
                from = INIT_SUCCESS_FROM,
                to = INIT_SUCCESS_TO,
                meaning = "getBool(\"pro_purchase_confirmed\") ?? false  ->  always true",
            ),
            AnchorEdit(
                label = "PurchaseService::initialize (async catch)",
                anchor = INIT_CATCH_ANCHOR,
                editAt = INIT_CATCH_EDIT_AT,
                from = INIT_CATCH_FROM,
                to = INIT_CATCH_TO,
                meaning = "catch { _pro = false }  ->  _pro = true",
            ),
        )

        var written = 0
        for (edit in edits) {
            val pristine = findAllIndices(bytes, edit.anchor)
            if (pristine.size == 1) {
                val at = pristine.single()
                assertBytes(bytes, at + edit.editAt, edit.from, edit.label)
                edit.to.copyInto(bytes, at + edit.editAt)
                written++
                report(
                    "Prismatica: ${edit.label} patched at 0x${(at + edit.editAt).toString(16)} " +
                        "(${edit.meaning}).",
                )
                continue
            }

            val already = findAllIndices(bytes, edit.patchedAnchor)
            if (pristine.isEmpty() && already.size == 1) {
                report(
                    "Prismatica: ${edit.label} at 0x${already.single().toString(16)} is already " +
                        "patched (${edit.meaning}); leaving it alone.",
                )
                continue
            }

            throw PatchException(
                "Prismatica: could not locate ${edit.label} in libapp.so. Expected its " +
                    "${edit.anchor.size}-byte anchor exactly once, found ${pristine.size} " +
                    "unpatched and ${already.size} already-patched matches. " +
                    "Observed bytes at the first unpatched candidate " +
                    "(${pristine.firstOrNull()?.let { "0x${it.toString(16)}" } ?: "none"}): " +
                    "${pristine.firstOrNull()?.let { hexAt(bytes, it + edit.editAt, 8) } ?: "n/a"}; " +
                    "expected ${hex(edit.from)}. " +
                    "libapp.so is a Dart AOT snapshot and every offset in this patch is pinned to " +
                    "1.5.8 (Dart 3.13.4, snapshot hash 0451907c2eaa8467e848c0067bfe8ed4). An app " +
                    "update must fail here rather than edit something else: re-run " +
                    "`python tools/blutter_stage.py --package com.harwin.prismatica`, open " +
                    "asm/harwins_prismatica_pro/services/purchase_service.dart, re-read " +
                    "PurchaseService::initialize, and update the anchor plus the assertion text " +
                    "in UnlockProPatch.kt together. Do not guess an offset.",
            )
        }

        when {
            written == edits.size -> {
                libFile.writeBytes(bytes)
                report(
                    "Prismatica: wrote ${edits.size} edits into lib/arm64-v8a/libapp.so. " +
                        "PurchaseService._pro now initialises to true on every launch, so the " +
                        "Batch Export and cross-project grade paywalls stay closed. Static and " +
                        "packaging verified; not yet exercised on a device.",
                )
            }

            written == 0 -> report(
                "Prismatica: all ${edits.size} pro-state edits are already present; " +
                    "libapp.so was not rewritten.",
            )

            else -> {
                libFile.writeBytes(bytes)
                report(
                    "Prismatica: wrote $written of ${edits.size} edits into " +
                        "lib/arm64-v8a/libapp.so (the rest were already patched).",
                )
            }
        }
    }
}

/** One byte-level replacement, plus the anchor that proves we are editing the intended site. */
private class AnchorEdit(
    val label: String,
    val anchor: ByteArray,
    val editAt: Int,
    val from: ByteArray,
    val to: ByteArray,
    val meaning: String,
) {
    init {
        require(from.size == to.size) { "$label: length-preserving edits only" }
        require(editAt + from.size <= anchor.size) { "$label: edit range escapes its anchor" }
        require(anchor.copyOf(editAt + from.size).takeLast(from.size).toByteArray().contentEquals(from)) {
            "$label: `from` does not match the anchor it sits in"
        }
    }

    /** The anchor as it reads after this edit - i.e. our own output. */
    val patchedAnchor: ByteArray = anchor.copyOf().also { to.copyInto(it, editAt) }
}

/**
 * A patch's own output is dropped by the desktop CLI unless it is printed, and dropped by the
 * manager UI unless it is logged, so say it both ways. Only reached for the informational
 * ("already in the state we want") cases - everything unexpected throws a PatchException.
 */
private fun report(message: String) {
    logger.info(message)
    println(message)
}

private fun assertBytes(bytes: ByteArray, at: Int, expected: ByteArray, label: String) {
    if (at < 0 || at + expected.size > bytes.size) {
        throw PatchException(
            "Prismatica: $label edit range 0x${at.toString(16)}.." +
                "0x${(at + expected.size).toString(16)} is outside libapp.so " +
                "(${bytes.size} bytes).",
        )
    }
    val actual = bytes.copyOfRange(at, at + expected.size)
    if (!actual.contentEquals(expected)) {
        throw PatchException(
            "Prismatica: $label mismatch at 0x${at.toString(16)}: expected " +
                "${hex(expected)}, got ${hex(actual)}. Do not re-run against an edited file; " +
                "re-pull the APK and start from the pristine snapshot.",
        )
    }
}

/** Every start index of [pattern] in this array. Overlapping matches included; callers count them. */
private fun findAllIndices(haystack: ByteArray, pattern: ByteArray): List<Int> {
    val hits = mutableListOf<Int>()
    if (pattern.isEmpty()) return hits
    for (start in 0..haystack.size - pattern.size) {
        var matched = true
        for (i in pattern.indices) {
            if (haystack[start + i] != pattern[i]) {
                matched = false
                break
            }
        }
        if (matched) hits.add(start)
    }
    return hits
}

private fun hexAt(bytes: ByteArray, at: Int, size: Int): String {
    if (at < 0 || at >= bytes.size) return "n/a"
    return hex(bytes.copyOfRange(at, minOf(at + size, bytes.size)))
}

private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
