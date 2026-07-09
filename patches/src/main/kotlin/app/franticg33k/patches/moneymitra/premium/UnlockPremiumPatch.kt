package app.franticg33k.patches.moneymitra.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.moneymitra.shared.Constants.COMPATIBILITY_MONEYMITRA

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

private const val SINGLETON_SET_RETURN = """
    const-string v0, "shark_subscription"
    invoke-static {v0}, Ljava/util/Collections;->singleton(Ljava/lang/Object;)Ljava/util/Set;
    move-result-object v0
    return-object v0
"""

@Suppress("unused")
val unlockMoneyMitraPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks all premium features in MoneyMitra by forcing RevenueCat's " +
        "EntitlementInfo.isActive(), CustomerInfo.activeSubscriptions " +
        "and allPurchasedProductIdentifiers to return premium status. " +
        "This removes paywalls and enables premium course access without a subscription.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MONEYMITRA)

    execute {
        EntitlementInfoIsActiveFingerprint.method.addInstructions(0, TRUE_RETURN)
        SubscriptionInfoIsActiveFingerprint.method.addInstructions(0, TRUE_RETURN)
        ActiveSubscriptionsFingerprint.method.addInstructions(0, SINGLETON_SET_RETURN)
        AllPurchasedProductIdsFingerprint.method.addInstructions(0, SINGLETON_SET_RETURN)
    }
}
