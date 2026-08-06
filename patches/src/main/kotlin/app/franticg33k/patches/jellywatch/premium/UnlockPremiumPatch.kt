package app.franticg33k.patches.jellywatch.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.jellywatch.shared.Constants.COMPATIBILITY_JELLYWATCH

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

// The purchase manager's instance fields (a: SharedPreferences, b: owned-items state) and the
// state holder's getValue()/p() methods are structurally stable, but their class names are
// obfuscated and change between versions. Build the smali at patch time from the types resolved
// by the string-anchored class fingerprint so a rename doesn't break the injection.
// The resolved descriptors already carry a trailing ';' (definingClass and field type both do),
// so normalize them here before embedding.
private fun shopMergeSmali(purchaseManager: String, stateHolder: String): String {
    val pm = purchaseManager.trimEnd(';') + ";"
    val st = stateHolder.trimEnd(';') + ";"
    return """
    iget-object v0, p0, $pm->b:$st
    invoke-virtual {v0}, $st->getValue()Ljava/lang/Object;
    move-result-object v1
    check-cast v1, Ljava/util/Set;
    new-instance v2, Ljava/util/HashSet;
    invoke-direct {v2}, Ljava/util/HashSet;-><init>()V
    invoke-interface {v2, v1}, Ljava/util/Set;->addAll(Ljava/util/Collection;)Z
    invoke-interface {v2, p1}, Ljava/util/Set;->addAll(Ljava/util/Collection;)Z
    iget-object p1, p0, $pm->a:Landroid/content/SharedPreferences;
    invoke-interface {p1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
    move-result-object p1
    const-string v1, "owned_items"
    invoke-interface {p1, v1, v2}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
    invoke-interface {p1}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
    const/4 v1, 0x0
    invoke-virtual {v0, v1, v2}, $st->p(Ljava/lang/Object;Ljava/lang/Object;)Z
    return-void
"""
}

private fun prepopulateOwnedSmali(
    shopViewModel: String,
    purchaseManager: String,
    stateHolder: String,
): String {
    val vm = shopViewModel.trimEnd(';') + ";"
    val pm = purchaseManager.trimEnd(';') + ";"
    val st = stateHolder.trimEnd(';') + ";"
    return """
    iget-object v0, p0, $vm->purchaseManager:$pm
    iget-object v1, v0, $pm->b:$st
    invoke-virtual {v1}, $st->getValue()Ljava/lang/Object;
    move-result-object v2
    check-cast v2, Ljava/util/Set;
    new-instance v3, Ljava/util/HashSet;
    invoke-direct {v3}, Ljava/util/HashSet;-><init>()V
    invoke-interface {v3, v2}, Ljava/util/Set;->addAll(Ljava/util/Collection;)Z
    const-string v2, "avatar_frame_red_neon"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_blue_neon"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_steampunk"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_cyberpunk"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_dark_energy"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_aurora_borealis"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "avatar_frame_dragon_flame"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "space"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "fluid"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "steampunk"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "cyberpunk"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "dark_energy"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "aurora_borealis"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "dragon_flame"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_multicolor"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_black"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_space"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_crystal"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_steampunk"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const-string v2, "app_icon_default"
    invoke-interface {v3, v2}, Ljava/util/Set;->add(Ljava/lang/Object;)Z
    const/4 v2, 0x0
    invoke-virtual {v1, v2, v3}, $st->p(Ljava/lang/Object;Ljava/lang/Object;)Z
    iget-object v0, v0, $pm->a:Landroid/content/SharedPreferences;
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
    move-result-object v0
    const-string v1, "owned_items"
    invoke-interface {v0, v1, v3}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
    invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
"""
}

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

        // Resolve the obfuscated purchase-manager and state-holder types at patch time from the
        // string-anchored class fingerprint, so the injected smali survives class renames.
        val purchaseManager = ShopPurchaseManagerIsOwnedFingerprint.method.definingClass
        val stateHolder = ShopPurchaseManagerIsOwnedFingerprint.originalClassDef
            .fields.first { it.name == "b" }.type
        val shopViewModel = ShopViewModelInitializeBillingOnceFingerprint.method.definingClass

        ShopPurchaseManagerIsOwnedFingerprint.method.addInstructions(0, TRUE_RETURN)
        ShopPurchaseManagerReplaceOwnedFingerprint.method.addInstructions(0, shopMergeSmali(purchaseManager, stateHolder))
        ShopViewModelInitializeBillingOnceFingerprint.method.addInstructions(0, prepopulateOwnedSmali(shopViewModel, purchaseManager, stateHolder))
    }
}
