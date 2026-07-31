package app.franticg33k.patches.nativecamera.premium

import app.morphe.patcher.Fingerprint

object CameraViewModelPremiumInitFingerprint : Fingerprint(
    definingClass = "LN1/g;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/app/Application;"),
    strings = listOf("is_premium", "rawcam_prefs"),
)

object CameraViewModelSetPremiumFingerprint : Fingerprint(
    definingClass = "LN1/g;",
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
