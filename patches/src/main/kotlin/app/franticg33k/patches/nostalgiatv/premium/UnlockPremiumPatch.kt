package app.franticg33k.patches.nostalgiatv.premium

import app.franticg33k.patches.nostalgiatv.shared.Constants.COMPATIBILITY_NOSTALGIA_TV
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val unlockNostalgiaTvPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks every client-side NostalgiaTV Pro feature by pinning the single pro " +
        "state authority to true, and removes the PairIP Play Store license/paywall wrapper. " +
        "All pro gating reads one StateFlow (ProStatusRepository.isProUser) that is written " +
        "through one setter; the setter's argument is forced to true and the flow's initial " +
        "seed is flipped to true, so the flag can never be revoked. That covers the 10-channel " +
        "lineup cap, Docker companion link, on-demand library, custom themes, simulated " +
        "commercials, channel editor, multiple profiles and player controls. PairIP's license " +
        "check, response handling, paywall launch and error dialog are stubbed out so the app " +
        "never redirects to the Play Store on launch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NOSTALGIA_TV)

    execute {
        // 1. Force the pro-state setter's boolean argument to true.
        //    This is the only place pro state is ever written, so every writer (persisted-pref
        //    load on init, successful purchase, the query-purchases revocation guard, and the
        //    hidden 3-minute test-mode revert) ends with the flag pinned on.
        ProStateWriterFingerprint.method.addInstructions(0, "const/4 p0, 0x1")

        // 2. Flip the flow's initial seed in <clinit> from Boolean.FALSE to Boolean.TRUE.
        //    This step is load-bearing, not belt-and-braces: SettingsViewModel and
        //    ChannelsViewModel each snapshot the flow once via
        //    `ProStatusRepository.isProUser.value` in their constructor and expose it through
        //    `isProUser()`. They do not collect the flow, so a snapshot taken before the first
        //    setter call would otherwise capture `false` and stay false for the whole session
        //    even though the setter itself is patched.
        val classDef = ProStateWriterFingerprint.classDef
        val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" }
            ?: throw PatchException("NostalgiaTV: pro-state class has no <clinit>")
        val instructions = checkNotNull(clinit.implementation) {
            "NostalgiaTV: pro-state <clinit> has no implementation"
        }.instructions
        val seedIndex = instructions.indexOfFirst { instruction ->
            val reference = instruction.memberReference as? MethodReference
            reference?.definingClass == STATE_FLOW_KT && reference.name == "MutableStateFlow"
        }
        if (seedIndex < 0) {
            throw PatchException("NostalgiaTV: no MutableStateFlow seed in pro-state <clinit>")
        }
        // Walk back from the MutableStateFlow call to the Boolean constant feeding it and repoint
        // that field reference at Boolean.TRUE. Reusing the original register keeps this correct
        // even if register allocation shifts between releases.
        val constantIndex = (seedIndex downTo 0).firstOrNull { index ->
            val reference = instructions[index].memberReference
            reference is FieldReference && reference.definingClass == BOOLEAN
        } ?: throw PatchException("NostalgiaTV: no Boolean seed constant in pro-state <clinit>")
        val seedRegister = (instructions[constantIndex] as BuilderInstruction21c).registerA
        clinit.replaceInstruction(
            constantIndex,
            "sget-object v$seedRegister, $BOOLEAN->TRUE:Ljava/lang/Boolean;",
        )

        // 3. Neutralize the PairIP licensing layer. Stubbing checkLicense means the local
        //    installer check, the ILicensingService bind and the repeated-check scheduling never
        //    start; the remaining stubs are defence in depth so no response path can reach the
        //    Play Store paywall or the shutdown dialog even if the SDK is reached another way.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")
        PairipProcessResponseFingerprint.method.addInstructions(0, "return-void")
        PairipStartPaywallActivityFingerprint.method.addInstructions(0, "return-void")
        PairipStartErrorDialogActivityFingerprint.method.addInstructions(0, "return-void")
        PairipValidateResponseFingerprint.method.addInstructions(0, "return-void")
    }
}
