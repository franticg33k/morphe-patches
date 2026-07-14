package app.franticg33k.patches.hamropatro.ads

import app.morphe.patcher.Fingerprint

private const val PLACEMENTS_CLASS = "Lcom/hamropatro/library/nativeads/HamroAdsPlacements;"

// The single native chokepoint that resolves every ad placement. All the public
// getBannerAds / getNativeAd overloads funnel into these leaf builders, so no-oping
// them starves banner, native, interstitial, fullscreen and roadblock placements at
// the source. Obfuscated helper types (Lyq7;, Lzq7;, Lar7;) are pinned to v10.7.30.

object GetBannerAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getBannerAds",
    returnType = "Ljava/util/List;",
    parameters = listOf("Lyq7;", "Ljava/lang/String;", "Z"),
)

object GetFullScreenAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getFullScreenAds",
    returnType = "Ljava/util/List;",
    parameters = listOf("Lzq7;", "Z"),
)

object GetRoadblockAdsFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getRoadblockAds",
    returnType = "Ljava/util/List;",
    parameters = listOf("Lzq7;", "Z"),
)

object GetNativeAdFingerprint : Fingerprint(
    definingClass = PLACEMENTS_CLASS,
    name = "getNativeAd",
    returnType = "Ljava/util/List;",
    parameters = listOf("Lar7;", "Ljava/lang/String;"),
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
