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

// P3 hardening - REMOVED for 1.6.5, deliberately.
//
// In 1.3.x/1.4.0.1 there was a standalone pro_unlocked writer: a (Z)V method that persisted the
// flag through SharedPreferences$Editor.putBoolean, where forcing the argument true meant a later
// non-premium RevenueCat refresh could never downgrade the local entitlement.
//
// In 1.6.5 no such method exists anywhere in the APK. Persistence was folded into the
// entitlement sync method (EdgeEntitlementActiveFingerprint below), which writes
// `putBoolean("pro_unlocked", v1)` where v1 is the combined pro||edge boolean. Leaving the
// fingerprint in place would abort the whole patch on a Fingerprint miss, so it is removed rather
// than left to fail.
//
// The hardening is not lost, it is subsumed: v1 is assigned from v0, and v0 is the result of the
// RevenueCat pro check that RevenueCatEntitlementActiveFingerprint already forces to true. The
// persisted flag therefore cannot be written false, which is exactly what P3 was for.

// The entitlement sync / publish method. In 1.4.0.1 this was (CustomerInfo, Z)V and persisted
// the sticky `legacy_pro_grant`; in 1.6.5 the Z parameter is gone, `legacy_pro_grant` no longer
// exists anywhere in the APK, and the same method now writes `pro_unlocked` instead. The
// published value is the combined (pro || edge) flag, so forcing it true unlocks both.
// (1.4.0.1: z70.a  ->  1.6.5: Lua0.a) (audit: Edge gate)
//
// The two strings are required together: `fricam_edge` also appears in unrelated coroutine
// builders, so it alone is not unique, while (CustomerInfo)V + fricam_edge + pro_unlocked
// resolves to exactly one method.
object EdgeEntitlementActiveFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/revenuecat/purchases/CustomerInfo;"),
    strings = listOf("fricam_edge", "pro_unlocked"),
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
