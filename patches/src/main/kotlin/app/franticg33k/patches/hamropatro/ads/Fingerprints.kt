package app.franticg33k.patches.hamropatro.ads

import app.morphe.patcher.Fingerprint

private const val PLACEMENTS_CLASS = "Lcom/hamropatro/library/nativeads/HamroAdsPlacements;"

/*
 * The single native chokepoint that resolves every ad placement. All the public
 * getBannerAds / getNativeAd overloads funnel into these leaf builders, so returning an empty
 * list starves banner, native, interstitial, fullscreen and roadblock placements at the source.
 *
 * Why this layer rather than the SDKs underneath: Hamro bundles MAX, Facebook, Unity,
 * ironSource, Vungle, Pangle, InMobi, Chartboost and an `ads_mobile_sdk` Google Ads build behind
 * a mediation waterfall. No-oping a per-SDK `show()` leaves the mediator's state machine waiting
 * on a show callback that never arrives, and no-oping Pangle's `show()` is impossible anyway -
 * those methods are `public abstract` and have no body. Killing the placement resolver instead
 * means no request is ever built, so no mediation cycle starts and no callback is missed.
 *
 * Obfuscated helper types rotate every build - these three went Lyq7/Lzq7/Lar7 (10.7.30) to
 * Lp05/Lq05/Lr05 (10.7.33). Nothing else moved: the class, method names, return types and arity
 * are unchanged, and each signature below is defined exactly once across all 15 dex files.
 * Expect to re-pin them on the next app update; that is the cost of anchoring on the resolver,
 * and it is why every target is byte-checked rather than assumed.
 */

private const val BANNER_ARG = "Lp05;"
private const val FULLSCREEN_ARG = "Lq05;"
private const val NATIVE_ARG = "Lr05;"

object GetBannerAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getBannerAds",
    returnType = "Ljava/util/List;",
    parameters = listOf(BANNER_ARG, "Ljava/lang/String;", "Z"),
)

object GetFullScreenAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getFullScreenAds",
    returnType = "Ljava/util/List;",
    parameters = listOf(FULLSCREEN_ARG, "Z"),
)

object GetRoadblockAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getRoadblockAds",
    returnType = "Ljava/util/List;",
    parameters = listOf(FULLSCREEN_ARG, "Z"),
)

object GetNativeAdFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getNativeAd",
    returnType = "Ljava/util/List;",
    parameters = listOf(NATIVE_ARG, "Ljava/lang/String;"),
)

object GetNativeAdByPlacementFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getNativeAd",
    returnType = "Ljava/util/List;",
    parameters = listOf(
        "Lcom/hamropatro/library/nativeads/model/AdPlacementName;",
        "Ljava/lang/String;",
    ),
)
