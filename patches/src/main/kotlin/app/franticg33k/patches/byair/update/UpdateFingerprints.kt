package app.franticg33k.patches.byair.update

import app.morphe.patcher.Fingerprint

/**
 * The single suspend entry point of byAir's forced-update gate.
 *
 * `CheckNeedForceUpdateUseCase` is a `y59`-style use case whose `invoke` resolves to a
 * `Result<Boolean>`: `true` means "the server's minimum version code is newer than ours",
 * which drives the non-dismissible `AppForceUpdateScreen`. The `ru/wearemad/...` module
 * class names are not R8-obfuscated, so this anchor is stable across builds.
 */
object CheckNeedForceUpdateUseCaseInvokeFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_app_update/use_case/CheckNeedForceUpdateUseCase;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/Continuation;")
)

/**
 * The app's own "what version code am I?" reader (`getCurrentAppVersionCode()I`): a
 * zero-argument, `int`-returning method on the use case. The member name is pinned to the
 * build (R8 may rename a private method), so this fingerprint deliberately matches by
 * *shape* -- class + return type + no parameters -- instead of by name. In the byAir 2.41.0
 * dump it resolves to exactly one method, and that method is only ever read by the version
 * comparison inside `CheckNeedForceUpdateUseCase.invoke`.
 */
object CurrentAppVersionCodeFingerprint : Fingerprint(
    definingClass = "Lru/wearemad/i_app_update/use_case/CheckNeedForceUpdateUseCase;",
    returnType = "I",
    parameters = listOf()
)
