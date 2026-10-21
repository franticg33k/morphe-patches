package app.franticg33k.patches.marriage.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    /*
     * Pinned to a single version, for the same reason Hamro Patro is: these patches locate their
     * bytes by signature inside `assets/index.android.bundle`, a Hermes bytecode file whose
     * function layout changes with any JavaScript edit. Advertising the patch for "any" version
     * would offer it on builds whose offsets have moved, and the failure is a hard PatchException
     * mid-patch rather than a graceful skip - so a target list makes the manager refuse an
     * unverified build up front.
     *
     * Adding a version is a deliberate act: pull it, disassemble the bundle with hermes-dec,
     * re-locate the two functions (see the comments in economy/), re-pin the signatures, then
     * append an AppTarget here and the same version to tools/appdata/marriage.yml.
     */
    val COMPATIBILITY_MARRIAGE = Compatibility(
        name = "Marriage",
        packageName = "com.bhoos.marriage",
        appIconColor = 0xE0457B,
        targets = listOf(
            AppTarget(
                version = "2.8.45",
                isExperimental = false,
                minSdk = null,
            ),
        ),
    )
}
