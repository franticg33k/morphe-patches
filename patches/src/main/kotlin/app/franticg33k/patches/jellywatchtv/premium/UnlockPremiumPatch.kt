package app.franticg33k.patches.jellywatchtv.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.jellywatchtv.shared.Constants.COMPATIBILITY_JELLYWATCH_TV
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
        // reads the PremiumStatus boolean field, and every instance is built by a constructor, so
        // forcing that field true right after each constructor writes it makes every instance
        // premium regardless of the server response.
        //
        // The field and the constructor shape are NOT pinned: 0570 had <init>(I,String,String,Z),
        // 0845 has <init>(Z,String,I,String) and renamed the class. The field is whichever boolean
        // the generated toString() reads right after its "PremiumStatus(isPremium=" literal.
        val premiumClass = PremiumStatusIsPremiumFingerprint.classDef
        val classType = premiumClass.type

        val toStringInstructions = PremiumStatusIsPremiumFingerprint.method
            .implementation!!.instructions.toList()
        val literal = toStringInstructions.indexOfFirst {
            it is ReferenceInstruction &&
                (it.reference as? StringReference)?.string == "PremiumStatus(isPremium="
        }
        val getter = if (literal < 0) null else toStringInstructions.drop(literal)
            .firstOrNull { it.opcode == Opcode.IGET_BOOLEAN }
        val field = (getter as? ReferenceInstruction)?.reference as? FieldReference
        if (field == null || field.definingClass != classType) {
            throw PatchException(
                "JellyWatch TV: could not read the isPremium field out of $classType.toString " +
                    "(expected an iget-boolean after the \"PremiumStatus(isPremium=\" literal). " +
                    "toString was reshaped - re-read it and re-aim this edit."
            )
        }

        var writes = 0
        for (init in premiumClass.methods.filter { it.name == "<init>" }) {
            val instructions = init.implementation?.instructions?.toList() ?: continue
            // Highest index first, so earlier insertions do not move the later ones.
            for (i in instructions.indices.reversed()) {
                val instruction = instructions[i]
                if (instruction.opcode != Opcode.IPUT_BOOLEAN) continue
                val ref = (instruction as ReferenceInstruction).reference as? FieldReference ?: continue
                if (ref.definingClass != classType || ref.name != field.name) continue
                val register = (instruction as OneRegisterInstruction).registerA
                init.addInstructions(
                    i + 1,
                    "const/16 v$register, 0x1\n" +
                        "iput-boolean v$register, p0, $classType->${field.name}:Z",
                )
                writes++
            }
        }
        check(writes > 0) {
            "JellyWatch TV: no iput-boolean of $classType->${field.name} in any constructor"
        }

        // Neutralize the PairIP Play Store license check that would otherwise shut the app down.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
        PairipHandleErrorFingerprint.method.addInstructions(0, "return-void")
    }
}
