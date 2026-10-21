package app.franticg33k.patches.marriage.economy

import app.franticg33k.patches.marriage.bundle.HERMES_BUNDLE_ENTRY
import app.franticg33k.patches.marriage.bundle.Site
import app.franticg33k.patches.marriage.bundle.editHermesBundle
import app.franticg33k.patches.marriage.shared.Constants.COMPATIBILITY_MARRIAGE
import app.morphe.patcher.patch.rawResourcePatch

/*
 * The hourly gift button, without the hour.
 *
 * ---------------------------------------------------------------------------------------------
 * What the app does (Marriage 2.8.45, HBC v96)
 * ---------------------------------------------------------------------------------------------
 * `AnimatedGiftIcon` (Function #12715) reads the anchor out of the account the server sent:
 *
 *     const account = useAccount()
 *     const timestamp = account.giftTimeStamp
 *
 * and the label inside it (Function #12727, `Label`) turns that into a countdown:
 *
 *     useCountDownTo(timestamp * 1000 + 3600000, onReady)
 *     remaining !== 0 ? format(remaining) : 'Claim'
 *
 * The 3600000 is a literal in the bundle - the one-hour window is the client's opinion, not a
 * server field. The press handler (Function #12720, `handlePress`) is the gate:
 *
 *     if (isReady) openGift(pageX, pageY) else shake()
 *
 * where `isReady` is the countdown reaching zero.
 *
 * ---------------------------------------------------------------------------------------------
 * The edit
 * ---------------------------------------------------------------------------------------------
 * `handlePress` starts by loading `isReady` into r1 and branching on it:
 *
 *     0x234347  2e 01 00 0a     LoadFromEnvironment r1, r0, 10   ; r1 = isReady
 *     0x23434b  90 36 01        JmpTrue r1, +0x36  (-> 0x51)     ; ready -> open the gift
 *     0x23434e  29 01 01        GetEnvironment r1, 1            ; not ready -> shake path
 *
 * Swapping `JmpTrue` (0x90) for `Jmp` (0x8e) drops the condition: the press always takes the
 * open path, and the shake/return path becomes dead code. The third byte of the original
 * instruction is `JmpTrue`'s register operand (0x01); it becomes 0x00 = `Unreachable`, so the
 * instruction stream keeps its length and every later offset in the function is unchanged.
 *
 * The open path still checks `isConnected` before it opens anything, so this does not turn a
 * failed connection into a different failure - it just removes the timer.
 *
 * ---------------------------------------------------------------------------------------------
 * What this does and does not prove
 * ---------------------------------------------------------------------------------------------
 * This is a client-side gate. `claimGift` is a POST that carries only `{ choice }` - the client
 * never sends a timestamp - so the server has its own copy of `giftTimeStamp` and may well
 * refuse a claim inside the hour. If it does, the gift screen opens and the claim comes back
 * with the server's own error, which is the honest limit of a client patch: the timer display is
 * ours to remove, the entitlement is not.
 */

private val GIFT_COUNTDOWN_GATE = Site(
    name = "AnimatedGiftIcon.handlePress countdown gate",
    // 0x234347..0x234358: LoadFromEnvironment r1,r0,10 ; JmpTrue r1,+0x36 ; GetEnvironment r1,1 ;
    // LoadFromEnvironment r4,r1,0 ; LoadFromEnvironment r5,r1,1
    signature = "2e01000a9036012901012e0401002e050101",
    editOffset = 4,
    expected = "903601", // JmpTrue Addr8:0x36, Reg8:1
    replacement = "8e3600", // Jmp +0x36 ; Unreachable
)

@Suppress("unused")
val skipGiftCountdownPatch = rawResourcePatch(
    name = "Skip hourly gift cooldown",
    description = "Removes the one-hour countdown gate on the lobby gift button, so the gift " +
        "screen opens on every press instead of after the timer. The window is computed on the " +
        "device (account.giftTimeStamp + 1 h) - this only removes that gate; whether the server " +
        "accepts a claim inside the hour is up to the server.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MARRIAGE)

    execute {
        editHermesBundle(get(HERMES_BUNDLE_ENTRY), listOf(GIFT_COUNTDOWN_GATE))
    }
}
