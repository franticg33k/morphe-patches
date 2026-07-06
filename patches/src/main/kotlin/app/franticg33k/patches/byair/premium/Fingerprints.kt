package app.franticg33k.patches.byair.premium

import app.morphe.patcher.Fingerprint

object NeedShowPaywallAppLaunchDecisionFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_subscriptions/use_case/show_paywall/commands/NeedShowPaywallAppLaunchCommand;",
    name = "invoke\$lambda\$0",
    returnType = "Z",
    parameters = listOf("Z", "I", "Lru/wearemad/i_subscriptions/data/user_actions/PaywallUserActionsDTO;")
)

object NeedShowPaywallOnboardingDecisionFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_subscriptions/use_case/show_paywall/commands/NeedShowPaywallOnboardingCommand;",
    name = "invoke\$lambda\$0",
    returnType = "Z",
    parameters = listOf("Z", "Z")
)

object BuildAppProBannerResultFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/byair/domain/common/BuildAppProBannerStateUseCase\$invoke\$2;",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;")
)

object UserInfoIsSubscriberFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/domain/user/UserInfo;",
    name = "isSubscriber",
    returnType = "Z",
    parameters = listOf()
)

object UserInfoGetSignedInFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/domain/user/UserInfo;",
    name = "getSignedIn",
    returnType = "Z",
    parameters = listOf()
)
