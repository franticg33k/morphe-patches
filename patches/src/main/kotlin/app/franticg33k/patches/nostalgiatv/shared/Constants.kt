package app.franticg33k.patches.nostalgiatv.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_NOSTALGIA_TV = Compatibility(
        name = "NostalgiaTV",
        packageName = "com.nostalgiatv",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xB59F7E,
        targets = listOf(
            AppTarget(
                version = "0.10.2",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
