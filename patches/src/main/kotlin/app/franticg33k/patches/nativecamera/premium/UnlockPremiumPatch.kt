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
        "'is_premium' SharedPreferences flag (in the 'rawcam_prefs' file) read on cold start " +
        "into a StateFlow. Unlocks RAW DNG, 10-bit/HLG/UHDR video, custom boosted modes and " +
        "the sub-40-Mbps bitrate cap. It also neutralizes PairIP's Play Store licensing (fired " +
        "from both Application.attachBaseContext and LicenseContentProvider.onCreate, so a " +
        "repacked copy no longer gets bounced to the Play Store paywall at launch).",
    default = true
) {
    compatibleWith(COMPATIBILITY_NATIVECAMERA)

    execute {
        // Primary unlock (hard): the constructor reads getBoolean("is_premium", false) into the
        // premium StateFlow. Flip the default literal so premium is always true on a cold start.
        val initMatch = CameraViewModelPremiumInitFingerprint.match()
        val premiumFlagMatch = initMatch.stringMatchesOrNull!!.first { it.string == "is_premium" }
        val defaultConstIndex = premiumFlagMatch.index + 1
        val defaultConst = initMatch.method.getInstruction(defaultConstIndex) as BuilderInstruction11n
        check(defaultConst.narrowLiteral == 0) {
            "Native Camera: expected const/4 literal 0 at default-const index $defaultConstIndex, " +
                "got ${defaultConst.narrowLiteral}"
        }
        initMatch.method.replaceInstruction(defaultConstIndex, "const/4 v${defaultConst.registerA}, 0x1")

        // Hardening (fail-soft): the premium setter writes the "is_premium" flag and, on
        // downgrade, resets premium-only features. Force its boolean argument to 1 so a later
        // non-premium billing result can never downgrade the local entitlement. Deliberately
        // matched by the stable 'is_premium' key + (Z)V signature (no obfuscated name), and
        // applied only if found so a reshuffle in a future build can never block the cold-start
        // unlock above.
        val setter = CameraViewModelSetPremiumFingerprint.methodOrNull
        if (setter != null) {
            setter.addInstructions(0, "const/4 p1, 0x1")
        }

        // Neutralize the PairIP Play Store license check at its source (return-void covers
        // every caller: Application.attachBaseContext AND LicenseContentProvider.onCreate,
        // which is the path that actually bounces unlicensed installs to the Play Store).
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
    }
}
