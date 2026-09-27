package app.franticg33k.patches.byair.shared

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

/**
 * Resolves the obfuscated type of `kotlin.Result`'s success box at patch time.
 *
 * The byAir seams that return `Result<T>` (entitlement, subscription sync, banner state)
 * must hand back a real `Result.Success` instance. That class is R8-obfuscated and its
 * name changes between releases, so it can never be hardcoded:
 *
 *   byAir 2.39.0 -> Ly4a$c;   byAir 2.39.2 -> Lb6a$c;
 *
 * (and in 2.39.2 the name `y4a` is reused by an unrelated Okio class, so a stale
 * hardcoded name resolves to the wrong class or to nothing at all, producing
 * NoClassDefFoundError the moment the patched coroutine runs.)
 *
 * Instead the base `kotlin.Result` class is identified structurally: it is an abstract
 * class over `java.lang.Object` exposing a static `Companion` of its own `$a` inner
 * class, with exactly two further inner classes -- `$b` holding a `Throwable`
 * (`Result.Failure`) and `$c` holding an `Object` and taking one in its constructor
 * (`Result.Success`). Verified to match exactly one class in both 2.39.0 and 2.39.2.
 */
internal object KotlinResultBox {

    private const val OBJECT = "Ljava/lang/Object;"
    private const val THROWABLE = "Ljava/lang/Throwable;"
    private const val COMPANION = "Companion"

    private var cachedSuccessBoxType: String? = null

    /**
     * Returns the dex type of `Result.Success`, e.g. `Lb6a$c;`.
     *
     * @throws IllegalStateException if the class cannot be identified unambiguously.
     */
    fun successBoxType(context: BytecodePatchContext): String =
        cachedSuccessBoxType ?: locate(context).also { cachedSuccessBoxType = it }

    private fun locate(context: BytecodePatchContext): String {
        val classes = HashMap<String, ClassDef>()
        context.classDefForEach { classes[it.type.internalName] = it }

        val bases = classes.values.filter { it.isResultBase(classes) }.map { it.type.internalName }

        require(bases.size == 1) {
            "Expected exactly one kotlin.Result class, found ${bases.size}: $bases. " +
                "Refusing to patch -- guessing the Result.Success box would crash the app."
        }

        return "L${bases.single()}\$c;"
    }

    private fun ClassDef.isResultBase(classes: Map<String, ClassDef>): Boolean {
        if (accessFlags and AccessFlags.ABSTRACT.value == 0) return false
        if (superclass != OBJECT) return false

        val name = type.internalName
        if (staticFields.none { it.name == COMPANION && it.type == "L$name\$a;" }) return false

        return classes["$name\$b"].isFailureBox() && classes["$name\$c"].isSuccessBox()
    }

    private fun ClassDef?.isFailureBox(): Boolean = this != null &&
        instanceFields.any { it.type == THROWABLE } &&
        methods.any { it.name == "<init>" && it.parameterTypes == listOf(THROWABLE) }

    private fun ClassDef?.isSuccessBox(): Boolean = this != null &&
        instanceFields.any { it.type == OBJECT } &&
        methods.any { it.name == "<init>" && it.parameterTypes == listOf(OBJECT) }

    private val String.internalName: String
        get() = removePrefix("L").removeSuffix(";")
}
