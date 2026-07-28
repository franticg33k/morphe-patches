package app.franticg33k.patches.oldroll.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.oldroll.shared.Constants.COMPATIBILITY_OLDROLL

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

private const val RETURN_CURRENT_TIME = """
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v0
    return-wide v0
"""

@Suppress("unused")
val enableOldrollProPatch = bytecodePatch(
    name = "Enable Pro",
    description = "Unlocks all pro/premium features by bypassing purchase validation and SharedPreferences checks.",
    default = true
) {
    compatibleWith(COMPATIBILITY_OLDROLL)

    execute {
        PurchaseSharedPrefManagerIsSkuPurchasedFingerprint.method.addInstructions(0, TRUE_RETURN)
        PurchaseSharedPrefManagerGetProMonthPurchaseTimeFingerprint.method.addInstructions(0, RETURN_CURRENT_TIME)
        PurchaseSharedPrefManagerGetProYearPurchaseTimeFingerprint.method.addInstructions(0, RETURN_CURRENT_TIME)
    }
}
