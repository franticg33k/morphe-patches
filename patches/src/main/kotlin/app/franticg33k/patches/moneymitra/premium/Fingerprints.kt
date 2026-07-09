package app.franticg33k.patches.moneymitra.premium

import app.morphe.patcher.Fingerprint

/**
 * RevenueCat EntitlementInfo.isActive() — the primary gate for all premium features.
 * Targeting the method that returns the isActive boolean field.
 */
object EntitlementInfoIsActiveFingerprint : Fingerprint(
    definingClass = "Lcom/revenuecat/purchases/EntitlementInfo;",
    name = "isActive",
    returnType = "Z",
    parameters = listOf()
)

/**
 * RevenueCat SubscriptionInfo.isActive() — used to check individual subscription
 * active status. Provides defense-in-depth for subscription-level checks.
 */
object SubscriptionInfoIsActiveFingerprint : Fingerprint(
    definingClass = "Lcom/revenuecat/purchases/SubscriptionInfo;",
    name = "isActive",
    returnType = "Z",
    parameters = listOf()
)

/**
 * CustomerInfo.getActiveSubscriptions() — returns the set of active subscription
 * product IDs. Patching this ensures customerInfo.activeSubscriptions is non-empty.
 */
object ActiveSubscriptionsFingerprint : Fingerprint(
    definingClass = "Lcom/revenuecat/purchases/CustomerInfo;",
    name = "getActiveSubscriptions",
    returnType = "Ljava/util/Set;",
    parameters = listOf()
)

/**
 * CustomerInfo.getAllPurchasedProductIds() — returns the set of all purchased
 * product IDs. Patching this ensures allPurchasedProductIdentifiers is non-empty.
 */
object AllPurchasedProductIdsFingerprint : Fingerprint(
    definingClass = "Lcom/revenuecat/purchases/CustomerInfo;",
    name = "getAllPurchasedProductIds",
    returnType = "Ljava/util/Set;",
    parameters = listOf()
)

/**
 * EntitlementInfo.willRenew() — indicates whether the entitlement will auto-renew.
 * Patching this to true prevents the app from treating trial as non-renewing.
 */
object EntitlementInfoWillRenewFingerprint : Fingerprint(
    definingClass = "Lcom/revenuecat/purchases/EntitlementInfo;",
    name = "willRenew",
    returnType = "Z",
    parameters = listOf()
)
