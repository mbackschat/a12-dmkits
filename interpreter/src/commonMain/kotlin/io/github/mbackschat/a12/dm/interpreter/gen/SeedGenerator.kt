package io.github.mbackschat.a12.dm.interpreter.gen

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.random.Random

/**
 * Generates a **valid-by-construction** document instance from a [ModelDef] into a [PlacementSink]. It walks
 * the model's group tree (derived from the field paths + the declared repeatable groups), instantiates the
 * requested number of rows per repeatable group, and emits a value per field from a [ValueStrategy] — by
 * default a valid value for the field's [Kind] (respecting NUMBER scale/sign, date/time/range formats). The
 * result is correct by construction: every field gets an in-format value unless a strategy deliberately
 * leaves it empty.
 *
 * Kernel-free and multiplatform — so it serves three consumers from one place:
 * - the **`:adapter` differential fuzz** net (random valid documents, assert interp == kernel);
 * - the **perf harness** (deterministic strategies that drive a known firing fraction);
 * - a future **`dmtool seed`** verb on the native image (model → valid sample instance → JSON).
 *
 * **Determinism:** [populate] builds its own `Random(seed)` and walks in a fixed order, so two calls with the
 * same `seed` (and the same generator instance) produce the **identical** document — which is exactly how the
 * fuzz net materializes one generated instance into BOTH engines. Use ONE [SeedGenerator] per model and vary
 * the `seed`.
 */
class SeedGenerator(private val model: ModelDef) {

    /** Group path -> its child group paths (document order, then sorted for cross-call determinism). */
    private val childGroups: Map<String, List<String>>
    /** Group path -> the fields declared directly under it, in declaration order. */
    private val fieldsByGroup: Map<String, List<FieldDef>> = model.fields.groupBy { parentGroup(it.path) }
    /** The depth-1 root groups (e.g. `/Order`). */
    private val roots: List<String>

    init {
        val allGroups = buildSet {
            model.fields.forEach { f -> addAll(prefixes(f.path).dropLast(1)) }   // a field's ancestor groups
            model.repeatable.forEach { addAll(prefixes(it)) }                    // repeatable groups + ancestors
        }
        childGroups = allGroups.filter { depth(it) > 1 }.groupBy { parentGroup(it) }
            .mapValues { (_, v) -> v.sorted() }
        roots = allGroups.filter { depth(it) == 1 }.sorted()
    }

    /**
     * Populate [sink] with a full instance. [rowsOf] decides how many rows to instantiate for each repeatable
     * group (given its parent row's indices + the RNG — so counts can vary per parent / per run); a
     * non-repeatable group always gets exactly one row. [value] decides each field's value (null = leave the
     * field empty). Both receive the shared seeded [Random], so a random strategy is reproducible from [seed].
     */
    fun populate(
        sink: PlacementSink,
        seed: Long = 0L,
        rowsOf: (group: String, parentReps: List<Int>, rnd: Random) -> Int,
        value: ValueStrategy = ValidValues,
    ) {
        val rnd = Random(seed)
        roots.forEach { emit(sink, it, emptyList(), rnd, rowsOf, value) }
    }

    private fun emit(
        sink: PlacementSink, group: String, parentReps: List<Int>, rnd: Random,
        rowsOf: (String, List<Int>, Random) -> Int, value: ValueStrategy,
    ) {
        val n = if (group in model.repeatable) rowsOf(group, parentReps, rnd) else 1
        for (idx in 1..n) {
            val reps = parentReps + idx
            sink.group(group, *reps.toIntArray())
            fieldsByGroup[group]?.forEach { f ->
                val fieldReps = (reps + 1).toIntArray()   // append the field's own index (always 1)
                when (val v = value.value(f, reps + 1, rnd)) {
                    null -> sink.emptyField(f.path, *fieldReps)
                    else -> sink.field(f.path, v, *fieldReps)
                }
            }
            childGroups[group]?.forEach { child -> emit(sink, child, reps, rnd, rowsOf, value) }
        }
    }

