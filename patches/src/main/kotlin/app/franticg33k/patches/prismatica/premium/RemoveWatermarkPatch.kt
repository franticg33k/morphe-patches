package app.franticg33k.patches.prismatica.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.prismatica.shared.Constants.COMPATIBILITY_PRISMATICA

@Suppress("unused")
val removePrismaticaWatermarkPatch = rawResourcePatch(
    name = "Remove Watermark",
    description = "Patches the procedural GLSL shader in libharwin_native.so to disable the \"PRISMATICA\" watermark in the video player and exported videos.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PRISMATICA)

    execute {
        val libFile = get("lib/arm64-v8a/libharwin_native.so", false)
        val bytes = libFile.readBytes()
        // The watermark is a procedural GLSL shader rendered in the fragment shader.
        // Condition: if (uIsPro == 0) -> renders watermark text
        // We change '0' to '2' so the condition is never met (uIsPro is only 0 or 1).
        val target = "uIsPro == 0".toByteArray()
        val replacement = "uIsPro == 2".toByteArray()
        val index = bytes.indexOfFirst(target)
        if (index == -1) error("Watermark pattern not found in libharwin_native.so")
        replacement.forEachIndexed { i, byte -> bytes[index + i] = byte }
        libFile.writeBytes(bytes)
    }
}

private fun ByteArray.indexOfFirst(pattern: ByteArray): Int {
    for (i in 0..size - pattern.size) {
        var match = true
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) { match = false; break }
        }
        if (match) return i
    }
    return -1
}