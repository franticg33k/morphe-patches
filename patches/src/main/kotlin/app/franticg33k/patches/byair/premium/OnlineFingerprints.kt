package app.franticg33k.patches.byair.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference

private fun Iterable<Instruction>.references(): List<Reference> =
    mapNotNull { (it as? ReferenceInstruction)?.reference }

/**
 * The app's single "does this user have Pro?" answer (2.42.0: `Lcnh;->a`).
 *
 * In 2.42.0 the whole RevenueCat surface is four `static final` suspend methods on one
 * abstract class -- the replacement for 2.41.0's `SubscriberExtKt.checkIfUserSubscriber` and
 * `SubscriptionsSDKProvider`:
 *
 *   a(provider, Z, cont)  -> Result<Boolean>   isSubscriber    <-- this fingerprint
 *   b(provider, cont)     -> Result<...>       subscription kind
 *   c(provider, Z, cont)  -> Result<CustomerInfo>  raw getCustomerInfo
 *   d(provider, cont)     -> Result<...>       syncPurchases
 *
 * `a` and `c` are signature-identical, so shape alone cannot separate them, and the class has
 * no const-strings to anchor on (only the coroutine-resume literal). The disambiguator is
 * structural: `a` is the wrapper that delegates to its own sibling `c` and maps the result,
 * whereas `c` fetches `CustomerInfo` and calls no sibling.
 *
 * Predicate: static, returns Object, three parameters whose second is `Z`, and the body
 * invokes a static method of its OWN defining class with an identical parameter list and
 * return type. Across all 149,156 methods of 2.42.0 this matches exactly one method, and the
 * sibling it finds is `cnh.c`. It stays unique without naming `Ljoh;` (the provider interface)
 * or `Lf84;` (the continuation class), both of which are obfuscated and rotate.
 *
 * `cnh.c` must never be patched: it feeds the real `CustomerInfo` that `cnh.b` turns into
 * subscription kind / lifetime, so faking it would blank the subscription details screen.
 */
object SubscriberCheckFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        val parameters = method.parameterTypes
        val isStatic = method.accessFlags and AccessFlags.STATIC.value != 0
        val delegatesToOwnSibling = method.implementation?.instructions
            ?.references()
            .orEmpty()
            .filterIsInstance<MethodReference>()
            .any {
                it.definingClass == method.definingClass &&
                    it.parameterTypes == parameters &&
                    it.returnType == method.returnType
            }

        isStatic && parameters.size == 3 && parameters[1] == "Z" && delegatesToOwnSibling
    },
)

/**
 * The pro-status compare-and-save step (2.42.0: `Lm4j;->a(ZZ Lf84;)`), the 2.42.0 home of the
 * `ProStatus.*` sync markers. `a` is an *instance* method reached as `Lm4j;->a(...)` from
 * `m4j.c`, i.e. `p1` = isSubscriber and `p2` = forceRemote.
 *
 * Both literals live in this one method and the pair is unique in the dex set, so it needs no
 * class name. This replaces 2.41.0's `UpdateRemoteProStatusUseCase` seam: pinning `p1` makes
 * the Room write into `user_table` always record `isSubscriber = true`, which is what keeps a
 * later refresh from downgrading the local entitlement.
 */
object ProStatusSyncFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf(
        "ProStatus. rc check ok. forceRemote=",
        "ProStatus. saving to db. isSubscriber=",
    ),
)
