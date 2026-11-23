package app.franticg33k.patches.karobar.premium

import app.morphe.patcher.patch.rawResourcePatch
import app.franticg33k.patches.karobar.shared.Constants.COMPATIBILITY_KAROBAR
import java.util.logging.Logger

/*
 * Karobar 7.9.0 (versionCode 141) premium unlock.
 *
 * Offsets re-located against this build with a fresh blutter run (Dart 3.12.2, snapshot
 * ace654289f5abc240509fc941453ebc5, arm64). The 7.8.0 table that used to live here pointed at
 * 0xdf89a0 / 0xdfa878 / 0xdcabe4 / 0xdcabec; none of those hold the expected instruction any more,
 * and 0xdfa964 is now a theme setter, so the old table had to be re-derived rather than shifted.
 *
 * WHERE THE PREMIUM GATE ACTUALLY LIVES
 * -------------------------------------
 * Everything premium funnels through two getters on the UserModel extension:
 *
 *   UserModelExtension.hasPremiumAccess(user)  ->  isPaidUser(user) || isValidTrialUser(user)
 *   UserModelExtension.isPaidUser(user)        ->  subscriptionDetail(user)?.type == paid
 *
 * The access-control objects are consumers, not gates:
 *
 *   PrinterAccess.hasPremiumAccess             ->  hasPremiumAccess || isFeatureUnlocked("thermal_printer")
 *   StaffActionAccessControl.hasPremiumAccess  ->  hasPremiumAccess (or true for non-sheet actions)
 *   ReportsAccessControl.hasPremiumAccess      ->  hasPremiumAccess || isFeatureUnlocked(feature) || !isLimitReached()
 *
 * and the screens that call them are consumers too - measured from the blutter tree: 23
 * hasPremiumAccess call sites in 19 files and 9 isPaidUser call sites in 7 files (home, quick
 * POS, transaction add/edit, party details, business cards, invoice settings/themes, inventory,
 * manage account/subscription, reports, ...). So the narrow seam is the callee pair, and this
 * patch edits the callee pair only - three instructions, each in the callee's own
 * prologue-adjacent branch:
 *
 *   hasPremiumAccess @ 0xb80b5c (+0x28)
 *     0xb80b80: bl  UserModelExtension.isPaidUser
 *     0xb80b84: tbnz w0, #4, #0xb80b90        ; isPaidUser() == false -> try the trial
 *     0xb80b88: add  x0, NULL, #0x20          ; true
 *     0xb80b8c: b    #0xb80b98                ; -> LeaveFrame / ret
 *     0xb80b90: ...  isValidTrialUser()
 *
 *   isPaidUser @ 0xb829f0 (+0x6c)
 *     0xb82a38: ...  getIt<Settings>().appSettings (late), then ->field_f->field_4b
 *     0xb82a5c: tbz  w1, #4, #0xb82a70        ; bit4 clear -> verify subscriptionDetail().type
 *     0xb82a60: add  x0, NULL, #0x20          ; true
 *     0xb82a64: LeaveFrame / ret
 *
 * The `->field_f->field_4b` chain is the settings bool that decides whether the real
 * subscription check runs at all; its exact name is not visible in the AOT output, and this
 * patch does not depend on it - the edit only stops the conditional from leaving the true-block.
 *
 * Retargeting each conditional to the adjacent `add x0, NULL, #0x20` (rather than NOPing it) is a
 * length-preserving 4-byte edit and makes the intent explicit: the getter always takes its own
 * "return true" block, and never falls into the trial / subscription-detail path. Skipping those
 * paths is safe - both are pure reads that return a bool, and the frame teardown is the normal
 * LeaveFrame both blocks share.
 *
 * Forcing hasPremiumAccess alone would not cover the eight other isPaidUser call sites -
 * SubscriptionService.check, the deeplink handler, the redeem-success page, the "more" page,
 * manage-subscription's subtitle/build/callout and the report footer buttons - which is why both
 * getters are edited.
 *
 * NOT PATCHED, DELIBERATELY
 * -------------------------
 * UserModelExtension.isFeatureUnlocked(name) reads the per-user, server-supplied
 * `meta["gamification_enabled_features"]` list. It is an *entitlement* list, not the premium
 * flag. The action gates that combine it with the premium answer are all written
 * `hasPremiumAccess || isFeatureUnlocked(name)` and short-circuit on the left, so the two edits
 * above open them: quotation (home, party details, transaction list), add_images,
 * low_stock_alert, PrinterAccess ("thermal_printer") and ReportsAccessControl (which falls back
 * to a freemium quota). Flipping isFeatureUnlocked too would also switch on rollout-gated,
 * non-premium features the server has disabled for this account - a different patch, with a
 * different blast radius.
 *
 * The one consumer that is NOT covered is the `SubscriptionGuard` widget, whose unlock decision
 * is `isFeatureUnlocked(PremiumFeatureEnum.name)` (plus a caller-supplied predicate) with no
 * hasPremiumAccess term; `SubscriptionGuard2` goes through PrinterAccess instead and is covered.
 * So a row wrapped in the plain SubscriptionGuard for a feature the server left disabled keeps
 * its lock even with this patch applied. Whether that is visible in this build is a device
 * question, and the answer is not claimed here.
 *
 * THE EXPIRED-SUBSCRIPTION SHEET IS A THIRD GATE
 * ----------------------------------------------
 * SubscriptionService.check() runs after login and raises a blocking modal from
 * checkDaysRemaining() (expired <= 0 -> "subscription expired", 1..10 -> "expiring soon"). It
 * reads the user's subscription expiry from the server model, so forcing the two getters does not
 * silence it. One retarget keeps the app on its own "still active" path:
 *
 *   SubscriptionService.check @ 0xa03454
 *     0xa035b4: days = checkDaysRemaining()
 *     0xa035b8: cmp  x0, #0
 *     0xa035bc: b.gt #0xa035e8                ; days > 0 -> (flags + optional expiring sheet)
 *     0xa035c0: ...  StoreStaticField(0x1c00, false); isAdmin ? expired-sheet(admin) : expired-sheet(staff)
 *     0xa035e8: cmp  x0, #0
 *     0xa035ec: b.le #0xa03624
 *     0xa03624: StoreStaticField(0x1c00, true); return
 *
 * Making that b.gt unconditional sends the days<=0 case to 0xa035e8 as well, where the
 * app's own `b.le` takes it to 0xa03624 - the "subscription counts as active" tail
 * (StoreStaticField(0x1c00, true), return). No sheet, no false expiry flag, one instruction.
 * It does not touch the 1..10-day "expiring soon" notice, which is genuine subscription
 * state rather than a paywall.
 *
 * WHAT THE PATCH CANNOT DO
 * ------------------------
 * This is a client-side UI unlock. Anything the backend enforces (invoice/report/bulk-export
 * endpoints, payment verification, server-side plan limits in the subscription API) still
 * applies, and the app will surface those failures as API errors. Do not read "the paywall is
 * gone" as "premium purchased".
 */

