package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR
import app.franticg33k.patches.byair.shared.KotlinResultBox

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

private const val FALSE_RETURN = """
    const/4 v0, 0x0
    return v0
"""

/**
 * Wraps the instructions in [value] (which must leave the success value in `v0`) in a real
 * `kotlin.Result.Success` and returns it. These seams are suspend functions typed
 * `Result<T>`, so the box class is resolved from the APK at patch time -- see
 * [KotlinResultBox] for why it cannot be hardcoded.
 */
private fun BytecodePatchContext.successReturn(value: String): String {
    val box = KotlinResultBox.successBoxType(this)
    return "$value\n" +
        "new-instance v1, $box\n" +
        "invoke-direct {v1, v0}, $box-><init>(Ljava/lang/Object;)V\n" +
        "return-object v1"
}

private const val NULL_VALUE = "const/4 v0, 0x0"

private val TRUE_VALUE = """
    const/4 v0, 0x1
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
""".trimIndent()

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
        // (the entitlement seam that NotificationsPreferencesProBannerCommandHandler awaits).
        // This is what keeps "All" locked when only the default patch is enabled.
        HasProEntitlementRequestFingerprint.method.addInstructions(0, successReturn(TRUE_VALUE))

        BuildAppProBannerResultFingerprint.method.addInstructions(0, successReturn(NULL_VALUE))

        NeedShowPaywallAppLaunchDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
        NeedShowPaywallOnboardingDecisionFingerprint.method.addInstructions(0, FALSE_RETURN)
    }
}
