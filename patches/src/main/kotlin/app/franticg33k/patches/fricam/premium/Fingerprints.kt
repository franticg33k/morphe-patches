package app.franticg33k.patches.fricam.premium

import app.morphe.patcher.Fingerprint

// Fricam's billing classes are R8-obfuscated (w70 = PurchaseManager, az4 = entitlement
// helpers). Anchor on the stable SharedPreferences keys and the RevenueCat entitlement id
// instead of class names, which change between versions.

// The single RevenueCat entitlement check: extracts CustomerInfo.getEntitlements().get("fricam_pro")
// and returns isActive(). Forcing it true unlocks Pro on every sync/purchase/restore path and even
// writes the sticky legacy_pro_grant. (audit: P1)
object RevenueCatEntitlementActiveFingerprint : Fingerprint(
    name = "b",
    returnType = "Z",
    parameters = listOf("Lcom/revenuecat/purchases/CustomerInfo;"),
    strings = listOf("fricam_pro"),
)

// The master UI gate. Reads "frigate" prefs "demo_mode" (true => all free) else
// "fricam_billing" prefs "pro_unlocked". Forcing it true makes every feature gate (home grid,
// widgets, follow tab) deterministic regardless of local prefs. (audit: P2)
object MasterProGateFingerprint : Fingerprint(
    name = "K",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("frigate", "demo_mode", "fricam_billing", "pro_unlocked"),
)

// The sole writer of pro_unlocked. Sets the Compose StateFlow and persists the flag via
// SharedPreferences$Editor.putBoolean. Forcing the argument true means a later non-premium
// RevenueCat refresh can never downgrade the local entitlement. (audit: P3 hardening)
object PersistProFlagFingerprint : Fingerprint(
    name = "d",
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("pro_unlocked"),
)

// Neutralize the PairIP Play Store licensing that gates the app on launch. Called from
// com.pairip.application.Application.attachBaseContext.
object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)
