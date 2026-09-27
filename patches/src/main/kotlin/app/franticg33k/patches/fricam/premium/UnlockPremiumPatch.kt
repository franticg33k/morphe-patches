package app.franticg33k.patches.fricam.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.fricam.shared.Constants.COMPATIBILITY_FRICAM

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

@Suppress("unused")
val unlockFricamPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks all Fricam Pro features for free. Fricam's Pro state terminates in a " +
        "plain SharedPreferences boolean (pro_unlocked in fricam_billing) written by a single " +
        "PurchaseManager writer; every UI feature gate re-reads it via the static master ProGate. " +
        "Fingerprints anchor on the stable prefs keys + signatures, so they survive the R8 class/method " +
        "renames between 1.3.x and 1.4.0.1. The patch forces the RevenueCat entitlement check and the " +
        "master gate to always return true (layered P1+P2), hardens the persist path so no refresh can " +
        "downgrade, and neutralizes the PairIP Play Store license check that gates the app on launch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FRICAM)

    execute {
        // P1: RevenueCat entitlement check -> always Pro. Every sync/purchase/restore path calls
        // this and unlocks; it even writes the sticky legacy_pro_grant.
        RevenueCatEntitlementActiveFingerprint.method.addInstructions(0, TRUE_RETURN)

        // P2: Master UI gate -> always Pro. The home-grid lock overlay, widget gate and Follow tab
        // all flow through the master gate (az4.K / uk8.c); forcing it true makes every feature
        // deterministic.
        MasterProGateFingerprint.method.addInstructions(0, TRUE_RETURN)

        // P3: Hardening — the sole pro_unlocked writer. Force the boolean argument so a later,
        // non-premium RevenueCat refresh can never downgrade the persisted entitlement (and the
        // Compose StateFlow it fans out to can never show a locked state).
        PersistProFlagFingerprint.method.addInstructions(0, "const/4 p1, 0x1")

        // Neutralize the PairIP Play Store licensing that would otherwise shut the app down on a
        // sideloaded/resigned build (application class calls checkLicense in attachBaseContext).
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}
