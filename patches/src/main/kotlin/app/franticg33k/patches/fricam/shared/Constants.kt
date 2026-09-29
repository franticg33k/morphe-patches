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
        // 1.4.0.1 and 1.3.7 removed rather than kept alongside 1.6.5.
        //
        // They cannot coexist with the current fingerprints: the Edge entitlement method was
        // a(CustomerInfo, Z)V in 1.4.0.1 and is a(CustomerInfo)V in 1.6.5, and the literal it is
        // anchored on changed from legacy_pro_grant (gone from the APK) to pro_unlocked. On
        // 1.4.0.1 the re-pinned fingerprint therefore does not resolve, and UnlockEdgePatch's
        // "exactly one Boolean.valueOf" assertion plus its `const/4 p1, 0x1` are written against
        // the 1.6.5 body shape. Leaving the old targets listed would offer the patch on builds
        // where it aborts on a Fingerprint miss.
        //
        // Re-adding an older version means restoring that version's fingerprint set, not just an
        // AppTarget - see tools/appdata/fricam.yml.
        targets = listOf(
            AppTarget(
                version = "1.6.5",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
