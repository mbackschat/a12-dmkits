package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a semantic-index key addresses the repetition it keys, the classes the checker names against the classes
 * A12 names, in every operand family whose gate reads a field.
 *
 * A12 addresses a keyed read exactly as the declaration's own iteration does and judges a defective key before
 * the family's gate ([KERNEL-FINDINGS KF476], `rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`). So each
 * keyed row has a TWIN the checker already reads: the same field from a rule declared inside `Claims`, or the
 * same read unkeyed inside a `Having` body, where the filter chain addresses every level.
 *
 * **Two ways to fail.** Any class A12 does not name fails outright, since a keyed operand the checker reads past
 * its key names the family's class where A12 names the key's. SILENCE fails only where the twin agrees: the
 * checker may stay quiet about a key, but not about a family it reads everywhere else.
 *
 * Own domain (insurance claims); `Riders` declares the fields of `Claims` and no index field, and `Endorsements`
 * indexes them on the enumeration `Status`, where a written constant is a key the checker cannot vouch for.
 */
class CheckerKeyedOperandAgreementTest {

    private val policy = DocumentModelDraft.named("policy-keyed").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .stringField("Code") { }
            .numberField("Limit") { it.fractionDigits(0) }
            .dateField("Opened") { it.format("yyyy-MM-dd") }
            .enumField("Stage") { e -> e
                .value("OPEN") { it.label("en_US", "Open claim") }
                .value("CLOSED") { it.label("en_US", "Closed claim") } }
            .group("Claims") { g -> claimFields(g.repeatable(20).indexField("ClaimRef")) }
            .group("Riders") { g -> claimFields(g.repeatable(5)) }
            .group("Endorsements") { g -> claimFields(g.repeatable(5).indexField("Status")) }
            .group("Notes") { g -> g.repeatable(5).stringField("Text") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theCheckerReadsAKeyedOperandAsA12Does() {
        val failures = mutableListOf<String>()
        var compared = 0
        for ((family, probe) in PROBES) {
            val local = compare(IN_CLAIMS, probe.with("%s"))
            val filterTwin = compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text Having ${probe.with("Claims/%s")}) > 0")
            val rows = linkedMapOf(
                "string key" to (compare(AT_ROOT, probe.with("Claims/%s For \"X\"")) to local),
                "field key" to (compare(AT_ROOT, probe.with("Claims/%s For Code")) to local),
                "key on the iterated level" to (compare(IN_CLAIMS, probe.with("%s For \"X\"")) to local),
                "number literal key on the iterated level" to (compare(IN_CLAIMS, probe.with("%s For 5")) to null),
                "keyed in a filter body" to (compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text Having " +
                    "${probe.with("Claims/%s For \"X\"")}) > 0") to filterTwin),
                "number literal key in a filter body" to (compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text " +
                    "Having ${probe.with("Claims/%s For 5")}) > 0") to null),
            ) + DEFECTS.mapValues { (_, form) -> compare(AT_ROOT, probe.with(form)) to null }
            for ((row, pair) in rows) {
                val (keyed, twin) = pair
                compared++
                val verdict = keyed.verdict() ?: continue
                if (verdict != SILENT || twin?.verdict() == null && twin?.a12?.isNotEmpty() == true) {
                    failures += "$family — $row: $verdict ${keyed.ours.sorted()}"
                }
            }
        }
        // A floor, not a count: it answers whether the grid reached both engines at all.
        assertTrue(compared > 200, "the grid compared only $compared rows")
        assertEquals(KNOWN_DIVERGENCES, failures, "keyed operands the checker reads unlike A12")
    }

    /** One row's two answers. */
    private class Comparison(val a12: Set<String>, val ours: Set<String>) {
        /** Null where the two agree; otherwise the kind of disagreement. */
        fun verdict(): String? = when {
            a12 == ours -> null
            a12.isEmpty() -> "OVER-REJECT"
            ours.isEmpty() -> SILENT
            ours.containsAll(a12) -> "EXTRA"
            else -> "WRONG-CLASS"
        }
    }

    private fun compare(at: Pair<String, String>, probe: String): Comparison {
        val (group, errorLeaf) = at
        val draft = DraftRule(group, "ProbeRule", "../$errorLeaf", "FieldFilled($errorLeaf) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(policy, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse(UNKEYED) }.toSet()
        }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return Comparison(a12, ours)
    }

    /** A family's probe, with the operand text [form] (a `%s` where the field stands) at each field it reads. */
    private class Probe(val template: String, vararg val fields: String) {
        fun with(form: String): String = template.format(*fields.map { form.format(it) }.toTypedArray())
    }

    private companion object {
        const val SILENT = "SILENT"
        const val UNKEYED = "(no code)"
        val AT_ROOT = "/Policy" to "Reference"
        val IN_CLAIMS = "/Policy/Claims" to "ClaimRef"

        /** The kernel grid's carriers, each probed on a field its own gate refuses. */
        val PROBES = linkedMapOf(
            "arity" to Probe("Sum(%s) > 0", "Amount"),
            "field-list all numbers" to Probe("Sum(%s, %s) > 0", "Memo", "Amount"),
            "value-in-fields constant kind" to Probe("NumberOfValueInFields(5 In %s, %s) > 0", "Memo", "ClaimRef"),
            "range operand" to Probe("RangeAsString(%s, 1, 2) == \"a\"", "Amount"),
            "value-as-date operand" to Probe("ValueAsDate(%s, FirstDay) > \"01.01.2020\"", "Memo"),
            "field-value operand" to Probe("FieldValueAsString(%s) == \"a\"", "Memo"),
            "rounding operand" to Probe("RoundUpValue(%s, 0) > 0", "Memo"),
            "comparison against a constant" to Probe("[%s] == 5", "Filed"),
            "boolean comparison" to Probe("[%s] == \"x\"", "Settled"),
            "string/enum comparison" to Probe("[%s] == \"PENDING\"", "Status"),
            "date-range list" to Probe("DateRangesOverlap(%s, %s)", "Memo", "ClaimRef"),
            "date arithmetic" to Probe("AddDays(%s, 1) < Today", "Memo"),
            "number scale comparison" to Probe("[%s] == [Limit]", "Amount"),
            "tolerance comparison" to Probe("[%s] DiffersWithToleranceRange1 5", "Memo"),
            "pattern comparison" to Probe("[%s] PatternMatched \"a+\"", "Amount"),
            "enum display comparison" to Probe("[%s] == [Stage]", "Status"),
            "rounding a computed value" to Probe("RoundUp([%s], 0) > 0", "Memo"),
            "date arithmetic amount" to Probe("AddDays(Opened, [%s]) < Today", "Memo"),
            "date arithmetic precision" to Probe("[Opened] < AddHours(%s, 1)", "Filed"),
            "entity-list duplicate" to Probe("Sum(%s, %s) > 0", "Amount", "Amount"),
        )

        /**
         * The rows that still disagree, for a reason that is not the key's (DEV-API-GAPS DG95). Both families are
         * silent on this fixture even OUTSIDE a filter, so inside one the filter walk's own class stands where
         * A12 names theirs, raised earlier in the body; the unkeyed twin fails the same way in fourteen families.
         * A fix deletes its row, and a row that starts agreeing fails here until it is deleted.
         */
        val KNOWN_DIVERGENCES = listOf(
            "comparison against a constant — keyed in a filter body: WRONG-CLASS [MVK_SEMANTIC_INDEX_NOT_ALLOWED]",
            "date arithmetic — keyed in a filter body: WRONG-CLASS [MVK_SEMANTIC_INDEX_NOT_ALLOWED]",
        )

        /** The key defects, each as the operand form it is spelled with. */
        val DEFECTS = linkedMapOf(
            "no index field on the keyed group" to "Riders/%s For \"X\"",
            "a Number key over a String index" to "Claims/%s For Limit",
            "a number literal key" to "Claims/%s For 5",
            "an iterating key" to "Claims/%s For Notes/Text",
            "a key beside a star" to "Claims*/%s For \"X\"",
            "a constant the enumeration index refuses" to "Endorsements/%s For \"PENDING\"",
            // A string key takes no filter, so this `Having` is the operand's: the text is no longer ONE index.
            "a string key and a filter on the operand" to "Claims/%s For \"X\" Having [Claims/ClaimRef] == \"a\"",
        )

        fun claimFields(g: DocumentModelDraft.GroupBuilder): DocumentModelDraft.GroupBuilder = g
            .stringField("ClaimRef") { }
            .numberField("Amount") { it.fractionDigits(2) }
            .stringField("Memo") { }
            .dateField("Filed") { it.format("yyyy-MM-dd") }
            .booleanField("Settled") { }
            .enumField("Status") { e -> e.value("OPEN").value("CLOSED") }
    }
}
