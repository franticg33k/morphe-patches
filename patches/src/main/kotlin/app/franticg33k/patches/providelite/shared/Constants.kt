package app.franticg33k.patches.providelite.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_PROVIDELITE = Compatibility(
        name = "Provide Lite",
        packageName = "com.providelite.epicplay",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1E88E5,
    )
}
