package app.franticg33k.patches.marriagedroid.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.marriagedroid.shared.Constants.COMPATIBILITY_MARRIAGEDROID

/**
 * The app ships two ads and both are Unity LevelPlay (IronSource mediation):
 *
 *  - an interstitial between games, loaded and shown from `GameTableScreen` and
 *    `GameTableScreenOld` — gated only by the `Settings.playWithJokerOld` flag, which is the
 *    flag the `mm_noads_new` in-app purchase sets. Nothing else reads that flag, so it is a
 *    paid no-ads switch, not a gameplay option;
 *  - a rewarded video that must complete before the *Public Zone* matchmaker will send its
 *    `eventType = 2` "find room" request. That one is **not** covered by the purchase: the
 *    Public Zone click handler calls the ad service unconditionally, so a paying customer
 *    still watches it.
 *
 * Neutralising the two interstitial leaves (load and show) removes the first. Neutralising the
 * rewarded entry point and calling its success callback immediately removes the second, and
 * the Public Zone opens as if the video had been watched.
 */

private const val RETURN_VOID = "return-void"

@Suppress("unused")
val removeMarriageDroidInterstitialAdsPatch = bytecodePatch(
    name = "Remove Interstitial Ads",
    description = "Stops the between-games Unity LevelPlay/IronSource interstitial from being " +
        "loaded or shown. The app's own `mm_noads_new` purchase sets `Settings.playWithJokerOld` " +
        "to suppress it, so this also gives that paid behaviour to builds without the purchase.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MARRIAGEDROID)

    execute {
        LoadInterstitialFingerprint.method.addInstructions(0, RETURN_VOID)
        ShowInterstitialFingerprint.method.addInstructions(0, RETURN_VOID)
    }
}

@Suppress("unused")
val skipMarriageDroidRewardedAdPatch = bytecodePatch(
    name = "Skip Public Zone Rewarded Ad",
    description = "Skips the rewarded Unity LevelPlay video that gates the Public Zone " +
        "matchmaker and reports it as watched immediately, so the room search starts at once. " +
        "The app's `mm_noads_new` purchase does not cover this ad.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MARRIAGEDROID)

    execute {
        // `m4.g` is the app's ad-callback interface; its `i()` method is the "reward granted,
        // continue" callback (`j()` is "closed/interrupted", `r()` is "no ad available"), wired
        // in `PublicOnlineGameSetupScreen` to send the room-finder request. Resolve the
        // interface from the located method rather than hardcoding the R8-renamed name.
        val method = LoadRewardedFingerprint.method
        val callback = method.parameterTypes.single()
        // The inline smali compiler does not know the target method's parameters, so `p1`
        // cannot be referenced by name: compute its real register (parameters are the highest
        // registers of an instance method) and use the /range form, which accepts any index.
        val callbackRegister = method.implementation!!.registerCount - method.parameterTypes.size
        method.addInstructions(
            0,
            """
                invoke-interface/range {v$callbackRegister .. v$callbackRegister}, $callback->i()V
                $RETURN_VOID
            """,
        )
    }
}
