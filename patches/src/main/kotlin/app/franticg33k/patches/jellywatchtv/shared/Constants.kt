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
        targets = listOf(
            AppTarget(
                version = "1.0.REV-0207",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
