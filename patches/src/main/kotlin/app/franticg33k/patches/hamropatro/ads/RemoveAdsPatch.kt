package app.franticg33k.patches.hamropatro.ads

import app.franticg33k.patches.hamropatro.shared.Constants.COMPATIBILITY_HAMROPATRO
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The gate (see Fingerprints.kt) reads the remote "ads enabled" flag and, when it is off,
 * returns Skip("ads_disabled"):
 *
 *     iget-boolean v3, v1, Llr/b;->a:Z      ; AdConfigDto.adsEnabled
 *     if-nez  v3, :cond_3                   ; enabled -> carry on to the other checks
 *     new-instance p1, Llr/e;               ; <- the app's own "ads are off" verdict
 *     const-string v0, "ads_disabled"
 *     invoke-direct {p1, v0}, Llr/e;-><init>(Ljava/lang/String;)V
 *     return-object p1
 *
 * Deleting the `if-nez` makes the fall-through - which is exactly that verdict - unconditional.
 * It is the app's own early return for "nothing to show" (AGENTS.md rule 5), so no class name is
 * written into the patch: the obfuscated `Llr/e;` is never mentioned and cannot rot.
 *
 * Fall-through is safe here (rule 4) only because the fall-through IS the target, which the
 * patch asserts before it edits: the instruction two before the "ads_disabled" const-string must
 * be the `if-nez`, and the one before it the `new-instance`.
 *
 * Side effect, by design: this gate also covers REWARDED, so the opt-in "watch an ad" flows
 * (ad-free minutes, games coins) can no longer start. Those exist only to remove ads / earn
 * coins the server must credit, so there is nothing for a client-only patch to keep.
 */
@Suppress("unused")
val removeHamroPatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables all ad serving in Hamro Patro by forcing the app's own ad policy " +
        "gate to its \"ads_disabled\" verdict. Every banner, native, interstitial, app-open and " +
        "rewarded request across the bundled MAX, AdMob, Pangle, ironSource, Facebook, Unity " +
        "and Vungle stacks asks that gate first, so none is ever built. Works on its own, and " +
        "combines cleanly with Nai64's universal No Ads patch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HAMROPATRO)

    execute {
        AdGateFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val marker = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    ((it as ReferenceInstruction).reference as StringReference).string == "ads_disabled"
            }
            if (marker < 2 ||
                instructions[marker - 1].opcode != Opcode.NEW_INSTANCE ||
                instructions[marker - 2].opcode != Opcode.IF_NEZ
            ) {
                throw PatchException(
                    "Hamro Patro ad gate: expected `if-nez; new-instance; const-string \"ads_disabled\"` " +
                        "but found ${instructions.getOrNull(marker - 2)?.opcode}/" +
                        "${instructions.getOrNull(marker - 1)?.opcode} around index $marker in $this. " +
                        "The gate was reshaped - re-read it in the new build and re-aim this edit " +
                        "(docs/hamropatro-ads-analysis.md section 0)."
                )
            }
            removeInstruction(marker - 2)
        }
    }
}
