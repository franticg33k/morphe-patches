package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR
import app.franticg33k.patches.byair.shared.KotlinResultBox

/**
 * The `UserInfo` constructor's `isSubscriber` argument is the *second* parameter, so it lives
 * in `p2` (`p0` = this, `p1` = id). The constructor's own `iput-boolean p2, ... ->b:Z` then
 * stores it, and `p2` is only re-used as a scratch register afterwards.
 */
private const val FORCE_SUBSCRIBER = "const/4 p2, 0x1"

private val TRUE_VALUE = """
    const/4 v0, 0x1
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
""".trimIndent()

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

@Suppress("unused")
val enableByAirProPatch = bytecodePatch(
    name = "Enable Pro",
    description = "Unlocks byAir Pro: forces the RevenueCat entitlement to \"subscriber\" and marks the local user as a subscriber, which is what the pro banner, flight-details and flight-time gates read.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BYAIR)

    execute {
        // Login-safe: only force the RevenueCat-derived isSubscriber flag.
        // Do NOT force the constructor's `j` field (the signed-in flag) -- the login/session
        // handshake (RevenueCat + byAir OAuth) reads it to decide whether to run, so forcing
        // TRUE there breaks login. That was the 2.41.0 lesson and it still holds in 2.42.0.
        val ctor = UserInfoIsSubscriberFingerprint.method
        require(ctor.parameterTypes.size == 8 && ctor.parameterTypes[1] == "Z") {
            "UserInfo constructor is no longer (String, Z, ...): got ${ctor.parameterTypes}. " +
                "The isSubscriber argument moved -- do NOT patch this build; re-locate the " +
                "seam first (see docs/byair-2.42.0-premium-analysis.md)."
        }
        ctor.addInstructions(0, FORCE_SUBSCRIBER)

        // The entitlement seam. Everything that asks "is this user a subscriber?" goes through
        // this one method: the pro-status sync (m4j), both paywall commands (suc / wuc) and the
        // four remaining feature gates. Returning Result.success(true) is the 2.42.0 equivalent
        // of 2.41.0's HasProEntitlementRequestImpl patch.
        SubscriberCheckFingerprint.method.addInstructions(0, successReturn(TRUE_VALUE))
    }
}
