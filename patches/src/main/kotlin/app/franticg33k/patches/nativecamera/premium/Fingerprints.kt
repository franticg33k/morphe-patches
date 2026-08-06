package app.franticg33k.patches.nativecamera.premium

import app.morphe.patcher.Fingerprint

// Anchored on the stable SharedPreferences keys / log strings instead of the obfuscated
// CameraViewModel class name, which can change between APK versions.
object CameraViewModelPremiumInitFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/app/Application;"),
    strings = listOf("is_premium", "rawcam_prefs"),
)

object CameraViewModelSetPremiumFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("setPremium called with value: "),
)

object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)
