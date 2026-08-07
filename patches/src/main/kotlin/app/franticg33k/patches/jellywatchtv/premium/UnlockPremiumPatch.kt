package app.franticg33k.patches.jellywatchtv.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.jellywatchtv.shared.Constants.COMPATIBILITY_JELLYWATCH_TV
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val unlockJellyWatchTvPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks all premium features in JellyWatch TV. Premium entitlement is " +
        "server-verified against verify.jellywatch.app and surfaced to the UI as a PremiumStatus " +
        "data class (isPremium = first boolean field); the patch forces that field to true and " +
        "neutralizes the PairIP Play Store license check that gates the app on launch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JELLYWATCH_TV)

    execute {
        // Every premium gate (SettingsViewModel.isPremium, SideNavViewModel.isPremium, promo UI)
        // reads the PremiumStatus boolean field. The Kotlin data class constructor writes the
        // boolean argument into the field, so forcing it true at the tail of <init> makes every
        // instance premium regardless of the server response.
        val premiumClass = PremiumStatusIsPremiumFingerprint.classDef
        val init = premiumClass.methods.firstOrNull { method ->
            method.name == "<init>" &&
                method.parameterTypes == listOf("I", "Ljava/lang/String;", "Ljava/lang/String;", "Z")
        } ?: throw PatchException(
            "JellyWatch TV: could not find PremiumStatus constructor (I, String, String, Z)"
        )
        val implementation = checkNotNull(init.implementation) {
            "JellyWatch TV: PremiumStatus constructor has no implementation"
        }
        val classType = premiumClass.type
        val iputBooleanIndex = implementation.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.IPUT_BOOLEAN &&
                (instruction as? BuilderInstruction22c)?.reference is FieldReference &&
                (instruction.reference as FieldReference).name == "a" &&
                (instruction.reference as FieldReference).definingClass == classType
        }
        check(iputBooleanIndex >= 0) {
            "JellyWatch TV: could not find iput-boolean isPremium field write in PremiumStatus constructor"
        }
        init.addInstructions(
            iputBooleanIndex + 1,
            "const/4 p4, 0x1\n" +
                "iput-boolean p4, p0, $classType->a:Z",
        )

        // Neutralize the PairIP Play Store license check that would otherwise shut the app down.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
        PairipHandleErrorFingerprint.method.addInstructions(0, "return-void")
    }
}
