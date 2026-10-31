package app.franticg33k.patches.marriagedroid.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference

private const val LEVELPLAY_INTERSTITIAL_AD =
    "Lcom/unity3d/mediation/interstitial/LevelPlayInterstitialAd;"

private fun List<Instruction>.references(): List<Reference> =
    mapNotNull { (it as? ReferenceInstruction)?.reference }

private fun List<Instruction>.calls(
    owner: String,
    name: String,
    parameterCount: Int,
): Boolean = references().any {
    it is MethodReference &&
        it.definingClass == owner &&
        it.name == name &&
        it.parameterTypes.size == parameterCount
}

/**
 * The app's rewarded-video seam: `IronSourceAdsHandler.a(AdCallback)`.
 *
 * R8 keeps the app's own class and method names uninformative (`Lf4/i;->a(Lm4/g;)V` in 15.6),
 * so this anchors on shape, not names: the only void method in the app that (a) takes exactly
 * one parameter, (b) stores that parameter into a field of the *same* type (the callback it
 * keeps for the ad SDK's listener), and (c) hands a runnable to
 * `Activity.runOnUiThread`. That is the rewarded-video entry point; the interstitial entry
 * points below take no callback and are matched separately.
 *
 * Deliberately *not* anchored on `Lcom/ironsource/.../IronSource;->loadRewardedVideo()`: the
 * actual call sits in an R8 synthetic runnable (`Lf4/f;->run()V`), not in this method.
 */
object LoadRewardedFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val callbackType = method.parameterTypes.singleOrNull()
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val storesCallback = callbackType != null &&
            callbackType.startsWith("L") && instructions
                .filterIsInstance<Instruction22c>()
                .mapNotNull { it.reference as? FieldReference }
                .any { it.type == callbackType }
        val postsToUiThread = instructions.references().any {
            it is MethodReference && it.name == "runOnUiThread"
        }
        storesCallback && postsToUiThread
    },
)

/**
 * The interstitial *show* leaf. Anchored on the vendor SDK's own, un-obfuscated pair:
 * `LevelPlayInterstitialAd.isAdReady()Z` followed by the 2-arg
 * `showAd(Activity, String)V` overload (not the Kotlin-generated `showAd$default` bridge).
 * That pair occurs in exactly one app method across the whole dex set in 15.6.
 */
object ShowInterstitialFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        instructions.calls(LEVELPLAY_INTERSTITIAL_AD, "isAdReady", 0) &&
            instructions.calls(LEVELPLAY_INTERSTITIAL_AD, "showAd", 2)
    },
)

/**
 * The interstitial *load* leaf: the same `isAdReady()` guard followed by
 * `LevelPlayInterstitialAd.loadAd()V`. Separate from [ShowInterstitialFingerprint] because
 * they are separate methods; matching one is not matching the other.
 */
object LoadInterstitialFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        instructions.calls(LEVELPLAY_INTERSTITIAL_AD, "isAdReady", 0) &&
            instructions.calls(LEVELPLAY_INTERSTITIAL_AD, "loadAd", 0)
    },
)
