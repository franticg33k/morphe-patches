package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

private const val FALSE_RETURN = """
    const/4 v0, 0x0
    return v0
"""

// y4a is kotlin.Result; y4a$c is its Success box (<init>(Ljava/lang/Object;)V).
// The obfuscated name is y4a$c -- NOT j89$c, which does not exist in this APK.
private val SUCCESS_NULL_RETURN = """
    const/4 v0, 0x0
    new-instance v1, Ly4a${'$'}c;
    invoke-direct {v1, v0}, Ly4a${'$'}c;-><init>(Ljava/lang/Object;)V
    return-object v1
"""

private val SUCCESS_TRUE_RETURN = """
    const/4 v0, 0x1
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
    new-instance v1, Ly4a${'$'}c;
    invoke-direct {v1, v0}, Ly4a${'$'}c;-><init>(Ljava/lang/Object;)V
    return-object v1
"""

@Suppress("unused")
val enableByAirProPatch = bytecodePatch(
    name = "Enable Pro",
    description = "Suppresses the main byAir paywall, unlock banners, local user gating, and the notifications preferences \"All\" gate.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BYAIR)

    execute {
        // Login-safe: only force the RevenueCat entitlement (isSubscriber).
        // Do NOT force UserInfo.getSignedIn() — the login/session handshake
        // (RevenueCat + byAir OAuth) reads getSignedIn to decide whether to run,
        // so forcing TRUE there breaks login. (Bug: login fails with Enable Pro only.)
        UserInfoIsSubscriberFingerprint.method.addInstructions(0, TRUE_RETURN)

        // Notifications preferences "All" gate: handled by HasProEntitlementRequestImpl.invoke
        // (the bx8 entitlement seam that NotificationsPreferencesProBannerCommandHandler awaits).
        // This is what keeps "All" locked when only the default patch is enabled.
        HasProEntitlementRequestFingerprint.method.addInstructions(0, SUCCESS_TRUE_RETURN)

        BuildAppProBannerResultFingerprint.method.addInstructions(0, SUCCESS_NULL_RETURN)

        NeedShowPaywallAppLaunchDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
        NeedShowPaywallOnboardingDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
    }
}
