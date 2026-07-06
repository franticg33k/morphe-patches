package app.franticg33k.patches.byair.premium

import app.morphe.patcher.Fingerprint

object HasProEntitlementRequestFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/byair/domain/mediator/banners/HasProEntitlementRequestImpl;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/Continuation;")
)

object UpdateSubscriptionUserIdUseCaseFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_subscriptions/use_case/UpdateSubscriptionUserIdUseCase;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/Continuation;")
)

object UpdateRemoteProStatusUseCaseFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_subscriptions/use_case/UpdateRemoteProStatusUseCase;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/Continuation;")
)

object UpdateUserSubscriptionStatusRequestFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/byair/domain/mediator/common/UpdateUserSubscriptionStatusRequestImpl;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Z", "Lkotlin/coroutines/Continuation;")
)
