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
        // The full 1.6.5 body (smali index; the patcher's instruction index is 4 lower because
        // labels are not instructions, so these are at 20 and 32 there):
        //
        //   [0]  invoke-static {p1}, Lua0;->b(CustomerInfo)Z   -> v0   (pro entitlement)
        //   [1]  move-result v0
        //   [2]  invoke-virtual CustomerInfo->getEntitlements() -> p1
        //   [4]  const-string v1, "fricam_edge"
        //   [5]  EntitlementInfos.get("fricam_edge") -> p1
        //   [9]  if-eqz p1, :cond_0
        //   [10] EntitlementInfo->isActive() -> p1
        //   [12] if-ne p1, v2, :cond_0
        //   [13] move p1, v2                     ; edge active
        //   [16] move p1, v1                     ; :cond_0  edge inactive
        //   [18] if-nez v0, :cond_1
        //   [19] if-eqz p1, :cond_2
        //   [21] move v1, v2                     ; :cond_1  v1 = pro || edge
        //   [23] iget-object v0, p0, Lua0;->t
        //   [24] invoke-static {p1}, Boolean;->valueOf(Z)   <<<< FIRST  boxes EDGE -> StateFlow t
        //   [27] Lxv6;->i(null, boxed)           ; t.compareAndSet
        //   [29] Lxv6;->getValue() -> p1         ; c
        //   [32] Boolean;->booleanValue() -> v0
        //   [34] if-ne v0, v1, :cond_3           ; already persisted this value?
        //   [35] return-void
        //   [37] invoke-static {v1}, Boolean;->valueOf(Z)   <<<< SECOND boxes pro||edge
        //   [39] Lxv6;->i(null, boxed)           ; c.compareAndSet
        //   [44] putBoolean("pro_unlocked", v1)
        //   [46] apply()
        //
        // TWO boxing calls, not one. The FIRST is the Edge publish and the register it consumes is
        // p1, the edge entitlement. Forcing p1 true opens every Edge gate reading StateFlow t,
        // including the sign-in-free pairing endpoint.
        //
        // The SECOND boxes v1 = pro || edge, which drives the Pro publish and the pro_unlocked
        // persist. It needs no patch: v1 is assigned from v0 at [21] whenever the pro check is
        // true, and UnlockPremiumPatch already forces that check true. That is also why the
        // standalone pro_unlocked writer fingerprint was removed - see Fingerprints.kt.
        //
        // v0 is NOT the register to force. After the iget-object at [23] it is dead, which is why
        // the pre-1.6.5 `const/4 v0, 0x1` was a silent no-op on this build.
        //
        // Take the first boxing call and force p1. The count is not asserted to a fixed number:
        // an earlier revision asserted "exactly 1" from a partial read of this body and therefore
        // aborted the whole patch on a perfectly good build. Require at least one, and require
        // the method to still be the one the fingerprint found (it is string-anchored on
        // fricam_edge + pro_unlocked with signature (CustomerInfo)V).
        val boxIndices = implementation.instructions.withIndex()
            .filter { (_, instruction) ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is MethodReference &&
                    reference.definingClass == "Ljava/lang/Boolean;" &&
                    reference.name == "valueOf"
            }
            .map { (i, _) -> i }
            .toList()
        check(boxIndices.isNotEmpty()) {
            "Fricam Edge: no Boolean.valueOf boxing call in the entitlement sync method; the " +
                "body shape has changed beyond what this patch knows how to force."
        }
        val boxIndex = boxIndices.first()

        // p1 is stated literally rather than parsed back off the instruction: dexlib2's
        // ReferenceInstruction does not expose a register list (only the concrete 35c/3rc types
        // do, and 35c reports a padded fixed-width one), so reading it back is worse than stating
        // the register the [24] invoke consumes. If the body is reshaped, the fingerprint's
        // string anchors and signature are what stop this from silently targeting the wrong call.
        method.addInstructions(boxIndex, "const/4 p1, 0x1")
    }
}