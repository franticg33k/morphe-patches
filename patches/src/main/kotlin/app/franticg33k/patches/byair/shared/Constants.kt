package app.franticg33k.patches.byair.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_BYAIR = Compatibility(
        name = "byAir",
        packageName = "com.byairapp.android",
        appIconColor = 0x1F6FEB,
        // 2.41.0 (versionCode 224) is the only build this set has been verified against, so it is
        // listed as a target: the manager then refuses an unverified build up front instead of
        // failing mid-patch on a fingerprint miss.
        //
        // The fingerprints are anchored on `ru/wearemad/...` class and member names, which byAir
        // does not obfuscate, so they survive most refactors - but not all: the 2.41.0 refresh
        // moved the whole `use_case` package, which is what breaks `Enable Online Pro` (see
        // tools/appdata/byair.yml). A new byAir release is therefore treated as one-version-ahead:
        // pull it, re-resolve the fingerprints, and add an AppTarget here rather than assuming
        // the old pin still describes the patch.
        targets = listOf(
            AppTarget(
                version = "2.41.0",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
