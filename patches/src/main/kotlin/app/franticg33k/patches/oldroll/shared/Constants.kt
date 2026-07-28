package app.franticg33k.patches.oldroll.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_OLDROLL = Compatibility(
        name = "OldRoll",
        packageName = "com.lightcone.analogcam",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF6B35,
    )
}
