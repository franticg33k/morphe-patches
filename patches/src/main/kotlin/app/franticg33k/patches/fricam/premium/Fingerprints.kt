package app.franticg33k.patches.fricam.premium

import app.morphe.patcher.Fingerprint

// Fricam's billing classes are R8-obfuscated and the class AND method names drift between
// versions (w70 -> z70 = PurchaseManager, az4 -> uk8 = entitlement gate; gate method K -> c,
// persist writer d -> g). These fingerprints deliberately omit `name` so the matcher skips the
// method-name check entirely (a null name matches any rename) and instead anchors on the stable
// SharedPreferences keys / RevenueCat entitlement id plus the un-obfuscatable parameter + return
// types. Each fingerprint was verified to resolve to exactly ONE method in 1.4.0.1.

// The single RevenueCat entitlement check: extracts CustomerInfo.getEntitlements().get("fricam_pro")
// and returns isActive(). Forcing it true unlocks Pro on every sync/purchase/restore path and even
// writes the sticky legacy_pro_grant. (1.4.0.1: z70.b). (audit: P1)
object RevenueCatEntitlementActiveFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Lcom/revenuecat/purchases/CustomerInfo;"),
    strings = listOf("fricam_pro"),
)

// The master UI gate. Reads "frigate" prefs "demo_mode" (true => all free) else
// "fricam_billing" prefs "pro_unlocked". Forcing it true makes every feature gate (home grid,
// widgets, follow tab) deterministic regardless of local prefs. (1.4.0.1: uk8.c; previously az4.K)
// (audit: P2)
object MasterProGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("frigate", "demo_mode", "fricam_billing", "pro_unlocked"),
)

// The sole writer of pro_unlocked. Sets the Compose StateFlow and persists the flag via
// SharedPreferences$Editor.putBoolean. Forcing the argument true means a later non-premium
// RevenueCat refresh can never downgrade the local entitlement. (1.4.0.1: z70.g; previously
// w70.d). (audit: P3 hardening)
object PersistProFlagFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("pro_unlocked"),
)

// Neutralize the PairIP Play Store licensing that gates the app on launch. Called from
// com.pairip.application.Application.attachBaseContext. PairIP is a third-party agent left
// unrenamed by R8, so definingClass + name are stable; the string anchors guard against that
// ever changing.
object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)