private const val LIBAPP = "lib/arm64-v8a/libapp.so"

/** The build this offset table is pinned to. Any other build fails the byte assertions below. */
private const val SNAPSHOT = "Karobar 7.9.0 (versionCode 141), Dart 3.12.2, snapshot ace654289f5abc240509fc941453ebc5"

private val logger = Logger.getLogger("morphe.karobar.premium")

/** `stp x29, x30, [sp, #-0x10]!` - the AOT prologue blutter prints as `EnterFrame`. */
private val FUNCTION_PROLOGUE = b(0xfd, 0x79, 0xbf, 0xa9)

/**
 * `add x0, NULL, #0x20` - the AOT encoding of the `true` constant. Blutter prints the null
 * register as `NULL`; in this snapshot it is x22, so the instruction is `add x0, x22, #0x20`.
 * Asserted next to both retargets so a build that moved the true-block fails here, not silently.
 */
private val LOAD_TRUE = b(0xc0, 0x82, 0x00, 0x91)

/** `cmp x0, #0` - the branch target that re-tests the remaining days. */
private val COMPARE_DAYS_TO_ZERO = b(0x1f, 0x00, 0x00, 0xf1)

// ── 1. UserModelExtension.hasPremiumAccess (0xb80b5c) ────────────────────────────────────────────
private const val HAS_PREMIUM_ACCESS = 0xb80b5cL
private const val HAS_PREMIUM_ACCESS_BRANCH = 0xb80b84L
private val HAS_PREMIUM_ACCESS_CONDITIONAL = b(0x60, 0x00, 0x20, 0x37) // tbnz w0, #4, #0xb80b90

// ── 2. UserModelExtension.isPaidUser (0xb829f0) ──────────────────────────────────────────────────
private const val IS_PAID_USER = 0xb829f0L
private const val IS_PAID_USER_BRANCH = 0xb82a5cL
private val IS_PAID_USER_CONDITIONAL = b(0xa1, 0x00, 0x20, 0x36) // tbz w1, #4, #0xb82a70

// ── 3. SubscriptionService.check (0xa03454) ──────────────────────────────────────────────────────
private const val SUBSCRIPTION_CHECK = 0xa03454L
private const val EXPIRY_GATE = 0xa035bcL
private val EXPIRY_CONDITIONAL = b(0x6c, 0x01, 0x00, 0x54) // b.gt #0xa035e8

/** `b #<same target>` - the unconditional form of each retired conditional. */
private val BRANCH_TO_TRUE = b(0x01, 0x00, 0x00, 0x14) // b #0xb80b88 / b #0xb82a60
private val BRANCH_PAST_EXPIRY = b(0x0b, 0x00, 0x00, 0x14) // b #0xa035e8

