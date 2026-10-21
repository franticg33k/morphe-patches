package app.franticg33k.patches.marriage.economy

import app.franticg33k.patches.marriage.bundle.HERMES_BUNDLE_ENTRY
import app.franticg33k.patches.marriage.bundle.Site
import app.franticg33k.patches.marriage.bundle.editHermesBundle
import app.franticg33k.patches.marriage.shared.Constants.COMPATIBILITY_MARRIAGE
import app.morphe.patcher.patch.rawResourcePatch

/*
 * "Watch ad" that pays even when no ad was ever shown.
 *
 * ---------------------------------------------------------------------------------------------
 * What the app does
 * ---------------------------------------------------------------------------------------------
 * Rewarded ads run through one listener, Function #17581, attached to the AdMob rewarded ad in
 * `triggerRewardedAdFlow` (Function #17582). The listener switches on the event type, and the
 * reward is granted in exactly one branch:
 *
 *     RewardedAdEventType.EARNED_REWARD ->  rewardUser(payload)        ; closure slot 12
 *     AdEventType.ERROR                 ->  if (isShowing.current) return
 *                                           modal.hide()
 *                                           topMessage.show({ title: 'Ad Error', ... })
 *
 * `rewardUser` (Function #17585) is an async POST and **never looks at the ad**:
 *
 *     encodeCipher(Constants.Ciphers.AD_KEY)
 *       -> POST a/redeemAdReward { key, value, currency }
 *       -> show the returned coins/diamonds
 *       -> app.sync()
 *
 * So the ad impression is only a UI gate. When AdMob has no fill - the common case on a device
 * with no ad inventory - the ERROR branch fires, the user gets "Failed to load or display ad",
 * and nothing is redeemed.
 *
 * ---------------------------------------------------------------------------------------------
 * The edit
 * ---------------------------------------------------------------------------------------------
 * The ERROR branch hides the loading modal at 0x18e, then builds the error message. Replacing
 * that load with a jump into the EARNED_REWARD body makes a failed load take the reward path:
 *
 *     0x5036e8  51 02 02 03     Call1 r2, r2, r3                ; modal.hide()
 *     0x5036ec  2e 03 01 03     LoadFromEnvironment r3, r1, 3   ; -> error message
 *     0x5036f0  36 02 03 11 ..  GetByIdShort r2, r3, 'show'
 *
 *     0x5036ec  8e d6 00 00     Jmp -0x2a (-> 0x164) ; Unreachable ; Unreachable
 *
 * 0x164 is the EARNED_REWARD body itself (`GetEnvironment r2,1 ; LoadFromEnvironment r2,r2,12 ;
 * Call2 r1,r2,r0,r1`), a valid branch target because it is already a switch case label. The
 * argument it receives is the environment object instead of the ad payload, which is harmless:
 * `rewardUser`'s generator contains no `LoadParam`, so the parameter is never read - the currency
 * comes from the closure, not the call.
 *
 * The two spare bytes are `Unreachable` (0x00), so the branch is 4 bytes in, 4 bytes out and
 * every later instruction in the listener keeps its address.
 *
 * Only the load-failure path is touched. An ad that does load and is dismissed early still pays
 * nothing, because that path never enters this branch.
 *
 * ---------------------------------------------------------------------------------------------
 * What this does and does not prove
 * ---------------------------------------------------------------------------------------------
 * Verified on a device (2.8.45): pressing a "Watch ad" button with no ad available pays out for
 * both coins and gems. That is also the evidence that `a/redeemAdReward` does not require an
 * impression token or a per-account proof - the server accepts the request on its own. If a
 * future build adds server-side verification, this patch will still redeem but the server will
 * answer with its own error, and the app will show "No reward received from server".
 */

private val AD_ERROR_REWARD = Site(
    name = "rewarded-ad ERROR branch calls rewardUser",
    // 0x5036e8..0x5036f3: Call1 r2,r2,r3 ; LoadFromEnvironment r3,r1,3 ; GetByIdShort r2,r3,...
    signature = "510202032e03010336020311",
    editOffset = 4,
    expected = "2e030103", // LoadFromEnvironment r3, r1, 3
    replacement = "8ed60000", // Jmp -0x2a (-> 0x164) ; Unreachable ; Unreachable
)

@Suppress("unused")
val rewardOnAdFailurePatch = rawResourcePatch(
    name = "Reward when the ad fails to load",
    description = "The reward for a rewarded ad is granted by one branch of the ad listener, " +
        "and the redemption call it makes (a/redeemAdReward) never checks that an ad was " +
        "actually shown. This routes the ad-load-failure branch into that same reward call, so " +
        "pressing \"Watch ad\" with no ad available pays out instead of showing \"Ad Error\".",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MARRIAGE)

    execute {
        editHermesBundle(get(HERMES_BUNDLE_ENTRY), listOf(AD_ERROR_REWARD))
    }
}
