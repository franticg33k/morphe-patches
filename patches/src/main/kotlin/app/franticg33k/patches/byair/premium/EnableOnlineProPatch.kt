package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR

// y4a is kotlin.Result; y4a$c is its Success box (<init>(Ljava/lang/Object;)V).
// The obfuscated name is y4a$c -- NOT j89$c, which does not exist in this APK.
private val SUCCESS_UNIT_RETURN = """
    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
    new-instance v1, Ly4a${'$'}c;
    invoke-direct {v1, v0}, Ly4a${'$'}c;-><init>(Ljava/lang/Object;)V
    return-object v1
"""

@Suppress("unused")
val enableByAirOnlineProPatch = bytecodePatch(
    name = "Enable Online Pro",
    description = "Experimental companion patch that keeps byAir's online Pro gates open without forcing the crash-prone global entitlement refresh path.",
    default = false
) {
    compatibleWith(COMPATIBILITY_BYAIR)
    dependsOn(enableByAirProPatch)

    execute {
        UpdateSubscriptionUserIdUseCaseFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
        UpdateRemoteProStatusUseCaseFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
        UpdateUserSubscriptionStatusRequestFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
    }
}
