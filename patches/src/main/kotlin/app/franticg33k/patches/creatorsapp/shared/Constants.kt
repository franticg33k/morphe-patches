package app.franticg33k.patches.creatorsapp.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_CREATORSAPP = Compatibility(
        name = "Creators' App",
        packageName = "jp.co.sony.ips.portalapp",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1f1f1f,
        targets = listOf(
            // 3.5.0 is the version the upstream smali patcher was written and runtime-tested
            // against; 3.6.0 is the build the fingerprints here were resolved on.
            AppTarget(version = "3.5.0", isExperimental = true, minSdk = null),
            AppTarget(version = "3.6.0", isExperimental = false, minSdk = null),
        ),
    )

    const val EXTENSION = "Lapp/franticg33k/extension/creatorsapp/ZvE10;"
    const val APP = "Ljp/co/sony/ips/portalapp"
}
