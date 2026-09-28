package app.franticg33k.patches.nostalgiatv.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference

internal const val BOOLEAN = "Ljava/lang/Boolean;"
internal const val STATE_FLOW_IMPL = "Lkotlinx/coroutines/flow/StateFlowImpl;"
internal const val STATE_FLOW_KT = "Lkotlinx/coroutines/flow/StateFlowKt;"
internal const val PAIRIP_LICENSE_CLIENT = "Lcom/pairip/licensecheck/LicenseClient;"
internal const val PAIRIP_RESPONSE_HELPER = "Lcom/pairip/licensecheck/LicenseResponseHelper;"

/**
 * This dexlib2 build keeps the member reference on the typed instruction formats rather than on
 * [Instruction] itself, so resolve it through the common supertype.
 */
internal val Instruction.memberReference: Reference?
    get() = (this as? ReferenceInstruction)?.reference

internal fun Iterable<Instruction>.references(): List<Reference> = mapNotNull { it.memberReference }

/**
 * The single write path for pro state: a `public static (Z)V` setter that boxes its boolean
 * argument and hands it to a `StateFlowImpl`.
 *
 * Deliberately anchored on *shape*, not on `ProStatusRepository` / `setProUser`. A rename of the
 * class, its package, or the method leaves this match intact, which is what keeps the patch
 * working across app updates. The predicate is also exact enough to be unambiguous: across all
 * 22,133 smali files of v0.10.2 it matches exactly one method
 * (`ProStatusRepository.setProUser(Z)V`). `StateFlowImpl` alone would be useless as an anchor --
 * 107 files touch it -- so it is the `static` / `(Z)V` / `Boolean.valueOf` conjunction that does
 * the disambiguation.
 */
object ProStateWriterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Z"),
    custom = { method, _ ->
        val references = method.implementation?.instructions?.references().orEmpty()
        val boxesBooleanArgument = references.any {
            it is MethodReference && it.definingClass == BOOLEAN && it.name == "valueOf"
        }
        val writesStateFlow = references.any {
            it is MethodReference && it.definingClass == STATE_FLOW_IMPL
        }
        boxesBooleanArgument && writesStateFlow
    },
)

/**
 * PairIP is a commercial licensing SDK that ships deobfuscated (its R8 keep rules preserve these
 * names), so class + name + signature is the stable anchor here. Each fingerprint additionally
 * requires a distinctive const-string so it cannot bind to an unrelated same-named method.
 */
object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = PAIRIP_LICENSE_CLIENT,
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Cannot check license with null context."),
)

object PairipProcessResponseFingerprint : Fingerprint(
    definingClass = PAIRIP_LICENSE_CLIENT,
    name = "processResponse",
    returnType = "V",
    parameters = listOf("I", "Landroid/os/Bundle;"),
    strings = listOf("PAYWALL_INTENT"),
)

object PairipStartPaywallActivityFingerprint : Fingerprint(
    definingClass = PAIRIP_LICENSE_CLIENT,
    name = "startPaywallActivity",
    returnType = "V",
    parameters = listOf("Landroid/app/PendingIntent;"),
    strings = listOf("paywallintent"),
)

object PairipStartErrorDialogActivityFingerprint : Fingerprint(
    definingClass = PAIRIP_LICENSE_CLIENT,
    name = "startErrorDialogActivity",
    returnType = "V",
    parameters = listOf(),
)

object PairipValidateResponseFingerprint : Fingerprint(
    definingClass = PAIRIP_RESPONSE_HELPER,
    name = "validateResponse",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    strings = listOf("RS256"),
)
