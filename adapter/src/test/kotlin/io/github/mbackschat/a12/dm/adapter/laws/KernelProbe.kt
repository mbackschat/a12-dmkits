package io.github.mbackschat.a12.dm.adapter.laws

/**
 * **KernelProbe** — observe the REAL kernel's behavior for a candidate condition across a battery of edge
 * documents, **before** implementing the operator in the interpreter. This makes the #1 "probe first" rule the
 * path of least resistance: instead of reading kernel source and guessing (the lesson of the `CurrentRepetition`
 * miss — shipped wrong, caught reactively by the fuzz), one call shows what the kernel actually does on the
 * empty / malformed / boundary / sparse-row cases at once.
 *
 * For each named scenario it reports: does the probe rule **fire**? its message **type** (OMISSION/VALUE)? and
 * **every** fired code (formal errors included, so a suppression shows up as the rule going quiet while a `zahl…`
 * appears). Kernel-only (`:adapter`, via [RuntimeLaws]). Use it from a probe test (see `KernelProbeTest`): build
 * the model + scenarios, call [report], read the printed table, then write the interpreter + its `*DiffTest`.
 * NOT a CI gate — an exploration tool; the gates are the `*DiffTest`s + the fuzz net.
 */
object KernelProbe {

    /** One scenario's kernel outcome: did the probe code fire, its message type, and all fired codes (sorted). */
    data class Outcome(val scenario: String, val fires: Boolean, val type: String?, val codes: List<String>)

    /**
     * Inject [condition] as rule [code] at [group] (error field [errorRel]) into [model], then run the kernel
     * over each named [scenarios] document and collect the per-scenario [Outcome].
     */
    fun probe(
        model: String, group: String, code: String, errorRel: String, condition: String,
        scenarios: List<Pair<String, RuntimeLaws.Doc>>,
    ): List<Outcome> {
        val withRule = RuntimeLaws.withRule(model, group, code, errorRel, code, condition)
        return scenarios.map { (name, doc) ->
            val msgs = RuntimeLaws.messages(withRule, doc)
            val probe = msgs.firstOrNull { it.code() == code }
            Outcome(name, probe != null, probe?.type()?.toString(), msgs.map { it.code() }.sorted())
        }
    }

    /** A printable table of [probe]'s outcomes — the thing to read when learning an operator's semantics. */
    fun report(
        model: String, group: String, code: String, errorRel: String, condition: String,
        scenarios: List<Pair<String, RuntimeLaws.Doc>>,
    ): String = buildString {
        appendLine("── KERNEL PROBE ──  $condition")
        appendLine("   rule [$code] @ $group   errorField $errorRel")
        appendLine("   " + "%-30s %-6s %-9s %s".format("scenario", "fires", "type", "all fired codes"))
        for (o in probe(model, group, code, errorRel, condition, scenarios)) {
            appendLine("   " + "%-30s %-6s %-9s %s".format(o.scenario, o.fires, o.type ?: "—", o.codes))
        }
    }

    // ---- battery builders for the recurring shapes ------------------------------------------------------

    /**
     * Build a document for a **single-level repeatable group** (`rootGroup` ⊃ `repGroup`): one row per
     * [rowValues] entry, with [indexField] always filled (distinct `K1`, `K2`, …, so no `uniqueIndex` noise)
     * and [field] set per entry (`null` = leave empty). Assumes the 2-level shape — root at depth 1, `repGroup`
     * at depth 2, fields at depth 3 (e.g. `/Cart` ⊃ `/Cart/Lines` ⊃ `ItemId`/`Qty`).
     */
    fun rowsDoc(
        rootGroup: String, repGroup: String, indexField: String, field: String, rowValues: List<String?>,
    ): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(rootGroup, 1)
        rowValues.forEachIndexed { i, v ->
            val r = i + 1
            doc.group(repGroup, 1, r).field(indexField, "K$r", 1, r, 1)
            if (v != null) doc.field(field, v, 1, r, 1)
        }
        return doc
    }

    /**
     * The standard row-fill patterns for probing a per-row / aggregate / iteration operator over a repeatable
     * field — the shape that keeps surprising (empties skipped? malformed suppresses? sparse rows? row index?).
     * Pair each `(name, rowValues)` with [rowsDoc] to get scenarios. [valid]/[malformed] are sample cell values.
     */
    fun rowPatterns(valid: String, malformed: String): List<Pair<String, List<String?>>> = listOf(
        "0 rows" to emptyList(),
        "1 valid" to listOf(valid),
        "1 empty" to listOf(null),
        "1 malformed" to listOf(malformed),
        "valid, valid" to listOf(valid, valid),
        "empty, valid" to listOf(null, valid),
        "valid, empty" to listOf(valid, null),
        "malformed, valid" to listOf(malformed, valid),
        "valid, malformed" to listOf(valid, malformed),
        "empty, empty, valid" to listOf(null, null, valid),
    )
}
