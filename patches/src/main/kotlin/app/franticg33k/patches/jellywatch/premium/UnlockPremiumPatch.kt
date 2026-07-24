package app.franticg33k.patches.jellywatch.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.jellywatch.shared.Constants.COMPATIBILITY_JELLYWATCH

private const val TRUE_RETURN = """
    const/4 v0, 0x1
    return v0
"""

private val SHOP_MERGE_D = """
    iget-object v0, p0, Lyu8;->b:Lnha;
    invoke-virtual {v0}, Lnha;->getValue()Ljava/lang/Object;
    move-result-object v1
    check-cast v1, Ljava/util/Set;
    new-instance v2, Ljava/util/HashSet;
    invoke-direct {v2}, Ljava/util/HashSet;-><init>()V
    invoke-interface {v2, v1}, Ljava/util/Set;->addAll(Ljava/util/Collection;)Z
    invoke-interface {v2, p1}, Ljava/util/Set;->addAll(Ljava/util/Collection;)Z
    iget-object p1, p0, Lyu8;->a:Landroid/content/SharedPreferences;
    invoke-interface {p1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
    move-result-object p1
    const-string v1, "owned_items"
    invoke-interface {p1, v1, v2}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
    invoke-interface {p1}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
    const/4 v1, 0x0
    invoke-virtual {v0, v1, v2}, Lnha;->p(Ljava/lang/Object;Ljava/lang/Object;)Z
    return-void
"""

private val PREPOPULATE_OWNED = """
    iget-object v0, p0, Lcom/jellywatch/app/shop/ShopViewModel;->purchaseManager:Lyu8;
    iget-object v1, v0, Lyu8;->b:Lnha;
    invoke-virtual {v1}, Lnha;->getValue()Ljava/lang/Object;
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
    invoke-virtual {v1, v2, v3}, Lnha;->p(Ljava/lang/Object;Ljava/lang/Object;)Z
    iget-object v0, v0, Lyu8;->a:Landroid/content/SharedPreferences;
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
    move-result-object v0
    const-string v1, "owned_items"
    invoke-interface {v0, v1, v3}, Landroid/content/SharedPreferences${'$'}Editor;->putStringSet(Ljava/lang/String;Ljava/util/Set;)Landroid/content/SharedPreferences${'$'}Editor;
    invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
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
        ShopPurchaseManagerIsOwnedFingerprint.method.addInstructions(0, TRUE_RETURN)
        ShopPurchaseManagerReplaceOwnedFingerprint.method.addInstructions(0, SHOP_MERGE_D)
        ShopViewModelInitializeBillingOnceFingerprint.method.addInstructions(0, PREPOPULATE_OWNED)
    }
}
