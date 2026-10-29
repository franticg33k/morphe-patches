package app.franticg33k.patches.byair.premium

import app.morphe.patcher.Fingerprint

// --- 2.42.0 re-pin ---------------------------------------------------------------
//
// 2.41.0's anchors pinned `ru/wearemad/...` class and member NAMES on the assumption that
// byAir does not obfuscate its own classes. That assumption is dead in 2.42.0: R8 now
// minifies every `ru.wearemad.*` LIBRARY module (the `i_*` modules, `ru.wearemad.domain`,
// `ru.wearemad.i_subscriptions`), so those classes are `u0k`, `cnh`, `m4j`, `zb1`, ... Only
// the app module `ru/wearemad/byair/**` keeps readable names. All ten 2.41.0 fingerprints
// therefore resolved to 0 methods.
//
// The replacements below never name an obfuscated class. They anchor on:
//   * a Kotlin data-class `toString` literal (stable across renames), which resolves the
//     CLASS, and
//   * a structural predicate over the method body, which resolves the METHOD.
//
// See docs/byair-2.42.0-premium-analysis.md for the full trace.

/**
 * Resolves the domain `UserInfo` class (`Lu0k;` in 2.42.0) from its data-class `toString`
 * literal. `"UserInfo(id="` occurs in exactly one class in the whole dex set -- both
 * `UserInfo` and `UserDTO` print `", isSubscriber="`, so the class-specific prefix is what
 * keeps this unique (`UserDTO` prints `"UserDTO(id="`).
 *
 * Class-level: it exists only to feed [UserInfoIsSubscriberFingerprint].
 */
object UserInfoClassFingerprint : Fingerprint(
    strings = listOf("UserInfo(id="),
)

/**
 * The domain `UserInfo` constructor.
 *
 * `b` (isSubscriber) is written in exactly one place -- `iput-boolean p2, p0, Lu0k;->b:Z` in
 * this constructor -- and that write happens *before* `p2` is re-used as a scratch register
 * for the authType mapping. Forcing `p2 = 1` therefore covers every construction site at
 * once (`bzb.b`, `h69` x2, and seven feature lambdas that build modified copies) instead of
 * patching one mapper.
 *
 * Deliberately NOT `j`: the constructor derives `j` itself from the authType sealed class --
 * it is the signed-in flag. 2.41.0 recorded that forcing it breaks the RevenueCat + byAir
 * OAuth login handshake.
 */
object UserInfoIsSubscriberFingerprint : Fingerprint(
    classFingerprint = UserInfoClassFingerprint,
    name = "<init>",
    returnType = "V",
)
