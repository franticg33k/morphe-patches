package app.franticg33k.patches.prismatica.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.prismatica.shared.Constants.COMPATIBILITY_PRISMATICA

@Suppress("unused")
val removePrismaticaLicenseCheckPatch = bytecodePatch(
    name = "Remove License Check",
    description = "Bypasses the PairIP Google Play Licensing check so the app runs without a valid Play Store license.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PRISMATICA)

    execute {
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
        PairipHandleErrorFingerprint.method.addInstructions(0, "return-void")
    }
}