package io.github.mbackschat.a12.dm.interpreter.gen

import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.random.Random

/**
 * Generates a **best-effort, model-derived sample candidate** document instance from a [ModelDef] into a
 * [PlacementSink]. It walks
 * the model's group tree (derived from the field paths + the declared repeatable groups), instantiates the
 * requested number of rows per repeatable group, and emits a value per field from a [ValueStrategy] — by
 * default a best-effort in-format value for the field's [Kind] (respecting NUMBER scale/sign, date/time/range
 * formats). It is a candidate, **not** a guarantee: placements are addressable, but caller-selected row counts
 * may exceed a group's declared maximum, so the output is not necessarily structurally or model accepted.
 *
 * Kernel-free and multiplatform — so it serves three consumers from one place:
 * - the **`:adapter` differential fuzz** net (random generated documents, assert interp == kernel);
 * - the **perf harness** (deterministic strategies that drive a known firing fraction);
 * - the **`dmtool model seed`** verb on the native image (model → best-effort sample candidate → JSON).
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

    /**
     * Java-friendly convenience (the shape `dmtool model seed` drives): populate with **fixed** per-group row counts
     * — a repeatable group listed in [rowsByGroup] gets that many rows, any other gets [defaultRows] — and the
     * default [ValidValues] strategy (a best-effort per-field heuristic, not a validity guarantee). Avoids passing a Kotlin function/`Random`
     * from Java.
     */
    fun populate(sink: PlacementSink, seed: Long, rowsByGroup: Map<String, Int>, defaultRows: Int) =
        populate(sink, seed, { group, _, _ -> rowsByGroup[group] ?: defaultRows }, ValidValues)

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
 * The default [ValueStrategy]: a **best-effort, constraint-aware** value for each [Kind] (a per-field local
 * heuristic, **not** a whole-document validity guarantee) — NUMBER respecting [FieldDef.scale],
 * [FieldDef.signed] and its declared `min/maxValue` range, STRING respecting its declared `min/maxLength`,
 * dates/times/ranges rendered in the field's [FieldDef.format]. **Constraint-aware** (S2 seeding): drawing a
 * value inside a field's declared constraints keeps the kernel's per-cell formal checks (`zahlZuKlein`,
 * `stringZuLang`, `stringFalschesMuster`, …) from firing and *suppressing the very rules a differential means
 * to exercise. It returns `null` (leaves the field empty) only for a STRING whose `pattern` it cannot cheaply
 * satisfy — the sanctioned leave-empty fallback (compose with a wrapper for other empty cases, e.g. the fuzz
 * net's fill-or-empty strategy).
 */
object ValidValues : ValueStrategy {
    override fun value(field: FieldDef, reps: List<Int>, rnd: Random): String? = when {
        // An enumeration field (stored as a STRING with declared members) MUST draw a real member — a generic
        // string would be a non-member, an invalid value. Picks one of the declared values at random.
        field.enumValues.isNotEmpty() -> field.enumValues[rnd.nextInt(field.enumValues.size)]
        // A STRING pattern (unless noValueValidation waives all string checks): satisfy it cheaply, else leave
        // empty (S2 MEDIUM). A satisfying sample is VERIFIED against the pattern before use, so a wrong guess is
        // discarded (→ empty), never emitted; an empty value skips the pattern check (no stringFalschesMuster).
        field.pattern != null && !field.noValueValidation -> patternSample(field.pattern)
        else -> byKind(field, reps, rnd)
    }

    private fun byKind(field: FieldDef, reps: List<Int>, rnd: Random): String = when (field.kind) {
        Kind.NUMBER -> number(field, rnd)
        Kind.STRING -> fitLength("s" + reps.joinToString("_") + "_" + rnd.nextInt(0, 1000), field)
        Kind.BOOLEAN -> rnd.nextBoolean().toString()
        Kind.CONFIRM -> "true"   // a confirm has no `false` (that is a formal error) — true or empty only
        Kind.DATE -> renderByFormat(field.format ?: "yyyy-MM-dd", 2024, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
        Kind.TIME -> renderByFormat(field.format ?: "HH:mm:ss", h = rnd.nextInt(24), mi = rnd.nextInt(60), s = rnd.nextInt(60))
        Kind.DATE_TIME -> renderByFormat(field.format ?: "yyyy-MM-dd'T'HH:mm:ss",
            2024, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60))
        Kind.DATE_RANGE -> {
            val fmt = field.format ?: "yyyy-MM-dd"
            val sep = field.rangeSeparator ?: "-"
            renderByFormat(fmt, 2024, 1, 1) + sep + renderByFormat(fmt, 2024, 12, 31)
        }
    }

    private fun number(field: FieldDef, rnd: Random): String {
        // A declared min/maxValue bound is itself a valid in-range value (a consistent model has min <= max),
        // and drawing one at random exercises BOTH ends across seeds — an in-range value without decimal
        // arithmetic in commonMain (S2 NUMBER = SMALL). Unbounded fields keep the random-magnitude path below.
        val min = field.minValue
        val max = field.maxValue
        if (min != null || max != null) {
            return when {
                min != null && max != null -> if (rnd.nextBoolean()) min else max
                min != null -> min
                else -> max!!
            }
        }
        val whole = rnd.nextInt(0, 1000)
        val scale = field.scale ?: 0
        val frac = if (scale > 0 && rnd.nextBoolean())
            "." + (1..(1 + rnd.nextInt(scale))).joinToString("") { rnd.nextInt(0, 10).toString() } else ""
        val magnitude = "$whole$frac"
        // Never sign a zero magnitude — the kernel forbids a signed zero (`zahlNullEnthaeltVorzeichen`), so
        // `-0`/`-0.00` would be a formal error; ValidValues avoids emitting the values it models as formal errors.
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

    /** Trim/pad [s] into a STRING field's declared `[minLength, maxLength]` (S2 STRING = TRIVIAL). Padding uses
     *  `'x'` (never a newline — the kernel forbids those by default). A consistent model has min <= max. */
    private fun fitLength(s: String, field: FieldDef): String {
        var r = s
        field.maxLength?.let { if (r.length > it) r = r.take(it) }
        field.minLength?.let { if (r.length < it) r = r.padEnd(it, 'x') }
        return r
    }

    /**
     * A minimal string satisfying [pattern], or `null` when the pattern is beyond this cheap sampler (S2 PATTERN
     * = MEDIUM, with the leave-empty fallback). Handled: literals, escapes (`\d`→digit, `\w`→letter, `\s`→space),
     * `.`, `[...]` classes (first member / range start), and the quantifiers `{n}` / `{n,m}` / `+` / `*` / `?`; a
     * `(group)` is skipped only when its quantifier permits zero (`*` / `?` / `{0,…}`), else the sampler bails.
     * **The candidate is always verified** with a full-match against the real [Regex] — a wrong guess yields
     * `null` (an empty cell), never an invalid value — so an over-simple sampler stays SOUND, just less covering.
     */
    private fun patternSample(pattern: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '^', '$' -> i++                                            // anchors contribute nothing
                '(' -> {
                    val close = matchingParen(pattern, i) ?: return null
                    // a group is only omissible when its quantifier allows zero occurrences
                    if (!quantifierAllowsZero(pattern, close + 1)) return null
                    i = close + 1 + quantifierLength(pattern, close + 1)
                }
                '[' -> {
                    val close = pattern.indexOf(']', i + 1).takeIf { it > 0 } ?: return null
                    val atom = firstClassChar(pattern.substring(i + 1, close)) ?: return null
                    i = appendAtom(sb, atom, pattern, close + 1)
                }
                '\\' -> {
                    if (i + 1 >= pattern.length) return null
                    val atom = escapedAtom(pattern[i + 1]) ?: return null
                    i = appendAtom(sb, atom, pattern, i + 2)
                }
                '.' -> i = appendAtom(sb, "a", pattern, i + 1)
                '*', '+', '?', '{', '|', ')' -> return null                // stray/unsupported metacharacter
                else -> i = appendAtom(sb, c.toString(), pattern, i + 1)
            }
        }
        val sample = sb.toString()
        return if (Regex(pattern).matches(sample)) sample else null
    }

    /** Append [atom] repeated per the quantifier at [qi] (min count: `{n}`→n, `{n,m}`→n, `+`→1, `*`/`?`→0, none→1);
     *  returns the index past the quantifier. */
    private fun appendAtom(sb: StringBuilder, atom: String, pattern: String, qi: Int): Int {
        repeat(minRepeat(pattern, qi)) { sb.append(atom) }
        return qi + quantifierLength(pattern, qi)
    }

    private fun escapedAtom(c: Char): String? = when (c) {
        'd' -> "0"; 'w' -> "a"; 's' -> " "
        '.', '\\', '/', '-', '+', '*', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$' -> c.toString()
        else -> null
    }

    /** The first concrete char of a `[...]` class body (`abc`→`a`, `a-z`→`a`, `\d`→`0`); null if empty/negated. */
    private fun firstClassChar(body: String): String? = when {
        body.isEmpty() || body.startsWith("^") -> null
        body.startsWith("\\") && body.length >= 2 -> escapedAtom(body[1])
        else -> body[0].toString()
    }

    private fun minRepeat(pattern: String, qi: Int): Int = when {
        qi >= pattern.length -> 1
        pattern[qi] == '+' -> 1
        pattern[qi] == '*' || pattern[qi] == '?' -> 0
        pattern[qi] == '{' -> pattern.substring(qi + 1, pattern.indexOf('}', qi).coerceAtLeast(qi + 1))
            .substringBefore(',').trim().toIntOrNull() ?: 1
        else -> 1
    }

    private fun quantifierLength(pattern: String, qi: Int): Int = when {
        qi >= pattern.length -> 0
        pattern[qi] == '+' || pattern[qi] == '*' || pattern[qi] == '?' -> 1
        pattern[qi] == '{' -> (pattern.indexOf('}', qi).takeIf { it > 0 }?.let { it - qi + 1 }) ?: 0
        else -> 0
    }

    private fun quantifierAllowsZero(pattern: String, qi: Int): Boolean = when {
        qi >= pattern.length -> false
        pattern[qi] == '*' || pattern[qi] == '?' -> true
        pattern[qi] == '{' -> minRepeat(pattern, qi) == 0
        else -> false
    }

    /** The index of the `)` matching the `(` at [open], honoring nesting; null if unbalanced. */
    private fun matchingParen(pattern: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++                                  // skip an escaped char
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }
}
