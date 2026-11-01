package app.franticg33k.patches.hamropatro.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/*
 * Hamro Patro 11.1.2 replaced the 10.7.x ad stack. `HamroAdsPlacements` (and the whole
 * `com.hamropatro.library.nativeads` package) is gone; placements now come from the bundled
 * `assets/adplacements.json` plus remote config (`new_ad_config_android`,
 * `new_ad_placements_android`), and every request - banner, native, interstitial, app-open,
 * rewarded, MAX or AdMob - first asks ONE policy method whether the placement may serve:
 *
 *     Lmr/b2;->a(Llr/v;)Llr/f;      // Allow (Llr/d) | Skip(reason) (Llr/e)
 *
 * It has ten verdicts, each a string: debug_ads_off, debug_subscribed, debug_format_off,
 * ads_disabled, subscribed, rewarded_ad_free, ad_free_service, no_consent, format_gated,
 * placement_disabled. Ten call sites in `mr/`, `or/` and `bz/` consult it, so it is the callee
 * to patch, not any of them.
 *
 * Every class name above is R8 output and WILL rotate. The verdict strings are the app's own
 * telemetry vocabulary and will not, so the fingerprint is those ten strings and nothing else.
 *
 * Two other methods hold the same strings - `lr/g.<clinit>` (it builds the set of verdicts) and a
 * static helper in `h10/o` (it maps a verdict to a user-facing message) - which is why the
 * fingerprint also requires an instance method. Without that it matches three times.
 */
object AdGateFingerprint : Fingerprint(
    strings = listOf(
        "debug_ads_off",
        "debug_subscribed",
        "debug_format_off",
        "ads_disabled",
        "rewarded_ad_free",
        "ad_free_service",
        "no_consent",
        "format_gated",
        "placement_disabled",
    ),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)
