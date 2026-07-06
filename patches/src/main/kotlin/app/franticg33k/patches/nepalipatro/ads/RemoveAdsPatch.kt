package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.nepalipatro.shared.Constants.COMPATIBILITY_NEPALIPATRO

private val TRUE_RETURN = """
    const/4 p1, 0x1
    return p1
"""

private val METHOD_CHANNEL_SUCCESS_TRUE = """
    const/4 v0, 0x1
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
    invoke-interface {p2, v0}, Lio/flutter/plugin/common/MethodChannel$Result;->success(Ljava/lang/Object;)V
    return-void
"""

private val METHOD_CHANNEL_SUCCESS_NULL = """
    const/4 v0, 0x0
    invoke-interface {p2, v0}, Lio/flutter/plugin/common/MethodChannel$Result;->success(Ljava/lang/Object;)V
    return-void
"""

@Suppress("unused")
val removeNepalipatroAdsPatch = bytecodePatch(
    name = "Remove Ads",
    description = "Disables all ad serving from Facebook Audience Network (FAN) and Google " +
        "Mobile Ads (AdMob). No-ops the plugin bridges so no ads are shown.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NEPALIPATRO)

    execute {
        FanInitFingerprint.method.addInstructions(0, TRUE_RETURN)
        FanInterstitialOnMethodCallFingerprint.method.addInstructions(0, METHOD_CHANNEL_SUCCESS_TRUE)
        FanRewardedOnMethodCallFingerprint.method.addInstructions(0, METHOD_CHANNEL_SUCCESS_TRUE)
        AdMobOnMethodCallFingerprint.method.addInstructions(0, METHOD_CHANNEL_SUCCESS_NULL)
    }
}
