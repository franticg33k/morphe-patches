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
            AppTarget(
                version = "1.4.2",
                isExperimental = false,
                minSdk = null,
            ),
            AppTarget(
                version = "1.4.4",
                isExperimental = false,
                minSdk = null,
            ),
            // 1.4.5 re-verified: the fingerprints still resolve exactly once even though R8
            // renamed the view model again (1.4.4 LR1/q;->U(Z)V -> 1.4.5 LR1/r;->W(Z)V), which
            // is the whole point of anchoring on the prefs file/key and the PairIP strings
            // instead of a name. No patch source changed. See tools/appdata/nativecamera.yml.
            AppTarget(
                version = "1.4.5",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
