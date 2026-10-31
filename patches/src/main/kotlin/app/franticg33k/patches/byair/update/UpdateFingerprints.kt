package app.franticg33k.patches.byair.update

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * `getCurrentAppVersionCode()I`, found by what it does rather than what it is called: an
 * `int`-returning method that calls `PackageInfo.getLongVersionCode`. 2.41.0 had it as a
 * zero-argument instance method; 2.42.0 (R8 minified `ru.wearemad.i_app_update`) made it a
 * static taking its own class. Either shape is accepted, and the app has a single such reader
 * outside the vendored SDKs (which take a `Context`/`String` instead).
 */
private fun Method.isVersionCodeReader(): Boolean {
    if (returnType != "I") return false
    val static = accessFlags and AccessFlags.STATIC.value != 0
    val shapeOk = if (static) parameterTypes == listOf(definingClass) else parameterTypes.isEmpty()
    return shapeOk && implementation?.instructions?.any {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        ref?.name == "getLongVersionCode" && ref.definingClass == "Landroid/content/pm/PackageInfo;"
    } == true
}

private fun ClassDef.hasVersionCodeReader() = methods.any { it.isVersionCodeReader() }

/**
 * The single suspend entry point of byAir's forced-update gate (`CheckNeedForceUpdateUseCase
 * .invoke`), a `Result<Boolean>`: `true` means "the server's minimum version code is newer
 * than ours", which drives the non-dismissible `AppForceUpdateScreen`.
 *
 * 2.41.0 named it (`ru/wearemad/i_app_update/use_case/CheckNeedForceUpdateUseCase`). 2.42.0
 * minified that module (`t43.W(Ld84;)Ljava/lang/Object;`), so it is now located as the only
 * non-static, one-argument, `Object`-returning method on the class that owns the version-code
 * reader. That class's other methods are the two-argument `x`/`y` delegates and the ctor.
 */
object CheckNeedForceUpdateUseCaseInvokeFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, classDef ->
        method.accessFlags and AccessFlags.STATIC.value == 0 &&
            method.parameterTypes.size == 1 &&
            classDef.hasVersionCodeReader()
    },
)

/**
 * The app's own "what version code am I?" reader -- see [isVersionCodeReader]. Used as a
 * belt-and-braces second layer; the patch tolerates it being absent.
 */
object CurrentAppVersionCodeFingerprint : Fingerprint(
    returnType = "I",
    custom = { method, _ -> method.isVersionCodeReader() },
)
