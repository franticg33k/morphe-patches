package app.franticg33k.patches.nativecamera.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.nativecamera.shared.Constants.COMPATIBILITY_NATIVECAMERA
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n

@Suppress("unused")
val unlockNativeCameraPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Forces the premium entitlement in Native Camera. Premium status is a local " +
        "'is_premium' SharedPreferences flag read on startup behind PairIP's Play Store licensing, " +
        "so this unlocks RAW DNG, 10-bit video, HLG/UHDR and the boosted-ISO seekbar.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NATIVECAMERA)

    execute {
        // The constructor reads getBoolean("is_premium", false) into the premium StateFlow.
        // Flip the default literal so premium is always true on a cold start.
        val initMatch = CameraViewModelPremiumInitFingerprint.match()
        val premiumFlagMatch = initMatch.stringMatchesOrNull!!.first { it.string == "is_premium" }
        val defaultConstIndex = premiumFlagMatch.index + 1
        val defaultConst = initMatch.method.getInstruction(defaultConstIndex) as BuilderInstruction11n
        check(defaultConst.narrowLiteral == 0) {
            "Native Camera: expected const/4 literal 0 at default-const index $defaultConstIndex, " +
                "got ${defaultConst.narrowLiteral}"
        }
        initMatch.method.replaceInstruction(defaultConstIndex, "const/4 v${defaultConst.registerA}, 0x1")

        // setPremium(Z)V re-syncs the flag from billing; force the argument so a later
        // non-premium result can never downgrade the local entitlement.
        CameraViewModelSetPremiumFingerprint.method.addInstructions(0, "const/4 p1, 0x1")

        // Neutralize the PairIP Play Store license check that gates the app on launch.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}
