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
 *   byAir 2.39.0 -> Ly4a$c;   byAir 2.39.2 -> Lb6a$c;   byAir 2.42.0 -> Ljcf;
 *
 * (and in 2.39.2 the name `y4a` is reused by an unrelated Okio class, so a stale
 * hardcoded name resolves to the wrong class or to nothing at all, producing
 * NoClassDefFoundError the moment the patched coroutine runs.)
 *
 * R8 has emitted `Result` in two different shapes, so both are recognised:
 *
 *  - **Merged pair** (2.41.0, 2.42.0). R8 gives `Success` and `Failure` a synthetic
 *    abstract common superclass over `java/lang/Object`, because both are `Serializable`
 *    inner classes that never share a real supertype. In 2.42.0 the base is `Lkcf;` with
 *    exactly two final subclasses: `Licf;` (`b:Ljava/lang/Throwable;`, `<init>(Throwable)`,
 *    `toString` = `"Error(error="`) and `Ljcf;` (`b:Ljava/lang/Object;`,
 *    `<init>(Object)`, `toString` = `"Success(data="`). The inner-class names `$b`/`$c`
 *    are gone -- R8 renamed each box independently.
 *  - **Companion holder** (2.39.x - 2.41.0). A class exposing a static `Companion`
 *    field of its own `$a` inner type, with `$b` holding a `Throwable` (`Result.Failure`)
 *    and `$c` holding an `Object` and taking one in its constructor (`Result.Success`).
 *
 * Both shapes are collected and the resulting success-box types must collapse to
 * exactly one; anything else refuses the patch rather than guessing, because boxing
 * the wrong class turns every patched coroutine into a `NoWhenBranchMatchedException`
 * (`Result`'s helpers switch on the two box types and throw on anything else).
 *
 * Evidence:
 *
 *  - 2.42.0 -- merged shape matches 1 of 33,054 classes: base `Lkcf;`, failure `Licf;`,
 *    success `Ljcf;`. The legacy shape matches 0 (R8 renamed the boxes independently).
 *  - 2.41.0 -- both shapes match the *same* base `Ltla;` (failure `Ltla$b;`, success
 *    `Ltla$c;`), i.e. the boxes do extend the holder, so the merged shape alone covers
 *    the older build too and the two branches cannot silently disagree.
 *
 * The legacy shape is kept even though the merged shape already covers 2.41.0, because no
 * 2.39.x tree was available here to confirm the merged shape for those builds. Keeping both
 * is safe: they can only ever *add* a candidate, and two different candidates abort the patch.
 *
 * See `docs/byair-2.42.0-premium-analysis.md`.
 */
internal object KotlinResultBox {

    private const val OBJECT = "Ljava/lang/Object;"
    private const val THROWABLE = "Ljava/lang/Throwable;"
    private const val COMPANION = "Companion"

    private var cachedSuccessBoxType: String? = null

    /**
     * Returns the dex type of `Result.Success`, e.g. `Ljcf;`.
     *
     * @throws IllegalStateException if the class cannot be identified unambiguously.
     */
    fun successBoxType(context: BytecodePatchContext): String =
        cachedSuccessBoxType ?: locate(context).also { cachedSuccessBoxType = it }

    private fun locate(context: BytecodePatchContext): String {
        // Keyed by full descriptors ("Lkcf;"), the same form `superclass` and `type` use.
        val classes = HashMap<String, ClassDef>()
        context.classDefForEach { classes[it.type] = it }

        val directSubclasses = classes.values.groupBy { it.superclass.orEmpty() }

        // Shape 2.41.0+: the two boxes share an R8-synthesised abstract superclass.
        val merged = classes.values.mapNotNull { mergedPairSuccessBox(it, directSubclasses, classes) }

        // Shape 2.39.x-2.41.0: the boxes are `$b`/`$c` inner classes of a Companion holder.
        val legacy = classes.values.mapNotNull { legacyHolderSuccessBox(it, classes) }

        val candidates = (merged + legacy).distinct()

        require(candidates.size == 1) {
            "Expected exactly one kotlin.Result.Success box, found ${candidates.size}: $candidates. " +
                "Refusing to patch -- guessing the Result.Success box would crash the app " +
                "(every Result helper switches on the box type and throws on anything else). " +
                "Recovery: decompile this APK, locate the two classes that hold a " +
                "$THROWABLE and an $OBJECT respectively, and teach KotlinResultBox their shape."
        }

        return candidates.single()
    }

    /**
     * `Lkcf;` in 2.42.0, `Ltla;` in 2.41.0: an abstract class over `java/lang/Object` whose
     * only two direct subclasses are a `Result.Failure`-shaped and a `Result.Success`-shaped box.
     */
    private fun mergedPairSuccessBox(
        base: ClassDef,
        directSubclasses: Map<String, List<ClassDef>>,
        classes: Map<String, ClassDef>,
    ): String? {
        if (base.accessFlags and AccessFlags.ABSTRACT.value == 0) return null
        if (base.superclass != OBJECT) return null

        val children = directSubclasses[base.type].orEmpty()
        if (children.size != 2) return null

        val failures = children.filter { classes[it.type].isFailureBox() }
        val successes = children.filter { classes[it.type].isSuccessBox() }
        if (failures.size != 1 || successes.size != 1) return null

        return successes.single().type
    }

    /**
     * 2.39.x-2.41.0 shape: a holder with a static `Companion` field of its own `$a` type,
     * a `$b` inner class holding a `Throwable` and a `$c` inner class holding an `Object`.
     */
    private fun legacyHolderSuccessBox(holder: ClassDef, classes: Map<String, ClassDef>): String? {
        val stem = holder.type.removePrefix("L").removeSuffix(";")
        if (holder.staticFields.none { it.name == COMPANION && it.type == "L$stem\$a;" }) return null
        if (!classes["L$stem\$b;"].isFailureBox()) return null
        if (!classes["L$stem\$c"].isSuccessBox()) return null

        return "L$stem\$c;"
    }

    private fun ClassDef?.isFailureBox(): Boolean = this != null &&
        instanceFields.any { it.type == THROWABLE } &&
        methods.any { it.name == "<init>" && it.parameterTypes == listOf(THROWABLE) }

    private fun ClassDef?.isSuccessBox(): Boolean = this != null &&
        instanceFields.any { it.type == OBJECT } &&
        methods.any { it.name == "<init>" && it.parameterTypes == listOf(OBJECT) }
}
