package app.franticg33k.patches.prismatica.premium

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.prismatica.shared.Constants.COMPATIBILITY_PRISMATICA
import java.util.logging.Logger

/*
 * Removes Prismatica's free-tier watermark by retargeting the one branch that draws it.
 *
 * Where the watermark lives
 * -------------------------
 * Prismatica has no watermark bitmap and no text overlay: the "PRISMATICA" mark is drawn
 * procedurally in GLSL, glyph by glyph, at the end of the main() of a single embedded fragment
 * shader (~73 kB of source, compiled in at link time into libharwin_native.so; the files under
 * `assets/shaders/` are stale leftovers from an older renderer - fragment_grade.glsl there has
 * neither `uIsPro` nor a watermark and is never loaded). The same shader renders the live
 * preview and the GPU export path (nativeApvEncoderEncodeFromGPU / glReadPixels off the graded
 * FBO), which is why one edit covers both.
 *
 * The gate
 * --------
 * The branch is `if (uIsPro == N) { ... }` where `uIsPro` is a `uniform int` filled from one
 * place: `glGetUniformLocation(prog, "uIsPro")` -> state+0x2e0, and the only two readers of
 * that location do `glUniform1i(loc, state->flags[0x1ac])` (0x32f91c, 0x335f2c in 1.5.8).
 * `state->flags[0x1ac]` has exactly one writer in the whole module, in
 * Java_com_harwin_prismatica_NativeBridge_nativeSetIsPro:
 *
 *     0x324034  tst  w19, #0xff
 *     0x324038  cset w3, ne
 *     0x32403c  str  w3, [x8, #0x1ac]
 *
 * so uIsPro is 0 or 1 - it is never 2 and never anything else. The gate literal therefore has
 * two shapes in the wild:
 *
 *   `if (uIsPro == 0)`  <= 1.5.7. Free tier is 0, so the watermark draws for every non-paying
 *                       user. This is a live gate and the edit below disables it.
 *   `if (uIsPro == 2)`  1.5.8, and any build this patch has already run on. Unreachable: the
 *                       branch is dead code and the app shows no shader watermark in *any*
 *                       tier. Nothing to remove, so we say so rather than fail.
 *
 * 1.5.8 ships the `== 2` form, which is why the previous version of this patch - anchored on
 * the literal string "uIsPro == 0" - stopped applying (zero matches, hard error). Reporting the
 * dead gate as "already watermark-free" instead is the idempotency rule: recognise the state
 * you would have produced and do not mistake it for a build we cannot handle.
 *
 * Why the branch is not simply NOP'd, and why the native writer is not flipped instead
 * ----------------------------------------------------------------------------------
 * Both alternatives were measured on 1.5.8 and rejected:
 *
 *  - Changing the flag writer (`cset w3, ne` -> `mov w3, #1`) asserts a 16-byte anchor that is
 *    unique for this exact build, but it changes uIsPro from 0 to 1 and the only consumer tests
 *    `== 2`. On 1.5.8 that is provably a no-op: a patch that applies and changes nothing.
 *  - Deleting the watermark body would break the shader's syntax unless every brace and const
 *    came with it, and a shader compile failure is a black preview, not a removed mark.
 *
 * The string edit is one byte in, one byte out, in a blob whose surrounding text is stable
 * across builds, and it needs no offset bookkeeping. When it stops matching, re-read the shader
 * (see `docs/prismatica-premium-analysis-1.5.8.md`) and add the new literal shape to GATES.
 */

private val logger = Logger.getLogger("morphe.prismatica.watermark")

/** Declaration of the uniform the gate reads. Present exactly once; proves we found the shader. */
private val UNIFORM_DECL = "uniform int uIsPro;".toByteArray()

/** Head of the only watermark branch in that shader. The literal follows immediately. */
private val GATE_HEAD = "if (uIsPro == ".toByteArray()

/** Gate literals we understand: '0' is live and gets rewritten, '2' is dead and is already done. */
private const val FREE_GATE_LITERAL = '0'
private const val DEAD_GATE_LITERAL = '2'

@Suppress("unused")
val removePrismaticaWatermarkPatch = rawResourcePatch(
    name = "Remove Watermark",
    description = "Removes the \"PRISMATICA\" watermark from the live preview and GPU exports by " +
        "retargeting the free-tier gate of the procedural GLSL fragment shader embedded in " +
        "libharwin_native.so: `if (uIsPro == 0)` becomes `if (uIsPro == 2)`, a value the renderer " +
        "can never pass because the uniform is filled from one native field that only holds 0 or 1. " +
        "Builds that already gate on `uIsPro == 2` (1.5.8 and later) have the branch unreachable " +
        "and are reported as already watermark-free instead of failing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PRISMATICA)

    execute {
        val libFile = get("lib/arm64-v8a/libharwin_native.so", false)
        val bytes = libFile.readBytes()

        val declarations = findAll(bytes, UNIFORM_DECL)
        if (declarations.size != 1) {
            throw PatchException(
                "Prismatica: expected the fragment shader's `uniform int uIsPro;` exactly once " +
                    "in libharwin_native.so, found ${declarations.size}. The shader layout moved - " +
                    "dump the embedded GLSL strings again (strings with a #version 300 es head) " +
                    "and re-anchor UNIFORM_DECL/GATE_HEAD on the new text; do not guess an offset.",
            )
        }

        val gates = findAll(bytes, GATE_HEAD)
        if (gates.size != 1) {
            throw PatchException(
                "Prismatica: expected exactly one `if (uIsPro == ` watermark gate, found " +
                    "${gates.size} at ${gates.joinToString { "0x${it.toString(16)}" }}. " +
                    "A second gate means the watermark grew a second branch, which this patch " +
                    "does not cover; a zero count means the branch was renamed. Re-derive it " +
                    "from the shader source before shipping.",
            )
        }

        val gate = gates.single()
        val literalAt = gate + GATE_HEAD.size
        if (literalAt >= bytes.size) {
            throw PatchException("Prismatica: watermark gate at 0x${gate.toString(16)} is truncated")
        }

        when (bytes[literalAt].toInt().toChar()) {
            FREE_GATE_LITERAL -> {
                bytes[literalAt] = DEAD_GATE_LITERAL.code.toByte()
                libFile.writeBytes(bytes)
                report(
                    "Prismatica: watermark gate patched at 0x${gate.toString(16)} " +
                        "(`uIsPro == 0` -> `uIsPro == 2`); the branch is now unreachable " +
                        "because uIsPro is only ever 0 or 1.",
                )
            }

            DEAD_GATE_LITERAL -> report(
                "Prismatica: watermark gate at 0x${gate.toString(16)} already reads " +
                    "`uIsPro == 2`. The uniform is written from one native field that is only " +
                    "0 or 1 (nativeSetIsPro, cset w3, ne), so the branch is dead code and this " +
                    "build draws no shader watermark in any tier - nothing to edit.",
            )

            else -> throw PatchException(
                "Prismatica: watermark gate at 0x${gate.toString(16)} reads " +
                    "`${GATE_HEAD.decodeToString()}${bytes[literalAt].toInt().toChar()})`, which " +
                    "this patch does not know. Read the branch in the embedded shader and decide " +
                    "whether it is live for the free tier before adding it to GATES.",
            )
        }
    }
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

/** Every start index of [pattern] in this array. Overlapping matches included; callers count them. */
private fun findAll(haystack: ByteArray, pattern: ByteArray): List<Int> {
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
