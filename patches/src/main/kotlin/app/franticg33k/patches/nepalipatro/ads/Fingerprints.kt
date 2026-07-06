package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.Fingerprint

object FanInitFingerprint : Fingerprint(
    definingClass = "La3/a;",
    name = "a",
    returnType = "Z",
    parameters = listOf("Ljava/util/HashMap;"),
)

object FanInterstitialOnMethodCallFingerprint : Fingerprint(
    definingClass = "La3/d;",
    name = "onMethodCall",
    returnType = "V",
    parameters = listOf(
        "Lio/flutter/plugin/common/MethodCall;",
        "Lio/flutter/plugin/common/MethodChannel$Result;",
    ),
)

object FanRewardedOnMethodCallFingerprint : Fingerprint(
    definingClass = "La3/g;",
    name = "onMethodCall",
    returnType = "V",
    parameters = listOf(
        "Lio/flutter/plugin/common/MethodCall;",
        "Lio/flutter/plugin/common/MethodChannel$Result;",
    ),
)

object AdMobOnMethodCallFingerprint : Fingerprint(
    definingClass = "Lio/flutter/plugins/googlemobileads/GoogleMobileAdsPlugin;",
    name = "onMethodCall",
    returnType = "V",
    parameters = listOf(
        "Lio/flutter/plugin/common/MethodCall;",
        "Lio/flutter/plugin/common/MethodChannel$Result;",
    ),
)
