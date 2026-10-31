package app.franticg33k.patches.byair.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR

/**
 * `m4j.a(isSubscriber, forceRemote, cont)` is reached as `Lm4j;->a(...)`, and it is an
 * *instance* method (`.method public final a(ZZ Lf84;)`), so `p1` is `isSubscriber`. It is
 * read exactly once, into `v4`, on the first pass through the coroutine state machine -- so
 * setting it on entry is enough.
 *
 * `const/16`, not `const/4`: the inline-smali compiler re-compiles this snippet inside a
 * dummy method sized to the *target's* register count. `m4j.a` declares `.locals 17` plus
 * four parameter registers, so `p1` is `v18` and `const/4` (4-bit register field, `v0`-`v15`
 * only) cannot encode it -- it fails at patch time with an empty-method parse error rather
 * than anything that points at the register.
 */
private const val FORCE_SYNC_SUBSCRIBER = "const/16 p1, 0x1"

/**
 * Companion patch that keeps byAir's *online* Pro status from ever being written as
 * "not a subscriber".
 *
 * 2.41.0 shipped this as two seams (`UpdateRemoteProStatusUseCase` and
 * `UpdateUserSubscriptionStatusRequestImpl`). Both classes are gone in 2.42.0 -- the
 * `i_subscriptions/use_case` package was folded away and the sync now happens inside `m4j`.
 * Its 2.42.0 equivalent is the compare-and-save step: pinning the incoming `isSubscriber` to
 * true means the Room write into `user_table` always records Pro, so a later refresh (or an
 * offline/failed RevenueCat round-trip) cannot downgrade the persisted entitlement.
 *
 * It is a *companion*, not a prerequisite: "Enable Pro" already makes the entitlement seam
 * answer true. This patch is the belt-and-braces layer for the persistence path, which is why
 * it stays off by default.
 */
@Suppress("unused")
val enableByAirOnlineProPatch = bytecodePatch(
    name = "Enable Online Pro",
    description = "Experimental companion patch that keeps byAir's online Pro status from being downgraded: forces the pro-status sync to always persist isSubscriber = true into the local user table, so a later RevenueCat refresh or a failed network round-trip cannot turn Pro back off.",
    default = false
) {
    compatibleWith(COMPATIBILITY_BYAIR)
    dependsOn(enableByAirProPatch)

    execute {
        val sync = ProStatusSyncFingerprint.method
        require(sync.parameterTypes.size == 3 && sync.parameterTypes[0] == "Z") {
            "Pro-status sync is no longer (Z, Z, Continuation): got ${sync.parameterTypes}. " +
                "The argument order moved -- do NOT patch this build; re-locate the seam first."
        }
        sync.addInstructions(0, FORCE_SYNC_SUBSCRIBER)
    }
}
