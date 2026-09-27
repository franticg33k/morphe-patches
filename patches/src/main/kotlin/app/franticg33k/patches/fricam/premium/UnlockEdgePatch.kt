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
        val boxIndex = implementation.instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            reference is MethodReference &&
                reference.definingClass == "Ljava/lang/Boolean;" &&
                reference.name == "valueOf"
        }
        check(boxIndex >= 0) {
            "Fricam Edge: could not find the Boolean.valueOf boxing in the entitlement sync method"
        }
        method.addInstructions(boxIndex, "const/4 v0, 0x1")
    }
}