@Suppress("unused")
val enableKarobarPremiumPatch = rawResourcePatch(
    name = "Unlock Premium",
    description = "Unlocks Karobar's premium features by editing the Dart AOT snapshot " +
        "(lib/arm64-v8a/libapp.so). Forces UserModelExtension.hasPremiumAccess and " +
        "UserModelExtension.isPaidUser to return true, and keeps the post-login " +
        "\"subscription expired\" sheet from firing on an expired account. Client-side only: " +
        "features the backend enforces still hit their server-side limits.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_KAROBAR)

    execute {
        val lib = get(LIBAPP, false)
        val bytes = lib.readBytes()

        fun fail(message: String): Nothing =
            error("Karobar premium patch: $message [pinned to $SNAPSHOT; libapp.so is ${bytes.size} bytes]")

        // Anchors first: the function entries and the true-return blocks. A moved snapshot fails
        // here with a message that names the function, before any edit offset is trusted.
        requireBytes(bytes, HAS_PREMIUM_ACCESS, FUNCTION_PROLOGUE, "UserModelExtension.hasPremiumAccess entry", ::fail)
        requireBytes(bytes, IS_PAID_USER, FUNCTION_PROLOGUE, "UserModelExtension.isPaidUser entry", ::fail)
        requireBytes(bytes, SUBSCRIPTION_CHECK, FUNCTION_PROLOGUE, "SubscriptionService.check entry", ::fail)
        requireBytes(bytes, HAS_PREMIUM_ACCESS_BRANCH + 4, LOAD_TRUE, "hasPremiumAccess true-return block", ::fail)
        requireBytes(bytes, IS_PAID_USER_BRANCH + 4, LOAD_TRUE, "isPaidUser true-return block", ::fail)
        requireBytes(bytes, EXPIRY_GATE + 0x2c, COMPARE_DAYS_TO_ZERO, "SubscriptionService.check days re-test", ::fail)

        var changed = false
        changed = edit(
            bytes, "UserModelExtension.hasPremiumAccess",
            HAS_PREMIUM_ACCESS_BRANCH, HAS_PREMIUM_ACCESS_CONDITIONAL, BRANCH_TO_TRUE, ::fail,
        ) or changed
        changed = edit(
            bytes, "UserModelExtension.isPaidUser",
            IS_PAID_USER_BRANCH, IS_PAID_USER_CONDITIONAL, BRANCH_TO_TRUE, ::fail,
        ) or changed
        changed = edit(
            bytes, "SubscriptionService.check expiry gate",
            EXPIRY_GATE, EXPIRY_CONDITIONAL, BRANCH_PAST_EXPIRY, ::fail,
        ) or changed

        if (!changed) {
            logger.info("Karobar: libapp.so is already patched - nothing to write")
            return@execute
        }

        lib.writeBytes(bytes)
        logger.info("Karobar: wrote $LIBAPP - premium getters forced to true, expiry sheet suppressed")
    }
}

private fun b(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

private fun requireBytes(
    bytes: ByteArray,
    offset: Long,
    expected: ByteArray,
    what: String,
    fail: (String) -> Nothing,
) {
    val at = offset.toInt()
    if (at < 0 || at + expected.size > bytes.size) {
        fail("$what is outside libapp.so (offset 0x${offset.toString(16)})")
    }
    val actual = bytes.copyOfRange(at, at + expected.size)
    if (!actual.contentEquals(expected)) {
        fail(
            "$what did not match at 0x${offset.toString(16)}: expected ${expected.toHex()}, " +
                "found ${actual.toHex()}. The snapshot moved - re-run blutter and re-derive the " +
                "offset table; do not adjust the offset on its own.",
        )
    }
}

/**
 * Rewrites [offset] from [expected] to [replacement] (same length).
 *
 * Idempotent: a site that already holds [replacement] is reported as already patched instead of
 * being treated as a mismatch, because the manager re-applies patches to an already-patched APK.
 *
 * @return true when bytes were written.
 */
private fun edit(
    bytes: ByteArray,
    label: String,
    offset: Long,
    expected: ByteArray,
    replacement: ByteArray,
    fail: (String) -> Nothing,
): Boolean {
    if (expected.size != replacement.size) {
        fail("$label: replacement is not length-preserving (${expected.size} in, ${replacement.size} out)")
    }
    val at = offset.toInt()
    if (at < 0 || at + expected.size > bytes.size) {
        fail("$label: offset 0x${offset.toString(16)} is outside libapp.so (${bytes.size} bytes)")
    }
    val actual = bytes.copyOfRange(at, at + expected.size)
    when {
        actual.contentEquals(replacement) -> {
            logger.info("Karobar: $label already patched at 0x${offset.toString(16)}")
            return false
        }
        actual.contentEquals(expected) -> {
            replacement.forEachIndexed { i, byte -> bytes[at + i] = byte }
            logger.info(
                "Karobar: $label patched at 0x${offset.toString(16)} " +
                    "(${expected.toHex()} -> ${replacement.toHex()})",
            )
            return true
        }
        else -> fail(
            "$label: byte mismatch at 0x${offset.toString(16)}; expected ${expected.toHex()} " +
                "but found ${actual.toHex()}",
        )
    }
}
