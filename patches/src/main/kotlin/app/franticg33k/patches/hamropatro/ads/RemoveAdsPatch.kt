package app.franticg33k.patches.hamropatro.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.hamropatro.shared.Constants.COMPATIBILITY_HAMROPATRO

private val EMPTY_LIST_RETURN = """
    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
    move-result-object v0
    return-object v0
"""

@Suppress("unused")
val removeHamroPatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables all ad serving in Hamro Patro by no-oping the native ad-placement " +
        "resolver (HamroAdsPlacements). Every banner, native, interstitial, fullscreen and " +
        "roadblock placement funnels through these leaf builders, so returning an empty list " +
        "means no ad request is ever built for the bundled MAX, Pangle, ironSource, Facebook " +
        "Audience, Unity, Vungle and Google Ads SDKs, and no mediation cycle ever starts. " +
        "Re-verified on v10.7.33. Works on its own, and combines cleanly with Nai64's universal " +
        "No Ads patch if you have it enabled - the two act on different layers, so together they " +
        "also cover the Google ads_mobile_sdk layer that no SDK-level patch reaches.",
    default = true
) {
    compatibleWith(COMPATIBILITY_HAMROPATRO)

    execute {
        GetBannerAdsFingerprint.method.addInstructions(0, EMPTY_LIST_RETURN)
        GetFullScreenAdsFingerprint.method.addInstructions(0, EMPTY_LIST_RETURN)
        GetRoadblockAdsFingerprint.method.addInstructions(0, EMPTY_LIST_RETURN)
        GetNativeAdFingerprint.method.addInstructions(0, EMPTY_LIST_RETURN)
        GetNativeAdByPlacementFingerprint.method.addInstructions(0, EMPTY_LIST_RETURN)
    }
}
