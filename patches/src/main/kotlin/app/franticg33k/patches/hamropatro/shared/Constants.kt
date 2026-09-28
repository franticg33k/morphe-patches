package app.franticg33k.patches.hamropatro.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    // Pinned to a single version on purpose. The Remove Ads patch anchors on three obfuscated
    // parameter types (Lp05;, Lq05;, Lr05;) which rotate on every app build - they were
    // Lyq7;/Lzq7;/Lar7; one release earlier at v10.7.30. Advertising the patch for "any" version
    // would offer it on builds whose symbols have already moved, and the failure is a hard
    // Fingerprint exception rather than a graceful skip. A target list makes the manager offer
    // this only where it has been verified, and adding a new version is a deliberate act:
    // re-pin the three types, re-run apks/extracted/hamropatro-analysis/hamro_fp_harness.py, then
    // append an AppTarget here.
    val COMPATIBILITY_HAMROPATRO = Compatibility(
        name = "Hamropatro",
        packageName = "com.hamropatro",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE2231A,
        targets = listOf(
            AppTarget(
                version = "10.7.33",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
