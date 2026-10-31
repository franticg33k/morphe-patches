package app.franticg33k.patches.byair.update

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.byair.shared.Constants.COMPATIBILITY_BYAIR_UPDATE
import app.franticg33k.patches.byair.shared.KotlinResultBox

/**
 * Loads a boxed `java.lang.Boolean(false)` into `v0` (the value the seams below are typed
 * to carry), mirroring the `TRUE_VALUE` helper in the premium patches.
 */
private val FALSE_VALUE = """
    const/4 v0, 0x0
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
    move-result-object v0
""".trimIndent()

/** Leaves `Integer.MAX_VALUE` in `v0` so a version-code reader can never look "outdated". */
private val MAX_VERSION_CODE = """
    const v0, 0x7fffffff
    return v0
""".trimIndent()

/**
 * Wraps the instructions in [value] (which must leave the success value in `v0`) in a real
 * `kotlin.Result.Success` and returns it. The force-update gate is a suspend function typed
 * `Result<Boolean>`, so the box class is resolved from the APK at patch time -- see
 * [KotlinResultBox] for why it cannot be hardcoded.
 */
private fun BytecodePatchContext.successReturn(value: String): String {
    val box = KotlinResultBox.successBoxType(this)
    return "$value\n" +
        "new-instance v1, $box\n" +
        "invoke-direct {v1, v0}, $box-><init>(Ljava/lang/Object;)V\n" +
        "return-object v1"
}

/**
 * Removes byAir's non-dismissible "time to update" screen.
 *
 * On every launch byAir asks the server for its minimum supported version code
 * (`AppUpdateApi.getAppVersion` -> `/v4/min_versions`) and, if the installed build is older,
 * blocks the whole app behind `AppForceUpdateScreen`, whose only action opens Google Play.
 * That means a *patched older* build stops working the moment byAir raises its floor, forcing
 * a fresh patch for every byAir release.
 *
 * This pins the gate to "no update required":
 *
 *  - Layer 1 (primary) short-circuits `CheckNeedForceUpdateUseCase.invoke` with
 *    `Result.success(false)` -- the exact value byAir's own error fallback returns -- so the
 *    check never runs and the min-version endpoint is never called.
 *  - Layer 2 (secondary) forces the use case's version-code reader to `Integer.MAX_VALUE`, so
 *    even if the entry point above is ever reached with a fresh body, `currentVersionCode <
 *    minVersion` still evaluates false. It is applied only if present, so a refactor that
 *    renames or inlines that private method can never make the whole patch fail.
 */
@Suppress("unused")
val removeByAirUpdatePromptPatch = bytecodePatch(
    name = "Remove Update Prompt",
    description = "Removes byAir's forced-update screen (\"Pack up for Update! 🚀\" / \"Update Now\") by pinning its version gate to \"no update required\". byAir fetches a server-side minimum version code on launch and, if your build is older, blocks the whole app behind a non-dismissible screen whose only action opens Google Play. With the gate closed a patched older build keeps running and never calls the min-version endpoint, so you no longer have to re-patch every time byAir ships a release.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BYAIR_UPDATE)

    execute {
        // Layer 1: no-op the gate entry. `Result.success(false)` is exactly what
        // CheckNeedForceUpdateUseCase$invoke$4 (the app's error fallback) returns, so the
        // caller already treats it as "no forced update".
        CheckNeedForceUpdateUseCaseInvokeFingerprint.method.addInstructions(0, successReturn(FALSE_VALUE))

        // Layer 2: belt-and-braces, applied only if the reader still exists.
        CurrentAppVersionCodeFingerprint.methodOrNull?.addInstructions(0, MAX_VERSION_CODE)
    }
}
