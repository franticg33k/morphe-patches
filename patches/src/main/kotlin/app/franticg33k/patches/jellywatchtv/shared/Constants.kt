package app.franticg33k.patches.jellywatchtv.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_JELLYWATCH_TV = Compatibility(
        name = "JellyWatch TV",
        packageName = "com.jellywatch.tv",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x008577,
        // 1.0.REV-0207 dropped: all five fingerprints re-verified against 1.0.REV-0570
        // (61,245 methods, each resolving to exactly one method). The PremiumStatus anchor is
        // the generated `toString` literal rather than a class name, and the PairIP classes are
        // un-obfuscated, so both survived the version jump untouched.
        targets = listOf(
            AppTarget(
                version = "1.0.REV-0570",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
