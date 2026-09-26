package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a semantic-index key addresses the repetition it keys, the classes the checker names against the classes
 * A12 names, in every operand family whose gate reads a field.
 *
 * A12 addresses a keyed read exactly as the declaration's own iteration does, addresses every level a read
 * inside a `Having` body crosses, and judges a defective key before the family's gate ([KERNEL-FINDINGS
 * KF476], `rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`). So each row has a TWIN the checker already
 * reads: the same field from a rule declared inside `Claims`.
 *
 * **Every row must agree, silence included.** The checker reads every key the chain judges and every key form
 * the grid writes, so a row it left silent would be a key it stopped reading, and one naming a class A12 does
 * not name would be a key it read past. The twin is each family's positive control: A12 must refuse it and the
 * checker must name the same class, or the family's rows prove nothing.
 *
 * Beside the key forms, each family is read in TEXT ORDER against an unaddressed read, where A12 names whichever
 * it reads first, and under a second indexed ancestor, which is `MVK_MULTIPLE_INDEX_FIELDS` on every gate
 * ([KERNEL-FINDINGS KF478]).
 *
 * Own domain (insurance claims); `Riders` declares the fields of `Claims` and no index field, and `Endorsements`
 * indexes them on the enumeration `Status`, whose constant key the chain judges against its value tokens
 * ([KERNEL-FINDINGS KF479]).
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

    /** The same model with the root also declaring an index, so every keyed read has a second indexed ancestor. */
    private val rootIndexed = ObjectMapper().let { mapper ->
        val root = mapper.readTree(policy)
        (root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group") as ObjectNode)
            .put("indexFieldName", "Reference")
        mapper.writeValueAsString(root)
    }

    private val validator = RuleValidator()

    @Test
    fun theCheckerReadsAKeyedOperandAsA12Does() {
        val failures = mutableListOf<String>()
        var compared = 0
        for ((family, probe) in PROBES) {
            val local = compare(IN_CLAIMS, probe.with("%s"))
            if (local.a12.isEmpty() || local.verdict() != null) {
                failures += "$family — local: the positive control A12 ${local.a12} ours ${local.ours}"
            }
            val keyed = probe.with("Claims/%s For \"X\"")
            val rows = linkedMapOf(
                "unkeyed from the root" to compare(AT_ROOT, probe.with("Claims/%s")),
                "string key" to compare(AT_ROOT, keyed),
                "enumeration constant key" to compare(AT_ROOT, probe.with("Endorsements/%s For \"OPEN\"")),
                "field key" to compare(AT_ROOT, probe.with("Claims/%s For Code")),
                "key on the iterated level" to compare(IN_CLAIMS, probe.with("%s For \"X\"")),
                "number literal key on the iterated level" to compare(IN_CLAIMS, probe.with("%s For 5")),
                "unkeyed in a filter body" to compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text Having " +
                    "${probe.with("Claims/%s")}) > 0"),
                "unkeyed in the filtered level's own body" to compare(AT_ROOT, "NumberOfFilledFields(Claims*/Memo " +
                    "Having ${probe.with("Claims/%s")}) > 0"),
                "keyed in a filter body" to compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text Having " +
                    "${probe.with("Claims/%s For \"X\"")}) > 0"),
                "keyed in the filtered level's own body" to compare(AT_ROOT, "NumberOfFilledFields(Claims*/Memo " +
                    "Having ${probe.with("Claims/%s For \"X\"")}) > 0"),
                "number literal key in the filtered level's own body" to compare(AT_ROOT,
                    "NumberOfFilledFields(Claims*/Memo Having ${probe.with("Claims/%s For 5")}) > 0"),
                "number literal key in a filter body" to compare(AT_ROOT, "NumberOfFilledFields(Notes*/Text " +
                    "Having ${probe.with("Claims/%s For 5")}) > 0"),
                "an unaddressed read ahead of the keyed read" to compare(AT_ROOT, "$UNADDRESSED And $keyed"),
                "the keyed read ahead of an unaddressed read" to compare(AT_ROOT, "$keyed And $UNADDRESSED"),
                "an enumeration constant key ahead of an unaddressed read" to compare(AT_ROOT,
                    "${probe.with("Endorsements/%s For \"OPEN\"")} And $UNADDRESSED"),
                "a refused enumeration constant ahead of an unaddressed read" to compare(AT_ROOT,
                    "${probe.with("Endorsements/%s For \"PENDING\"")} And $UNADDRESSED"),
                "a second indexed ancestor" to compare(AT_ROOT, probe.with("Claims/%s For \"X\""), rootIndexed),
                "a climbing read" to compare(IN_REVIEW, probe.with("../%s")),
                "a climbing keyed read" to compare(IN_REVIEW, probe.with("../%s For \"X\"")),
                "a climb to the root and back into Claims" to compare(IN_REVIEW, probe.with("../../Claims/%s")),
                "a climb past the root into Riders" to compare(IN_REVIEW, probe.with("../../Riders/%s")),
            ) + DEFECTS.mapValues { (_, form) -> compare(AT_ROOT, probe.with(form)) }
            for ((row, comparison) in rows) {
                compared++
                val verdict = comparison.verdict() ?: continue
                failures += "$family — $row: $verdict ${comparison.ours.sorted()} A12 ${comparison.a12.sorted()}"
            }
        }
        // The positive control is each family's local read above, which both engines must name alike and A12
        // must refuse; this floor only answers whether every row was reached.
        assertTrue(compared == PROBES.size * ROWS_PER_FAMILY, "the grid compared $compared rows")
        assertEquals(KNOWN_DIVERGENCES, failures, "keyed operands the checker reads unlike A12")
    }

    /**
     * A keyed GROUP beside a keyed field of it, where the entity-list pre-gates read the group's own fields
     * through the key ([KERNEL-FINDINGS KF475]): the same key repeats the field, another key does not. Both
     * engines must name the same class, since the checker reads these keys and has no reason to stay silent.
     */
    @Test
    fun theCheckerReadsAKeyedGroupOperandAsA12Does() {
        val rows = linkedMapOf(
            "the same key on the group and its field" to "Sum(Claims For \"X\", Claims/Amount For \"X\") > 0",
            "another key on the field" to "Sum(Claims For \"X\", Claims/Amount For \"Y\") > 0",
        )
        val actual = rows.mapValues { (_, probe) -> compare(AT_ROOT, probe).let { it.ours to it.a12 } }
        val a12 = linkedMapOf(
            "the same key on the group and its field" to setOf("MVK_DUPLICATE_PARAM2"),
            "another key on the field" to setOf("MVK_NO_NUMBER"),
        )
        assertEquals(a12.mapValues { (_, codes) -> codes to codes }, actual, "a keyed group operand, as (ours, A12)")
    }

    /** A desk whose repeatable `Claims` carries an enumeration projected through a covering and a wider category. */
    private val desk = DocumentModelDraft.named("policy-enum-source").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .enumField("Status") { e -> e.value("OPEN").value("CLOSED") }
            .group("Claims") { g -> g.repeatable(5)
                .enumField("Banded") { e -> e.value("OPEN").value("CLOSED").value("VOID")
                    .category("Band", "OPEN", "CLOSED", "OPEN")
                    .category("Wide", "OPEN", "CLOSED", "VOID") }
                .group("Review") { r -> r.enumField("Outcome") { e -> e.value("OPEN").value("CLOSED") } } } }
        .build().toJson()

    /**
     * An enumeration-valued computation reading its source through a STAR or a CLIMB, where the category
     * reader hands the access to the addressing test rather than refusing it as no bare spec. The local read is
     * the class's control; both engines must name the same class on every row.
     */
    @Test
    fun theCheckerReadsAnEnumComputationSourceAsA12Does() {
        val rows = linkedMapOf(
            "the local read of a wider category — the control" to
                Triple("/Policy/Claims", "../Review/Outcome", "[Banded -> Wide]"),
            "a starred read of a covering category" to Triple("/Policy", "../Status", "[Claims*/Banded -> Band]"),
            "a starred read of a wider category" to Triple("/Policy", "../Status", "[Claims*/Banded -> Wide]"),
            "a starred read of the wider field itself" to Triple("/Policy", "../Status", "[Claims*/Banded]"),
            // The category's own gates run before the bracket's star gate, so an undeclared category answers.
            "a starred read of an undeclared category" to Triple("/Policy", "../Status", "[Claims*/Banded -> Nope]"),
            "a climbing read of a covering category" to
                Triple("/Policy/Claims/Review", "../Outcome", "[../Banded -> Band]"),
            "a climbing read of a wider category" to
                Triple("/Policy/Claims/Review", "../Outcome", "[../Banded -> Wide]"),
            "a climbing read of the wider field itself" to
                Triple("/Policy/Claims/Review", "../Outcome", "[../Banded]"),
        )
        val actual = rows.mapValues { (_, row) ->
            val (group, target, operation) = row
            val draft = DraftComputation(group, "DerivedOutcome", target, null,
                listOf(DraftComputation.Alternative(null, operation)))
            val a12 = validator.check(desk, draft).let { result ->
                if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse(UNKEYED) }.toSet()
            }
            val ours = check(ModelSource(validator.inject(desk, draft))).findings
                .filter { it.justifiesRejection }.map { it.code }.toSet()
            ours to a12
        }
        val invalid = setOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        val starred = setOf("MVK_NO_WILDCARDS_ALLOWED")
        val a12 = linkedMapOf(
            "the local read of a wider category — the control" to invalid,
            "a starred read of a covering category" to starred,
            "a starred read of a wider category" to starred,
            "a starred read of the wider field itself" to starred,
            "a starred read of an undeclared category" to setOf("MVK_CATEGORY_UNKOWN"),
            "a climbing read of a covering category" to emptySet(),
            "a climbing read of a wider category" to invalid,
            "a climbing read of the wider field itself" to invalid,
        )
        assertEquals(a12.mapValues { (_, codes) -> codes to codes }, actual,
            "an enumeration computation's source, as (ours, A12)")
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

    private fun compare(at: Pair<String, String>, probe: String, model: String = policy): Comparison {
        val (group, errorLeaf) = at
        val draft = DraftRule(group, "ProbeRule", "../$errorLeaf", "FieldFilled($errorLeaf) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(model, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse(UNKEYED) }.toSet()
        }
        val ours = check(ModelSource(validator.inject(model, draft))).findings
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
        val IN_REVIEW = "/Policy/Claims/Review" to "Note"

        /** The kernel grid's carriers, each probed on a field its own gate refuses. */
        val PROBES = linkedMapOf(
            "arity" to Probe("Sum(%s) > 0", "Amount"),
            "field-operand kind" to Probe("FieldFilled(%s)", "Review"),
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
            "value-list condition" to Probe("AtLeastOneFieldValueIncludedInValueList(%s In \"a\")", "Amount"),
            "table enumeration operand" to Probe("ValueNotConsistent(%s To \"HIGH\")", "Amount"),
            "rounding digits" to Probe("RoundUpValue(%s, 15) > 0", "Amount"),
            "arithmetic under a pattern" to Probe("[%s] + 1 PatternMatched \"a+\"", "Memo"),
            "date construction slot" to Probe("Date(%s, \"02\", \"2020\") < Today", "Memo"),
            "date-range value list" to Probe("AtLeastOneDateRangeOverlaps(%s In Code)", "Memo"),
        )

        /** A read of the unstarred, unindexed `Riders` from the root, which A12 refuses as `MVK_NO_WILDCARD`. */
        const val UNADDRESSED = "[Riders/Memo] == \"a\""

        /** The rows each family is compared on: the named ones in the loop, then one per key defect. */
        val ROWS_PER_FAMILY = 21 + 9

        /**
         * The rows that still disagree, all from one family that is neither the key's nor the body's (CONSISTENCY-GAPS
         * DG96): it does not type a temporal comparison against a number constant, so its own local read is silent,
         * and so is every row where nothing else in the text draws a class; wherever the grid puts that comparison
         * beside another read, a class A12 raises later on the same text stands in its place — the filter walk's,
         * the index's, the unaddressed read's. A fix deletes its row, and a row that starts agreeing fails here
         * until it is deleted.
         */
        val KNOWN_DIVERGENCES = listOf(
            "comparison against a constant — local: the positive control A12 [MVK_INVALID_COMPARE_TO_DATE] ours []",
            "comparison against a constant — string key: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — enumeration constant key: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — field key: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — key on the iterated level: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — unkeyed in a filter body: WRONG-CLASS " +
                "[MVK_INVALID_ITERATION_IN_FILTER_CONDITION] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — unkeyed in the filtered level's own body: SILENT [] " +
                "A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — keyed in a filter body: WRONG-CLASS [MVK_SEMANTIC_INDEX_NOT_ALLOWED] " +
                "A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — keyed in the filtered level's own body: WRONG-CLASS " +
                "[MVK_SEMANTIC_INDEX_NOT_ALLOWED] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — the keyed read ahead of an unaddressed read: WRONG-CLASS " +
                "[MVK_NO_WILDCARD] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — an enumeration constant key ahead of an unaddressed read: WRONG-CLASS " +
                "[MVK_NO_WILDCARD] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — a climbing read: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — a climbing keyed read: SILENT [] A12 [MVK_INVALID_COMPARE_TO_DATE]",
            "comparison against a constant — a climb to the root and back into Claims: SILENT [] " +
                "A12 [MVK_INVALID_COMPARE_TO_DATE]",
        )

        /** The key defects, each as the operand form it is spelled with. */
        val DEFECTS = linkedMapOf(
            "no index field on the keyed group" to "Riders/%s For \"X\"",
            "a Number key over a String index" to "Claims/%s For Limit",
            "a number literal key" to "Claims/%s For 5",
            "an iterating key" to "Claims/%s For Notes/Text",
            "a key beside a star" to "Claims*/%s For \"X\"",
            "a constant the enumeration index refuses" to "Endorsements/%s For \"PENDING\"",
            "the enumeration's token in another case" to "Endorsements/%s For \"open\"",
            "a refused enumeration constant and a filter on the operand" to
                "Endorsements/%s For \"PENDING\" Having [Endorsements/ClaimRef] == \"a\"",
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
            .group("Review") { r -> r.stringField("Note") { } }
    }
}
