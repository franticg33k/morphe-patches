package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR

private val SUCCESS_TRUE_RETURN = """
    const/4 v0, 0x1
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
    new-instance v1, Lj89${'$'}c;
    invoke-direct {v1, v0}, Lj89${'$'}c;-><init>(Ljava/lang/Object;)V
    return-object v1
"""

private val SUCCESS_UNIT_RETURN = """
    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
    new-instance v1, Lj89${'$'}c;
    invoke-direct {v1, v0}, Lj89${'$'}c;-><init>(Ljava/lang/Object;)V
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
        HasProEntitlementRequestFingerprint.method.addInstructions(0, SUCCESS_TRUE_RETURN)

        UpdateSubscriptionUserIdUseCaseFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
        UpdateRemoteProStatusUseCaseFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
        UpdateUserSubscriptionStatusRequestFingerprint.method.addInstructions(0, SUCCESS_UNIT_RETURN)
    }
}
