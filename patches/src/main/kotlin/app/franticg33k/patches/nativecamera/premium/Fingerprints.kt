package app.franticg33k.patches.nativecamera.premium

import app.morphe.patcher.Fingerprint

// Anchored on stable SharedPreferences keys / log strings instead of the obfuscated
// CameraViewModel class and method names, which change between APK versions.

// The premium entitlement is read on cold start: a getBoolean("is_premium", false) on the
// "rawcam_prefs" file, stored into the premium StateFlow. Both the prefs file name and the
// key are stable across versions.
object CameraViewModelPremiumInitFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/app/Application;"),
    strings = listOf("is_premium", "rawcam_prefs"),
)

// The premium setter: writes the "is_premium" flag and (on downgrade) resets premium-only
// features. Forcing its boolean argument true means a later non-premium billing result can
// never downgrade the local entitlement. Anchored on the (Z)V signature + the persisted
// "is_premium" key rather than any obfuscated method name, so it survives renames.
object CameraViewModelSetPremiumFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("is_premium"),
)

// Neutralize the PairIP Play Store licensing check that gates the app on launch. It is fired
// from BOTH com.pairip.application.Application.attachBaseContext and
// com.pairip.licensecheck.LicenseContentProvider.onCreate (ContentProviders run before the
// Application at process start, so the ContentProvider is the path that actually opens the
// Play Store paywall). No-oping this one method neutralizes every caller. The class name is
// intentionally not pinned: PairIP's lib package is stable, but the unique log strings below
// are a version-proof anchor that survives any class rename.
object PairipCheckLicenseFingerprint : Fingerprint(
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Cannot check license with null context.", "Skipping license check in isolated process."),
)
