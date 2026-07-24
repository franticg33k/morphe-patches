package app.franticg33k.patches.jellywatch.premium

import app.morphe.patcher.Fingerprint

object PremiumStatusIsPremiumFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/model/PremiumStatus;",
    name = "isPremium",
    returnType = "Z",
)

object PremiumStatusHasFeatureFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/model/PremiumStatus;",
    name = "hasFeature",
    returnType = "Z",
    parameters = listOf("Lcom/jellywatch/app/billing/model/PremiumFeature;"),
)

object PremiumStatusIsSubscriptionActiveFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/model/PremiumStatus;",
    name = "isSubscriptionActive",
    returnType = "Z",
)

object BillingRepositoryIsPremiumFeatureAvailableFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/BillingRepository;",
    name = "isPremiumFeatureAvailable",
    returnType = "Z",
    parameters = listOf("Lcom/jellywatch/app/billing/model/PremiumFeature;"),
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

object ShopPurchaseManagerIsOwnedFingerprint : Fingerprint(
    definingClass = "Lyu8",
    name = "a",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
)

object ShopPurchaseManagerReplaceOwnedFingerprint : Fingerprint(
    definingClass = "Lyu8",
    name = "d",
    returnType = "V",
    parameters = listOf("Ljava/util/Set;"),
)

object ShopViewModelInitializeBillingOnceFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/shop/ShopViewModel;",
    name = "initializeBillingOnce",
    returnType = "V",
)
