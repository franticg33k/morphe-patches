package app.franticg33k.patches.marriagedroid.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_MARRIAGEDROID = Compatibility(
        name = "Marriage Card Game",
        packageName = "sapan.marriagedroid",
        appIconColor = 0xC62828,
        // 15.6 (versionCode 81) is the only build this has been checked against. Two of the
        // three anchors are vendor SDK call shapes (Unity LevelPlay's own
        // `isAdReady`/`showAd`/`loadAd`), not obfuscated app names; the third (the rewarded
        // entry point) is matched by shape because the app's own names are R8 letters. A new
        // release is still one-version-ahead per AGENTS.md: re-check all three, do not assume.
        targets = listOf(
            AppTarget(
                version = "15.6",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
