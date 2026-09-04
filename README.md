# franticg33k's Morphe Patches

A collection of [Morphe](https://morphe.software) patches for apps I use.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.3.9](https://github.com/franticg33k/morphe-patches/releases/tag/v1.3.9)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;22 patches total
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
<summary>📦 Fricam&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.3.7 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml so the app runs without a valid Play Store license (required because the APK is re-signed during patching). |  |
| [Unlock Premium](#unlock-premium) | Unlocks all Fricam Pro features for free. Fricam's Pro state terminates in a plain SharedPreferences boolean (pro_unlocked in fricam_billing) written only by PurchaseManager.d(Z); every UI feature gate re-reads it via the static MasterProGate.K(). The patch forces the RevenueCat entitlement check and the master gate to always return true (layered P1+P2), hardens the persist path so no refresh can downgrade, and neutralizes the PairIP Play Store license check that gates the app on launch. |  |

</details>

<details open>
<summary>📦 JellyWatch&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 2.0.REV-1712 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml. |  |
| [Unlock Premium](#unlock-premium) | Unlocks all premium features and shop items in JellyWatch. |  |

</details>

<details open>
<summary>📦 JellyWatch TV&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.0.REV-0207 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove License Activity](#remove-license-activity) | Removes the PairIP LicenseActivity from AndroidManifest.xml. |  |
| [Unlock Premium](#unlock-premium) | Unlocks all premium features in JellyWatch TV. Premium entitlement is server-verified against verify.jellywatch.app and surfaced to the UI as a PremiumStatus data class (isPremium = first boolean field); the patch forces that field to true and neutralizes the PairIP Play Store license check that gates the app on launch. |  |

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
<summary>📦 Karobar&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Premium](#unlock-premium) | Patches Dart AOT-compiled libapp.so to unlock all premium features. Forces hasPremiumAccess and isPaidUser to always return true by NOPing their ARM64 conditional branch instructions. |  |

</details>

<details open>
<summary>📦 Native Camera&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.4 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Premium](#unlock-premium) | Forces the premium entitlement in Native Camera. Premium status is a local 'is_premium' SharedPreferences flag (in the 'rawcam_prefs' file) read on cold start into a StateFlow. Unlocks RAW DNG, 10-bit/HLG/UHDR video, custom boosted modes and the sub-40-Mbps bitrate cap. It also neutralizes PairIP's Play Store licensing (fired from both Application.attachBaseContext and LicenseContentProvider.onCreate, so a repacked copy no longer gets bounced to the Play Store paywall at launch). |  |

</details>

<details open>
<summary>📦 Bolpatra Nepal&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Premium, Skip Login & Make Free Trial Permanent](#unlock-premium-skip-login-make-free-trial-permanent) | Patches the Dart AOT-compiled libapp.so of Bolpatra Nepal to skip the login screen, unlock premium/paid features, and make the 7-day free trial permanent. LoginManager::isNotValidatedUser is forced to always return false, lifting the Free-plan paywall and the trial-expiry gate: the trial expiry is computed client-side from the server plan_end_date into statics (isPaid = (plan_end_date - now).inDays <= 0) that only isNotValidatedUser reads, so with it pinned to false the free trial never expires and premium features stay unlocked forever (PPMO/EGP contact redirect, hidden works tab, document download and gallery gates). The isActive() result is left honest so the app stops attaching the invalid Authorization header, and the four login redirects (notice View, document download, gallery, subscription purchase) are bypassed at the branch level so actions run straight through without sending the user to the login page. Verified on v1.1.33 (Dart 3.9.2, arm64, non-obfuscated). |  |

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
