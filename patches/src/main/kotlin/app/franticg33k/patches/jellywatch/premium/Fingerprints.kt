package app.franticg33k.patches.jellywatch.premium

import app.morphe.patcher.Fingerprint

object PremiumStatusIsPremiumFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/model/PremiumStatus;",
    name = "isPremium",
    returnType = "Z",
)

// Resolves the shop purchase manager class from its stable SharedPreferences keys instead of
// pinning the obfuscated class name (Lyu8 -> Leu8 between versions), which the notes say to
// avoid. The two keys live in the same <init> method, so the anchor is unambiguous.
object PurchaseManagerClassFingerprint : Fingerprint(
    strings = listOf("shop_purchases", "owned_items"),
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

// R8 renamed this from `isPremiumFeatureAvailable` to `p` between 1744 and 1758. The class is
// in a stable package and (PremiumFeature)Z is the only method of that shape in it, so the
// shape - not the name - is the anchor.
object BillingRepositoryIsPremiumFeatureAvailableFingerprint : Fingerprint(
    definingClass = "Lcom/jellywatch/app/billing/BillingRepository;",
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

// 1758 merged the purchase manager into one R8 class (`xyb`) whose `isOwned(String)` is gone -
// the shop reads the owned-set StateFlow directly. What is left is `replaceOwned(Set)V` (the
// only (Set)V in the class; renamed d -> e) and the `<init>(Context)V` that PurchaseManagerClass
// resolves (it holds the two SharedPreferences keys), which is where the owned set is first built.
object ShopPurchaseManagerReplaceOwnedFingerprint : Fingerprint(
    classFingerprint = PurchaseManagerClassFingerprint,
    returnType = "V",
    parameters = listOf("Ljava/util/Set;"),
)
