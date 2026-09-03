package app.franticg33k.patches.fricam.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_FRICAM = Compatibility(
        name = "Fricam",
        packageName = "com.tgezginis.fricam",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1F6FEB,
        targets = listOf(
            AppTarget(
                version = "1.3.7",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
