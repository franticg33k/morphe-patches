package app.franticg33k.patches.jellywatch.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import app.franticg33k.patches.jellywatch.shared.Constants.COMPATIBILITY_JELLYWATCH

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

// Every shop item id the app sells. Ownership is "id is in the owned-items StateFlow", so
// pre-owning all of them unlocks the shop. Unchanged between 2.0.REV-1744 and 1758.
private val OWNED_IDS = listOf(
    "avatar_frame_red_neon", "avatar_frame_blue_neon", "avatar_frame_steampunk",
    "avatar_frame_cyberpunk", "avatar_frame_dark_energy", "avatar_frame_aurora_borealis",
    "avatar_frame_dragon_flame",
    "space", "fluid", "steampunk", "cyberpunk", "dark_energy", "aurora_borealis", "dragon_flame",
    "app_icon_multicolor", "app_icon_black", "app_icon_space", "app_icon_crystal",
    "app_icon_steampunk", "app_icon_default",
)

// `set` must hold a HashSet; `tmp` is any free register (clobbered).
private fun addOwnedIds(set: String, tmp: String) = OWNED_IDS.joinToString("\n") {
    "const-string $tmp, \"$it\"\n" +
        "invoke-virtual {$set, $tmp}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z"
}

// ponytail: in both injection sites only v0/v1 are free, so the merge is done with exactly those
// two (copy the incoming set into a HashSet in v0, reuse v1 for each id string). No new
// register, so no .locals bump and no parameter-register shift.
private fun mergeOwnedSmali(source: String, result: String) = """
    new-instance v0, Ljava/util/HashSet;
    invoke-direct {v0, $source}, Ljava/util/HashSet;-><init>(Ljava/util/Collection;)V
    ${addOwnedIds("v0", "v1")}
    move-object $result, v0
"""

@Suppress("unused")
val unlockJellyWatchPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks all premium features and shop items in JellyWatch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_JELLYWATCH)

    execute {
        PremiumStatusIsPremiumFingerprint.method.addInstructions(0, TRUE_RETURN)
        PremiumStatusHasFeatureFingerprint.method.addInstructions(0, TRUE_RETURN)
        PremiumStatusIsSubscriptionActiveFingerprint.method.addInstructions(0, TRUE_RETURN)
        BillingRepositoryIsPremiumFeatureAvailableFingerprint.method.addInstructions(0, TRUE_RETURN)

        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
        PairipHandleErrorFingerprint.method.addInstructions(0, "return-void")

        // Shop: (1) every later replaceOwned(Set) - a server sync or restore - keeps the full set.
        ShopPurchaseManagerReplaceOwnedFingerprint.method.addInstructions(
            0,
            mergeOwnedSmali(source = "p1", result = "p1"),
        )

        // (2) The set's first value, built in <init>(Context): it reads SharedPreferences
        // "owned_items" (v1 = that set, or an empty default) and hands v1 to the StateFlow
        // factory, the first invoke-static after getStringSet. Merge the ids in right before it.
        // Known gap, read off the patched smali: addInstructions leaves the `:goto_0` label on the
        // original invoke-static, so the injected block sits AFTER it and the `goto :goto_0` taken
        // when getStringSet returns null skips the merge. That branch is dead in practice - the
        // call passes a non-null default (an empty set), so SharedPreferences never returns null -
        // and if it ever ran the failure is "no pre-owned items", not a crash. (b) above still
        // merges on every later replaceOwned.
        PurchaseManagerClassFingerprint.method.apply {
            check(name == "<init>") { "JellyWatch shop: expected the purchase manager <init>, got $name" }
            val instructions = implementation!!.instructions.toList()
            val read = instructions.indexOfFirst {
                it is ReferenceInstruction &&
                    ReferenceUtil.getReferenceString(it.reference)?.contains("->getStringSet(") == true
            }
            val build = if (read < 0) -1 else (read + 1 until instructions.size)
                .firstOrNull { instructions[it].opcode == Opcode.INVOKE_STATIC } ?: -1
            if (build < 0) {
                throw PatchException(
                    "JellyWatch shop: no getStringSet -> invoke-static in the purchase manager " +
                        "<init>. The owned-set construction was reshaped - re-read $this and " +
                        "re-aim this edit (docs/jellywatch-premium-implementation.md)."
                )
            }
            addInstructions(build, mergeOwnedSmali(source = "v1", result = "v1"))
        }
    }
}
