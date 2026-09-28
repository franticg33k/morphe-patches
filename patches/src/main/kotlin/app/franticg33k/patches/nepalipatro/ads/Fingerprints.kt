package app.franticg33k.patches.nepalipatro.ads

import app.morphe.patcher.Fingerprint

/*
 * Nepali Patro 6.11.5 carries two independent ad stacks:
 *
 *  1. Google Mobile Ads (google_mobile_ads) — the only ad network still present in the dex
 *     (Facebook Audience Network's `com.facebook.ads` classes were dropped before this build).
 *     Every load/show/dispose call reaches the plugin through
 *     GoogleMobileAdsPlugin.onMethodCall(MethodCall, Result), which dispatches on
 *     `MethodCall.method`. Guarding there neutralizes every AdMob format in one place.
 *
 *  2. The first-party ad server `flutter_adserver`
 *     (https://ads-delivery.nepalipatro.com.np/...) — this is the stack that actually renders
 *     visible ads. It loads HTML ad markup into a flutter_webview platform view, so the
 *     loaders that feed HTML are the chokepoint:
 *     WebViewProxyApi.loadData / loadDataWithBaseUrl (the only two this build patches — see
 *     RemoveAdsPatch for why loadUrl is left alone).
 *
 * Resilience rules applied here, per docs/writing-update-resilient-patches.md:
 *  - never pin an obfuscated `definingClass` — all four omit it, so an R8 rename of
 *    GoogleMobileAdsPlugin / WebViewProxyApi does not break the match;
 *  - `name` is omitted wherever a stable const-string or an unambiguous signature already
 *    identifies the method;
 *  - `loadData` is the one exception: the bare `(WebView, String, String, String)V` signature
 *    is shared with five unrelated `onReceivedLoginRequest` implementations, so the stable
 *    Pigeon-generated name is required to disambiguate (verified: with the name, exactly 1
 *    match; without it, 6);
 *  - the abstract PigeonApiWebView declares the same two signatures with no body, so the
 *    WebView loaders additionally require a concrete implementation (`custom` below).
 *
 * Every fingerprint was verified against all 138,339 smali methods of 6.11.5 and resolves to
 * exactly ONE method. Re-verify with the harness documented in
 * docs/writing-update-resilient-patches.md after any change.
 */

/**
 * The AdMob plugin's method-channel entry point. The const-strings are the case labels of its
 * `MethodCall.method` switch; requiring all five (morphe's `strings` matcher is an AND over
 * substring containment) both identifies the method and forces a concrete implementation —
 * an abstract/stub `onMethodCall` cannot carry them.
 */
object AdMobOnMethodCallFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Lio/flutter/plugin/common/MethodCall;",
        "Lio/flutter/plugin/common/MethodChannel${'$'}Result;",
    ),
    strings = listOf(
        "loadInterstitialAd",
        "loadAppOpenAd",
        "loadNativeAd",
        "MobileAds#initialize",
        "showAdWithoutView",
    ),
)

/**
 * `WebViewProxyApi.loadData(WebView, data, mimeType, encoding)`. `name` is load-bearing here —
 * without it the signature alone matches 6 methods across three unrelated classes.
 */
object WebViewLoadDataFingerprint : Fingerprint(
    name = "loadData",
    returnType = "V",
    parameters = listOf(
        "Landroid/webkit/WebView;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    custom = { method, _ -> method.implementation != null },
)

/**
 * `WebViewProxyApi.loadDataWithBaseUrl(WebView, data, mimeType, encoding, baseUrl, historyUrl)`.
 * The six-parameter signature is unique in the dex, so no `name` pin is needed.
 */
object WebViewLoadDataWithBaseUrlFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/webkit/WebView;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    custom = { method, _ -> method.implementation != null },
)
