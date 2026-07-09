package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * A **model-level delta-debugger** for the WI-T corpus triage — the document-level [FuzzDiagnostics.shrink]
 * lifted to the MODEL (the IG44→IG56 drill line, INTERPRETER-GAPS). Given a diverging kernel DM-JSON model and a divergence
 * PREDICATE, it greedily removes/simplifies model elements — rules, computations, computation alternatives,
 * common/per-alternative preconditions, fields, and groups — keeping each reduction that PRESERVES the
 * divergence, until a minimal model remains. So a divergence buried in a large composite corpus model is
 * reduced to the smallest structure that still reproduces it — the "minimal interaction" the isolated
 * own-domain probes couldn't reach (every isolated tiered computation matched the kernel; the divergence is an
 * interaction).
 *
 * **Greedy ddmin to a fixpoint.** Each pass enumerates every one-element removal (an `elements[i]` entry, a
 * `computationAlternatives[j]`, a `commonPrecondition`, an alternative's `precondition`), tries them one at a
 * time, and accepts the FIRST that still diverges — then restarts the scan on the now-smaller model. When a
 * full pass accepts nothing, the model is minimal. A removal that breaks model consistency (an unresolved ref,
 * a kernel/interpreter load failure) makes the predicate false, so it is simply rejected — the reducer never
 * has to reason about validity, only about "does it still diverge". Bounded by [maxRuns] engine invocations so
 * a timebox is enforceable.
 *
 * **License boundary (absolute).** Operates ENTIRELY in memory on a model read in place from an EUPL/proprietary
 * corpus; persists NOTHING. The reduced model is returned to the caller for IN-MEMORY inspection only; the only
 * thing safe to surface is [redactedShape] (operator/structure keywords kept, every field name / path / literal
 * redacted — the mechanism, never the expression). The caller re-expresses the minimal shape own-domain before
 * committing anything.
 *
 * @param diverges the predicate: does this candidate kernel-JSON model still exhibit the tracked divergence?
 *                 (typically: prepare → seed(fixed) → compute on both engines → tracked pointer CLEARED in the
 *                 kernel AND VALUE in the interpreter). MUST be side-effect-free and swallow its own failures
 *                 (a broken candidate → false).
 */
class ModelShrinker(private val diverges: (String) -> Boolean, private val maxRuns: Int = 4000) {

    private val mapper = ObjectMapper()
    private var runs = 0

    /** The number of predicate (engine) invocations spent so far — for the caller's timebox reporting. */
    val runsSpent: Int get() = runs

    /**
     * Shrink [kernelJson] to a minimal model that still diverges. Requires the seed model to diverge (else it
     * throws — the caller must hand a confirmed divergence). Returns the minimal kernel JSON (in memory only).
     */
    fun shrink(kernelJson: String): String {
        require(divergesQuietly(kernelJson)) { "the seed model does not diverge under the predicate" }
        var current = kernelJson
        var changed = true
        while (changed && runs < maxRuns) {
            changed = false
            val root = mapper.readTree(current) as ObjectNode
            for (site in reductionSites(root)) {
                if (runs >= maxRuns) break
                val candidate = mapper.writeValueAsString(site.apply(root.deepCopy()))
                if (candidate != current && divergesQuietly(candidate)) {
                    current = candidate
                    changed = true
                    break   // restart the scan on the smaller model
                }
            }
        }
        return current
    }

    private fun divergesQuietly(json: String): Boolean {
        runs++
        return try { diverges(json) } catch (t: Throwable) { false }
    }

    // ── reduction enumeration ──────────────────────────────────────────────────────────────────────────────

    /** A single reduction: mutate a fresh deep-copied root in place and return it. */
    private fun interface Reduction { fun apply(rootCopy: ObjectNode): ObjectNode }

    /** Every one-element removal available on [root], most-impactful first (whole elements before sub-parts, so a
     *  single accepted removal collapses the most structure). Each is applied to a fresh deep copy. */
    private fun reductionSites(root: ObjectNode): List<Reduction> {
        val elementRemovals = mutableListOf<Reduction>()
        val subRemovals = mutableListOf<Reduction>()
        walk(root, ArrayDeque()) { steps, node ->
            // Remove a whole element from an `elements` array.
            if (steps.size >= 2 && steps[steps.size - 2] == "elements") {
                elementRemovals += removalAt(steps.toList())
            }
            // Sub-part removals inside a Computation payload.
            (node as? ObjectNode)?.get("Computation")?.let { comp ->
                (comp.get("computationAlternatives") as? ArrayNode)?.let { alts ->
                    if (alts.size() > 1) for (j in 0 until alts.size())
                        subRemovals += removalAt(steps + listOf("Computation", "computationAlternatives", j))
                    for (j in 0 until alts.size())
                        if ((alts.get(j) as? ObjectNode)?.has("precondition") == true)
                            subRemovals += removalAt(steps + listOf("Computation", "computationAlternatives", j, "precondition"))
                }
                if ((comp as ObjectNode).has("commonPrecondition"))
                    subRemovals += removalAt(steps + listOf("Computation", "commonPrecondition"))
            }
        }
        return elementRemovals + subRemovals
    }

    /** Walk the tree, invoking [visit] with the step-path (object keys / array indices) and node at each object. */
    private fun walk(node: JsonNode, steps: ArrayDeque<Any>, visit: (List<Any>, JsonNode) -> Unit) {
        if (node is ObjectNode) {
            visit(steps.toList(), node)
            node.fieldNames().forEachRemaining { name ->
                steps.addLast(name); walk(node.get(name), steps, visit); steps.removeLast()
            }
        } else if (node is ArrayNode) {
            for (i in 0 until node.size()) { steps.addLast(i); walk(node.get(i), steps, visit); steps.removeLast() }
        }
    }

    /** A reduction that navigates a fresh root copy to [steps]'s parent and removes the final step (array index
     *  → drop that entry; object key → drop that field). */
    private fun removalAt(steps: List<Any>): Reduction = Reduction { rootCopy ->
        var parent: JsonNode = rootCopy
        for (i in 0 until steps.size - 1) {
            val s = steps[i]
            parent = if (s is Int) parent.get(s) else parent.get(s as String)
        }
        when (val last = steps.last()) {
            is Int -> (parent as ArrayNode).remove(last)
            is String -> (parent as ObjectNode).remove(last)
        }
        rootCopy
    }

    // ── license-safe structural digest ─────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * A license-safe structural digest of a (minimal) kernel-JSON model: element counts by type, plus each
         * computation's alternative count / commonPrecondition presence and the [redactCondition]-redacted
         * shapes of its preconditions + operations (operators kept, field names / paths / literals stripped).
         * This is the ONLY thing safe to print/commit — the mechanism, never the expression.
         */
        fun redactedShape(kernelJson: String): String {
            val mapper = ObjectMapper()
            val root = mapper.readTree(kernelJson)
            val counts = linkedMapOf("Field" to 0, "Group" to 0, "Rule" to 0, "Computation" to 0)
            val comps = mutableListOf<String>()
            val rules = mutableListOf<String>()
            fun visit(n: JsonNode) {
                if (n is ObjectNode) {
                    n.get("type")?.asText()?.let { if (it in counts) counts[it] = counts.getValue(it) + 1 }
                    n.get("Computation")?.let { comps += compShape(it) }
                    n.get("Rule")?.get("errorCondition")?.asText()?.let { rules += "Rule: ${redactCondition(it)}" }
                    n.fieldNames().forEachRemaining { visit(n.get(it)) }
                } else if (n is ArrayNode) n.forEach { visit(it) }
            }
            visit(root)
            return buildString {
                append("elements: ").append(counts.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
                comps.forEach { append("  ").append(it).append('\n') }
                rules.forEach { append("  ").append(it).append('\n') }
            }.trimEnd()
        }

        private fun compShape(comp: JsonNode): String {
            val alts = comp.get("computationAlternatives") as? ArrayNode
            val common = comp.get("commonPrecondition")?.asText()?.let { "commonPrecond=${redactCondition(it)}" } ?: "no-commonPrecond"
            val altShapes = alts?.joinToString("; ") { a ->
                val pre = a.get("precondition")?.asText()?.let { "if ${redactCondition(it)}" } ?: "unconditional"
                val op = a.get("operation")?.asText()?.let { redactCondition(it) } ?: "?"
                "$pre → $op"
            } ?: "?"
            return "Computation[${alts?.size() ?: 0} alt] $common | $altShapes"
        }

        /** Redact a condition/operation to its operator/structure shape — keep operator + logical keywords and
         *  ALL structural punctuation (incl. the `*`/`/`/`+`/`-` ARITHMETIC operators — the mechanism), strip
         *  every domain identifier (field name), quoted literal, and number. So `FieldFilled(/A/B) And [/A/C] > 5`
         *  → `FieldFilled(_) And [_] > #`, and `RoundDown([A] * 1000000 / [B], 2)` → `RoundDown([_] * # / [_], #)`
         *  — the division/multiplication survive (they are mechanism, not expression). */
        fun redactCondition(cond: String): String {
            var s = cond.replace(Regex("\"[^\"]*\""), "\"…\"")     // quoted strings
            // tokens: keep known keywords, redact other identifiers (field names).
            s = Regex("[A-Za-z_][A-Za-z0-9_]*").replace(s) { m ->
                if (m.value in KEYWORDS) m.value else "_"
            }
            s = s.replace(Regex("\\b\\d+(\\.\\d+)?\\b"), "#")       // numbers
            // Collapse a redacted PATH run — an absolute `/_/_` or a segmented `_/_` (a `/` GLUED to an identifier
            // on BOTH sides) — to a single `_`; a spaced/bracketed `/` (arithmetic division) is left untouched.
            s = s.replace(Regex("/?_(?:/_)+"), "_")
            s = s.replace(Regex("_{2,}"), "_")
            return s.replace(Regex("\\s+"), " ").trim()
        }

        // Operator + logical + constant keywords worth keeping in a redacted shape (mechanism-revealing, not
        // domain content). Deliberately broad; anything else is a field name and gets redacted.
        private val KEYWORDS = setOf(
            "And", "Or", "Not", "Having", "In", "For", "RuleGroup", "True", "False", "Today", "Now", "BaseYear",
            "FirstDay", "LastDay",
            "FieldFilled", "FieldNotFilled", "AllFieldsFilled", "GroupFilled", "GroupNotFilled",
            "NoFieldFilled", "AtLeastOneFieldFilled", "MoreThanOneFieldFilled", "NotAllFieldsFilled",
            "NotExactlyOneFieldFilled", "FieldsNotCollectivelyFilled", "AllGroupsFilled", "NoGroupFilled",
            "AtLeastOneGroupFilled", "NotAllGroupsFilled", "GroupsNotCollectivelyFilled",
            "FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList",
            "AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",
            "NotAllFieldValuesIncludedInValueList", "DateRangesOverlap", "AtLeastOneDateRangeOverlaps",
            "FieldValuesNotUnique", "Valid", "Invalid", "RepetitionNotUnique", "PatternMatched", "PatternViolated",
            "Sum", "MinValue", "MaxValue", "NumberOfDifferentValues", "SumOfProducts", "FirstFilledValue",
            "NumberOfFilledFields", "NumberOfFilledGroups", "Min", "Max", "Round", "Abs", "RangeAsNumber",
            "Length", "FieldValueAsNumber", "FieldValueAsString", "CurrentRepetition", "NumberOfValueInFields",
            "MinValueOfField", "MaxValueOfField", "Date", "Time", "DateTime", "ValueAsDate",
            "YearFromDate", "MonthFromDate", "DayFromDate", "QuarterFromDate", "HoursFromTime", "MinutesFromTime",
            "SecondsFromTime", "DifferenceInDays", "DifferenceInMonths", "DifferenceInYears", "DifferenceInHours",
            "DifferenceInMinutes", "DifferenceInSeconds", "AddDays", "AddMonths", "AddYears",
        )
    }
}
