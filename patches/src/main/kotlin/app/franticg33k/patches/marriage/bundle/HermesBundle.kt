package app.franticg33k.patches.marriage.bundle

import app.morphe.patcher.patch.PatchException
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger

/*
 * A length-preserving editor for the React Native Hermes bytecode bundle.
 *
 * Marriage ships no useful dex surface for this work: the economy, the ad flow and the gift
 * timer all live in `assets/index.android.bundle`, a Hermes bytecode file (HBC v96) that the
 * app's own `libhermes.so` executes. There is no Java/Kotlin layer to fingerprint - the whole
 * application is JavaScript compiled ahead of time - so these patches edit the bundle itself,
 * the way a `libapp.so` patch edits a Flutter AOT snapshot (see .agents/playbook.md, "Web / JS
 * (Hermes, RN)").
 *
 * Three properties make that safe to do from a patch:
 *
 *  1. Every edit is the same number of bytes in and out. Hermes has no re-layout pass, so an
 *     instruction may be swapped for one of its own encoding width but never added or removed.
 *     Where an edit is shorter than the instruction it replaces (a 2-byte `Jmp` for a 4-byte
 *     `LoadFromEnvironment`), the tail is filled with `Unreachable` (0x00) - an opcode Hermes
 *     traps on, so if the flow ever reached it the failure is loud rather than a wild jump.
 *
 *  2. The bytes are located by signature, never by raw offset. Every site is a hex needle taken
 *     from a disassembly of the build it was verified on (2.8.45) and must match **exactly
 *     once**; zero matches and two matches both throw. This is the same rule as a dex
 *     fingerprint's "exactly one method" (AGENTS.md section 3, rules 1 and 2), applied to a
 *     byte blob.
 *
 *  3. The bundle ends with a SHA-1 over every preceding byte, and the header repeats the file
 *     length. An edit invalidates both, so the hash is recomputed before writing. Hermes itself
 *     does not appear to verify it at load time, but a stale hash makes the file unparseable by
 *     `hermes-dec`, which is the tool the next person will reach for.
 *
 * The site tables are pinned to HBC v96 / Marriage 2.8.45. An app update shifts every offset in
 * the file, so the first thing an update does is fail here - which is the intended behaviour:
 * re-disassemble, re-locate the function, re-pin the signature.
 */

/** Archive path of the React Native bundle inside the APK. */
internal const val HERMES_BUNDLE_ENTRY = "assets/index.android.bundle"

/** Hermes magic: 0x1F1903C103BC1FC6, little-endian. */
private const val HBC_MAGIC = "c61fbc03c103191f"

/** Bytecode version this table is pinned to. */
private const val HBC_VERSION = 96L

private const val HEADER_VERSION_OFFSET = 8
private const val HEADER_FILE_LENGTH_OFFSET = 0x20
private const val TRAILING_HASH_BYTES = 20

private val logger = Logger.getLogger("morphe.marriage.hermes")

/**
 * One length-preserving edit.
 *
 * @param name what the site is, in words a log reader can act on.
 * @param signature hex of the bytes to search for. Must be unique in the bundle.
 * @param editOffset byte offset of the edit inside [signature].
 * @param expected hex of the bytes currently at that offset (asserted, not assumed).
 * @param replacement hex of the bytes to write. Must be exactly as long as [expected].
 */
internal class Site(
    val name: String,
    val signature: String,
    val editOffset: Int,
    val expected: String,
    val replacement: String,
) {
    val editLength: Int = expected.length / 2

    /** [signature] with the edit already applied - the fingerprint of "this patch ran". */
    val patchedSignature: String =
        signature.substring(0, editOffset * 2) +
            replacement +
            signature.substring((editOffset + editLength) * 2)
}

/**
 * Applies [sites] to [file] and rewrites the trailing hash.
 *
 * Idempotent: a site whose pre-image is gone but whose post-image is present counts as already
 * patched and is skipped (the manager re-applies patches over an already-patched APK when the
 * user re-runs it).
 */