    private companion object {
        fun prefixes(path: String): List<String> {
            val parts = path.trim('/').split('/')
            return (1..parts.size).map { "/" + parts.take(it).joinToString("/") }
        }

        fun parentGroup(path: String): String = "/" + path.trim('/').split('/').dropLast(1).joinToString("/")

        fun depth(path: String): Int = path.count { it == '/' }
    }
}

/** Decides a field's value during generation — `null` leaves the field empty (present-but-unfilled). */
fun interface ValueStrategy {
    fun value(field: FieldDef, reps: List<Int>, rnd: Random): String?
}

/**
 * The default [ValueStrategy]: a **valid** value for each [Kind] — NUMBER respecting [FieldDef.scale] and
 * [FieldDef.signed], dates/times/ranges rendered in the field's [FieldDef.format]. Never empties a field
 * (compose with a wrapper that returns null for the empty case — e.g. the fuzz net's fill-or-empty strategy).
 */
object ValidValues : ValueStrategy {
    override fun value(field: FieldDef, reps: List<Int>, rnd: Random): String = when (field.kind) {
        Kind.NUMBER -> number(field, rnd)
        Kind.STRING -> "s" + reps.joinToString("_") + "_" + rnd.nextInt(0, 1000)
        Kind.BOOLEAN -> rnd.nextBoolean().toString()
        Kind.CONFIRM -> "true"   // a confirm has no `false` (that is a formal error) — true or empty only
        Kind.DATE -> renderByFormat(field.format ?: "yyyy-MM-dd", 2024, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
        Kind.TIME -> renderByFormat(field.format ?: "HH:mm:ss", h = rnd.nextInt(24), mi = rnd.nextInt(60), s = rnd.nextInt(60))
        Kind.DATE_RANGE -> {
            val fmt = field.format ?: "yyyy-MM-dd"
            val sep = field.rangeSeparator ?: "-"
            renderByFormat(fmt, 2024, 1, 1) + sep + renderByFormat(fmt, 2024, 12, 31)
        }
    }

    private fun number(field: FieldDef, rnd: Random): String {
        val whole = rnd.nextInt(0, 1000)
        val scale = field.scale ?: 0
        val frac = if (scale > 0 && rnd.nextBoolean())
            "." + (1..(1 + rnd.nextInt(scale))).joinToString("") { rnd.nextInt(0, 10).toString() } else ""
        val magnitude = "$whole$frac"
        // Never sign a zero magnitude — the kernel forbids a signed zero (`zahlNullEnthaeltVorzeichen`), so
        // `-0`/`-0.00` would be INVALID; ValidValues only ever emits kernel-valid values.
        val sign = if (field.signed && rnd.nextBoolean() && magnitude.any { it in '1'..'9' }) "-" else ""
        return "$sign$magnitude"
    }

    /**
     * Render `y/M/d/H/m/s` runs of a kernel format pattern (e.g. `dd.MM.yyyy`, `yyyy-MM-dd'T'HH:mm:ss`) from
     * the given components, each zero-padded to the run length; non-pattern characters are literal, and a
     * `'...'` section is a literal block (the ISO datetime `'T'`). The inverse of `DateMath.parseRangeToIso`'s
     * fragment scan — kept local (different direction) so the generator stays self-contained.
     */
    private fun renderByFormat(format: String, y: Int = 0, mo: Int = 0, d: Int = 0, h: Int = 0, mi: Int = 0, s: Int = 0): String {
        val sb = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            when {
                c in "yMdHms" -> {
                    var j = i
                    while (j < format.length && format[j] == c) j++
                    val len = j - i
                    val v = when (c) { 'y' -> y; 'M' -> mo; 'd' -> d; 'H' -> h; 'm' -> mi; else -> s }
                    val str = v.toString().padStart(len, '0')
                    sb.append(if (c == 'y' && str.length > len) str.takeLast(len) else str)
                    i = j
                }
                c == '\'' -> { i++; while (i < format.length && format[i] != '\'') { sb.append(format[i]); i++ }; i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
