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

private val SUCCESS_NULL_RETURN = """
    const/4 v0, 0x0
    new-instance v1, Lj89${'$'}c;
    invoke-direct {v1, v0}, Lj89${'$'}c;-><init>(Ljava/lang/Object;)V
    return-object v1
"""

@Suppress("unused")
val enableByAirProPatch = bytecodePatch(
    name = "Enable Pro",
    description = "Suppresses the main byAir paywall, unlock banners, and local user gating.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BYAIR)

    execute {
        UserInfoIsSubscriberFingerprint.method.addInstructions(0, TRUE_RETURN)
        UserInfoGetSignedInFingerprint.method.addInstructions(0, TRUE_RETURN)

        BuildAppProBannerResultFingerprint.method.addInstructions(0, SUCCESS_NULL_RETURN)

        NeedShowPaywallAppLaunchDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
        NeedShowPaywallOnboardingDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
    }
}
