# franticg33k's Morphe Patches

A collection of [Morphe](https://morphe.software) patches for apps I use.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.3.0-dev.1](https://github.com/franticg33k/morphe-patches/releases/tag/v1.3.0-dev.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;16 patches total
<details open>
<summary>📦 byAir&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Enable Online Pro](#enable-online-pro) | Experimental companion patch that keeps byAir's online Pro gates open without forcing the crash-prone global entitlement refresh path. |  |
| [Enable Pro](#enable-pro) | Suppresses the main byAir paywall, unlock banners, and local user gating. |  |

</details>

<details open>
<summary>📦 Hamropatro&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Ads](#remove-ads) | Disables all ad serving in Hamro Patro by no-oping the native ad-placement resolver (HamroAdsPlacements). Every banner, native, interstitial, fullscreen and roadblock placement funnels through these leaf builders, so returning an empty list means no ad request is ever built for AdMob, Pangle, IronSource or Facebook Audience Network. Verified on v10.7.30. |  |

</details>

<details open>
<summary>📦 Nepalipatro&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Ads](#remove-ads) | Disables all ad serving from Facebook Audience Network (FAN) and Google Mobile Ads (AdMob). No-ops the plugin bridges so no ads are shown. |  |

</details>

<details open>
<summary>📦 Provide Lite&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Ads](#remove-ads) | Blocks all ad display in Provide Lite by forcing the app's central ad gate (in libapp.so) to always take its 'skip' path. Two branch instructions are rewritten to unconditional skips: the global 'ad enabled' flag check (tbz -> b #skip) and the interstitial/rewarded cooldown gate (b.ge -> b #skip). Verified on v1.4.6 (Dart 3.7.0, arm64, obfuscated) — no ad-show invocation is reached. |  |
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity and LicenseContentProvider from AndroidManifest.xml and the com.android.vending.CHECK_LICENSE permission, so the app runs without a valid Play Store license (required because the APK is re-signed during patching). |  |
| [Unlock Premium](#unlock-premium) | Patches the Dart AOT-compiled libapp.so to unlock all premium / VIP features. Forces the VIP state to always be true by rewriting the constant-pool loads (add xN, x22, #0x30 -> add xN, x22, #0x20) inside the cacheIsVipKey readers (getter + 3 callers). Verified on v1.4.6 (Dart 3.7.0, arm64, obfuscated) — flutter.cacheIsVipKey=true on device. |  |

</details>

<details open>
<summary>📦 Atlas Photo&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml. |  |
| [Remove License Check](#remove-license-check) | Bypasses the PairIP Google Play Licensing check so Atlas Photo runs without a valid Play Store license. |  |
| [Unlock Pro](#unlock-pro) | Forces Atlas Photo's Hermes (React Native) JS runtime to treat every session as a Pro subscriber by patching the setIsProMember reducer so its isPro field is always truthy. Unlocks all Pro features and removes the Pro paywall. |  |

</details>

<details open>
<summary>📦 JellyWatch&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml. |  |
| [Unlock Premium](#unlock-premium) | Unlocks all premium features and shop items in JellyWatch. |  |

</details>

<details open>
<summary>📦 Prismatica Pro&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml. |  |
| [Remove License Check](#remove-license-check) | Bypasses the PairIP Google Play Licensing check so the app runs without a valid Play Store license. |  |
| [Remove Watermark](#remove-watermark) | Patches the procedural GLSL shader in libharwin_native.so to disable the "PRISMATICA" watermark in the video player and exported videos. |  |

</details>

<details open>
<summary>📦 Mero Karobar&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Premium](#unlock-premium) | Patches Dart AOT-compiled libapp.so to unlock all premium features. Forces hasPremiumAccess and isPaidUser to always return true by NOPing their ARM64 conditional branch instructions. |  |

</details>

<!-- PATCHES_END -->

## Usage

Add this repo as a patch source in Morphe:

```
https://github.com/franticg33k/morphe-patches
```

Or click: https://morphe.software/add-source?github=franticg33k/morphe-patches

## Credits

- byAir patches based on [early.egg3707/ee-morphe-patches](https://gitlab.com/early.egg3707/ee-morphe-patches).
