package app.franticg33k.patches.jellywatch.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_JELLYWATCH = Compatibility(
        name = "JellyWatch",
        packageName = "com.jellywatch.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x008577,
    )
}
