package app.franticg33k.patches.jellywatchtv.premium

import app.morphe.patcher.Fingerprint

// Anchored on the stable Kotlin data-class toString literal instead of the obfuscated
// PremiumStatus class name, which can change between APK versions.
object PremiumStatusIsPremiumFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    strings = listOf("PremiumStatus(isPremium="),
)

object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "processResponse",
    returnType = "V",
    parameters = listOf("I", "Landroid/os/Bundle;"),
)

object PairipValidateResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
    name = "validateResponse",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
)

object PairipHandleErrorFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "handleError",
    returnType = "V",
    parameters = listOf("Lcom/pairip/licensecheck/LicenseCheckException;"),
)
