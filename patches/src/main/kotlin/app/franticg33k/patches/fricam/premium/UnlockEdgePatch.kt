package app.franticg33k.patches.fricam.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.franticg33k.patches.fricam.shared.Constants.COMPATIBILITY_FRICAM
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val unlockFricamEdgePatch = bytecodePatch(
    name = "Unlock Edge",
    description = "Unlocks the Fricam Edge feature for free. Edge is a self-hosted companion " +
        "sidecar that runs beside your Frigate NVR and streams low-latency + AI-detection frames " +
        "into the app over WebRTC. Unlike Pro there is no local persistence for Edge: on every " +
        "RevenueCat sync the app recomputes the \"fricam_edge\" entitlement and publishes it into an " +
        "in-memory StateFlow that drives the pairing/settings/diagnostics UI. The patch forces that " +
        "published flag true so the Edge UI and the self-hosted (edge-local / Frigate-direct) routes " +
        "open without a subscription. Note: Fricam's managed Cloudflare relay (edge-remote, monthly " +
        "allowance) is authenticated server-side and is not bypassed - run the open-source sidecar " +
        "yourself to get the full value.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FRICAM)

    execute {
        // The edge boolean is computed (active ? 1 : 0) then boxed and published into the StateFlow.
        // Only one Boolean.valueOf(Z) call exists in the whole method (the Pro path persists via
        // SharedPreferences instead). Forcing the boxed value to 1 opens every Edge gate that reads
        // the StateFlow, including the sign-in-free pairing endpoint. (1.4.0.1 z70.a)
        val method = EdgeEntitlementActiveFingerprint.method
        val implementation = checkNotNull(method.implementation) {
            "Fricam Edge: the entitlement sync method has no implementation"
        }
        // In 1.4.0.1 the method was a(CustomerInfo, Z)V and one Boolean.valueOf boxed the Edge flag
        // held in v0, so forcing v0 worked. In 1.6.5 it is a(CustomerInfo)V and the body is:
        //
        //   invoke-static {p1}, Lua0;->b(CustomerInfo)Z   ; -> v0   (pro entitlement)
        //   ... EntitlementInfos.get("fricam_edge").isActive() -> p1  (edge entitlement)
        //   :goto_0 / if-nez v0 / if-eqz p1 / move v1, v2   ; v1 = pro || edge
        //   iget-object v0, p0, Lua0;->t
        //   invoke-static {p1}, Boolean;->valueOf(Z)       ; ONE call, boxes p1
        //
        // The published register is therefore p1, and v0 is dead after the iget - forcing v0 there
        // is a silent no-op, which is what happened on 1.6.5 until this was caught. Force the
        // register the invoke actually takes rather than assuming v0.
        //
        // Assert exactly one boxing call instead of taking the first: the Pro path used to persist
        // via SharedPreferences rather than boxing, and if a future build adds a second
        // Boolean.valueOf then indexOfFirst would silently start targeting the wrong one.
        val boxIndices = implementation.instructions.withIndex()
            .filter { (_, instruction) ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is MethodReference &&
                    reference.definingClass == "Ljava/lang/Boolean;" &&
                    reference.name == "valueOf"
            }
            .map { (i, _) -> i }
            .toList()
        check(boxIndices.size == 1) {
            "Fricam Edge: expected exactly 1 Boolean.valueOf boxing call in the entitlement sync " +
                "method, found ${boxIndices.size} at $boxIndices. Refusing to guess which one " +
                "carries the published flag."
        }
        val boxIndex = boxIndices.single()

        // Force p1, the register the invoke actually boxes. p1 is the edge entitlement result,
        // carried out of the `EntitlementInfos.get("fricam_edge").isActive()` call. This is a
        // literal rather than something read back off the instruction, because dexlib2's
        // `ReferenceInstruction` does not expose the register list - only the concrete
        // Instruction35c / RegisterRangeInstruction types do, and 35c always reports a
        // fixed-width (padded) register list, so parsing it back is worse than stating it.
        //
        // The reason this is safe to hard-code: `EdgeEntitlementActiveFingerprint` is already
        // method-scoped and string-anchored (fricam_edge + pro_unlocked, signature
        // (CustomerInfo)V), so a version change either re-resolves to a method with this exact
        // shape or the fingerprint fails and the patch refuses to run.
        method.addInstructions(boxIndex, "const/4 p1, 0x1")
    }
}