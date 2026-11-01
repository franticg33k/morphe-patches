package app.franticg33k.patches.jellywatch.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    // Package-name-only (AGENTS.md rule 14). The old per-REV pin (2.0.REV-1712) meant the patch
    // was never offered on 1744/1758 even where it applied; the locators now decide per bundle,
    // and the shop edits assert the instruction shape they touch and fail loudly if it moves.
    val COMPATIBILITY_JELLYWATCH = Compatibility(
        name = "JellyWatch",
        packageName = "com.jellywatch.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x008577,
    )
}
