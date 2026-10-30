package app.franticg33k.patches.byair.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    /**
     * Premium patches ("Enable Pro", "Enable Online Pro").
     *
     * 2.41.0 (versionCode 224) and 2.42.0 (versionCode 225) are both listed because both sets
     * of anchors resolve: the premium fingerprints were re-pinned in 2.42.0 onto data-class
     * `toString` literals and a structural body predicate, so they no longer name an obfuscated
     * class. See docs/byair-2.42.0-premium-analysis.md.
     *
     * A byAir release is still treated as one-version-ahead: pull it, re-resolve the
     * fingerprints with `python tools/fingerprints.py --app byair`, and add an AppTarget here
     * rather than assuming the previous pin still describes the patch.
     */
    val COMPATIBILITY_BYAIR = Compatibility(
        name = "byAir",
        packageName = "com.byairapp.android",
        appIconColor = 0x1F6FEB,
        targets = listOf(
            AppTarget(
                version = "2.41.0",
                isExperimental = false,
                minSdk = null,
            ),
            AppTarget(
                version = "2.42.0",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )

    /**
     * "Remove Update Prompt" only. Its anchors are name-free (see `UpdateFingerprints.kt`), so
     * it follows the premium target list.
     */
    val COMPATIBILITY_BYAIR_UPDATE = Compatibility(
        name = "byAir",
        packageName = "com.byairapp.android",
        appIconColor = 0x1F6FEB,
        targets = listOf(
            AppTarget(
                version = "2.41.0",
                isExperimental = false,
                minSdk = null,
            ),
            AppTarget(
                version = "2.42.0",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
