package app.franticg33k.patches.jellywatchtv.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    // Package-name-only (AGENTS.md rule 14). 0570 -> 0845 changed the PremiumStatus class name
    // and constructor signature; nothing in the patch pins either (the class is found by its
    // generated toString literal, the field by what that toString reads), so a version pin would
    // only have stopped the patch being offered where it still works.
    val COMPATIBILITY_JELLYWATCH_TV = Compatibility(
        name = "JellyWatch TV",
        packageName = "com.jellywatch.tv",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x008577,
    )
}
