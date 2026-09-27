package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR
import app.franticg33k.patches.byair.shared.KotlinResultBox

private const val UNIT_VALUE = "sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;"

/**
 * Returns `Result.success(Unit)`; these are suspend functions typed `Result<Unit>`, so the
 * success box is resolved from the APK at patch time -- see [KotlinResultBox].
 */
private fun BytecodePatchContext.successUnitReturn(): String {
    val box = KotlinResultBox.successBoxType(this)
    return "$UNIT_VALUE\n" +
        "new-instance v1, $box\n" +
        "invoke-direct {v1, v0}, $box-><init>(Ljava/lang/Object;)V\n" +
        "return-object v1"
}

@Suppress("unused")
val enableByAirOnlineProPatch = bytecodePatch(
    name = "Enable Online Pro",
    description = "Experimental companion patch that keeps byAir's online Pro gates open without forcing the crash-prone global entitlement refresh path.",
    default = false
) {
    compatibleWith(COMPATIBILITY_BYAIR)
    dependsOn(enableByAirProPatch)

    execute {
        val successUnit = successUnitReturn()

        UpdateSubscriptionUserIdUseCaseFingerprint.method.addInstructions(0, successUnit)
        UpdateRemoteProStatusUseCaseFingerprint.method.addInstructions(0, successUnit)
        UpdateUserSubscriptionStatusRequestFingerprint.method.addInstructions(0, successUnit)
    }
}