internal fun editHermesBundle(file: File, sites: List<Site>) {
    if (!file.isFile) {
        throw PatchException(
            "Marriage: $HERMES_BUNDLE_ENTRY is missing (looked for ${file.absolutePath}). " +
                "This patch only applies to an APK that still carries the JavaScript bundle.",
        )
    }

    val bytes = file.readBytes()
    requireSupportedHeader(bytes)

    var changed = false
    for (site in sites) {
        if (site.expected.length != site.replacement.length) {
            throw PatchException(
                "Marriage: site '${site.name}' is not length-preserving " +
                    "(${site.expected.length / 2} bytes in, ${site.replacement.length / 2} out)",
            )
        }

        val before = findAll(bytes, site.signature.hexToBytes())
        val after = findAll(bytes, site.patchedSignature.hexToBytes())

        when {
            before.size == 1 -> {
                val base = before.single()
                val at = base + site.editOffset
                val observed = bytes.copyOfRange(at, at + site.editLength)
                val expected = site.expected.hexToBytes()

                if (!observed.contentEquals(expected)) {
                    throw PatchException(
                        "Marriage: site '${site.name}' signature matched at 0x" +
                            base.toString(16) + " but the bytes at +${site.editOffset} are " +
                            observed.toHex() + ", expected ${site.expected}. The signature and " +
                            "the edit offset disagree - do not 'fix' the offset, re-disassemble.",
                    )
                }

                site.replacement.hexToBytes().copyInto(bytes, at)
                changed = true
                logger.info(
                    "Marriage: '${site.name}' patched at 0x${base.toString(16)} " +
                        "(${site.expected} -> ${site.replacement})",
                )
            }

            before.isEmpty() && after.size == 1 -> {
                logger.info("Marriage: '${site.name}' already patched at 0x${after.single().toString(16)}")
            }

            else -> throw PatchException(missMessage(site, before.size, after.size, bytes))
        }
    }

    if (!changed) {
        logger.info("Marriage: ${file.name} needs no edits - already patched")
        return
    }

    rehash(bytes)
    file.writeBytes(bytes)
    logger.info("Marriage: wrote ${file.name} (${bytes.size} bytes, trailing SHA-1 recomputed)")
}

/** Fails early and specifically when the bundle is not the build the site table describes. */
private fun requireSupportedHeader(bytes: ByteArray) {
    if (bytes.size < HEADER_FILE_LENGTH_OFFSET + 4 + TRAILING_HASH_BYTES) {
        throw PatchException("Marriage: the bundle is ${bytes.size} bytes - too small to be Hermes bytecode")
    }
    val magic = bytes.copyOfRange(0, 8).toHex()
    if (magic != HBC_MAGIC) {
        throw PatchException(
            "Marriage: $HERMES_BUNDLE_ENTRY is not Hermes bytecode (magic $magic, expected " +
                "$HBC_MAGIC). If the app moved to a different engine, these patches do not apply.",
        )
    }
    val version = readUInt32LE(bytes, HEADER_VERSION_OFFSET)
    if (version != HBC_VERSION) {
        throw PatchException(
            "Marriage: bundle is HBC v$version, the site table is pinned to v$HBC_VERSION. " +
                "An engine change re-lays out every function - re-run hermes-dec on the new " +
                "bundle, re-locate both functions, and re-pin the signatures.",
        )
    }
    val declared = readUInt32LE(bytes, HEADER_FILE_LENGTH_OFFSET)
    if (declared != bytes.size.toLong()) {
        throw PatchException(
            "Marriage: bundle header says $declared bytes but the entry is ${bytes.size}. " +
                "The file was truncated or rewritten by something else - refusing to edit it.",
        )
    }
}

/** Hermes appends a SHA-1 over every byte before it; keep it consistent with the edited body. */
private fun rehash(bytes: ByteArray) {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(bytes, 0, bytes.size - TRAILING_HASH_BYTES)
    digest.digest().copyInto(bytes, bytes.size - TRAILING_HASH_BYTES)
}

private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private fun missMessage(site: Site, before: Int, after: Int, bytes: ByteArray): String {
    val version = readUInt32LE(bytes, HEADER_VERSION_OFFSET)
    val hex = "0x" + site.signature
    return "Marriage: site '${site.name}' did not resolve - the signature matched $before " +
        "time(s) and the patched form matched $after (expected exactly one of the two).\n" +
        "  signature   : $hex\n" +
        "  edit        : +${site.editOffset} ${site.expected} -> ${site.replacement}\n" +
        "  bundle      : HBC v$version, ${bytes.size} bytes\n" +
        "Recovery: this bundle is a different build than the one the site table was pinned to. " +
        "Disassemble the new ${HERMES_BUNDLE_ENTRY} with hermes-dec, find the function by its " +
        "neighbouring opcodes (see the comments on the site), take a fresh signature that " +
        "matches once, and replace the Site entry in the patch source - do not relax the " +
        "'exactly once' rule."
}

private fun findAll(haystack: ByteArray, needle: ByteArray): List<Int> {
    if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
    val hits = ArrayList<Int>()
    val last = haystack.size - needle.size
    var i = 0
    while (i <= last) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) hits += i
        i++
    }
    return hits
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string of odd length: $this" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}
