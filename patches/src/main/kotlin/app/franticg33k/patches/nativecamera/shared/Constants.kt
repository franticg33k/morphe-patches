package app.franticg33k.patches.nativecamera.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_NATIVECAMERA = Compatibility(
        name = "Native Camera",
        packageName = "com.rawcam.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xd0bcff,
        targets = listOf(
            AppTarget(
                version = "1.4",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
