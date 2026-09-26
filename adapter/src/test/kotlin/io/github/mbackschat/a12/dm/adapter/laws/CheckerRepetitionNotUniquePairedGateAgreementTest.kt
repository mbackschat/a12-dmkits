package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `RepetitionNotUnique`'s gates two at a time, the rows of `validate.laws.RepetitionNotUniquePairedGateLawsTest`,
 * with A12's class written out and the portable checker required to name the same one. That class holds the
 * rivals each row rules out; this one holds the checker to the measured column.
 *
 * Own domain (insurance claims, and a fleet of vehicles as a repeatable root), the carrier of that laws class.
 */
class CheckerRepetitionNotUniquePairedGateAgreementTest {

    private val model = DocumentModelDraft.named("policy-uniqueness-pairs").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .enumField("Tier") { e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH") }
            .group("Insured") { i -> i.stringField("InsuredName") { } }
            .group("Claims") { c -> c.repeatable(20).indexField("ClaimRef")
                .stringField("ClaimRef") { }
                .numberField("Amount") { n -> n.fractionDigits(2) }
                .stringField("RawNote") { s -> s.lineBreaks().noValueValidation() }
                .group("Adjuster") { a -> a.stringField("AdjusterRef") { } }
                .group("Assessor") { a -> a.stringField("AssessorRef") { } } }
            .group("Payments") { g -> g.repeatable(20).indexField("ClaimRef")
                .stringField("ClaimRef") { }
                .stringField("PayRef") { } }
            .group("Batches") { b -> b.repeatable(4)
                .group("Items") { i -> i.repeatable(5).indexField("LineRef")
                    .stringField("LineRef") { }.stringField("ItemRef") { } }
                .group("Parts") { q -> q.repeatable(5).indexField("LineRef")
                    .stringField("LineRef") { }.stringField("PartRef") { } } }
            .group("Riders") { r -> r.repeatable(5).stringField("RiderRef") { } }
            .group("Vacant") { v -> v.repeatable(3) }
            .group("Dormant") { } }
        .rootGroup("Fleet") { f -> f.repeatable(50)
            .stringField("Plate") { }
            .stringField("Vin") { }
            .group("Registration") { r -> r.stringField("RegNo") { } }
            .group("Drivers") { d -> d.repeatable(3).stringField("License") { } } }
        .rootGroup("Archive") { a -> a.repeatable(5) }
        .rootGroup("Attic") { }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theCheckerNamesTheClassA12NamesOnEveryPairedRow() {
        val unread = "Insured/InsuredName For \"k\""
        val rows = mutableListOf<Row>()
        for (defect in listOf("Insured", "Claims/RawNote", "Tier -> Band", "Claims*/ClaimRef")) {
            rows += inPolicy("$defect, $unread", "MVK_NO_INDEX_FIELD")
            rows += inPolicy("$unread, $defect", "MVK_NO_INDEX_FIELD")
        }
        bothOrders("Tier -> Band", "Insured") { rows += inPolicy(it, "MVK_CATEGORY_NOT_ALLOWED") }
        bothOrders("Insured", "Claims*/ClaimRef") { rows += inPolicy(it, "MVK_NO_GROUPS_ALLOWED") }
        bothOrders("Claims*/ClaimRef", "Claims/Amount For \"C-1\"") { rows += inPolicy(it, "MVK_NO_WILDCARDS_ALLOWED") }
        bothOrders("Claims/Amount For \"C-1\"", "Claims/RawNote") { rows += inPolicy(it, "MVK_SEMANTIC_INDEX_NOT_ALLOWED") }
        bothOrders("Insured", "Claims/RawNote") { rows += inPolicy(it, "MVK_NO_GROUPS_ALLOWED") }
        bothOrders("ClaimRef", "/Policy/Payments/PayRef") { rows += inClaims(it, "MVK_NOT_IN_SAME_PATH") }
        rows += inClaims("/Policy/Payments/PayRef", "MVK_NOT_IN_RULEGROUP")
        rows += atFirstKey("/Policy", "Claims/Adjuster/AdjusterRef")
        rows += atFirstKey("/Policy", "Claims/Adjuster/AdjusterRef, Claims/Assessor/AssessorRef")
        rows += atFirstKey("/Policy", "Claims/Adjuster/AdjusterRef, Claims/Assessor/AssessorRef @From Claims")
        rows += atFirstKey("/Policy", "Batches/Items/ItemRef, Batches/Parts/PartRef", "MVK_NOT_IN_SAME_PATH")
        rows += atFirstKey("/Policy", "Batches/Items/ItemRef, Batches/Parts/PartRef @From Batches",
            "MVK_PARALLEL_ITERATION_NOT_ALLOWED")
        rows += atFirstKey("/Fleet", "Plate")
        rows += atFirstKey("/Fleet", "Plate, Vin")
        rows += atFirstKey("/Fleet", "Registration/RegNo", "MVK_REPEATABLE_GROUP_MISSING")
        rows += atFirstKey("/Fleet", "Plate, Registration/RegNo", "MVK_REPEATABLE_GROUP_MISSING")
        rows += atFirstKey("/Fleet", "Drivers/License")
        rows += inPolicy("Claims/RawNote @From Vacant", "MVK_NO_WILDCARD")
        rows += inPolicy("Claims/ClaimRef @From Vacant", "MVK_NO_WILDCARD")
        rows += inPolicy("Claims/ClaimRef, Claims/ClaimRef @From Vacant", "MVK_NO_WILDCARD")
        rows += inPolicy("$unread @From Vacant", "MVK_NO_INDEX_FIELD")
        rows += inPolicy("Claims/ClaimRef @From Dormant", "MVK_GROUP_IS_EMPTY")
        rows += inPolicy("Claims/RawNote @From Dormant", "MVK_GROUP_IS_EMPTY")
        rows += inPolicy("Claims/ClaimRef, Claims/ClaimRef @From Dormant", "MVK_GROUP_IS_EMPTY")
        rows += inPolicy("Claims/RawNote @From Insured For \"k\"", "MVK_NO_INDEX_FIELD")
        rows += inPolicy("Claims/ClaimRef @From Insured For \"k\"", "MVK_NO_INDEX_FIELD")
        rows += inPolicy("Claims/ClaimRef @From Claims For \"\"", "MVK_INDEX_VALUE_INVALID")
        rows += inPolicy("Claims/ClaimRef @From Claims For \"C-1\"", "MVK_SEMANTIC_INDEX_NOT_ALLOWED")
        // The scope's own gate answers ahead of containment, so a key outside a keyed scope is not the question.
        rows += inPolicy("Payments/PayRef @From Claims For \"C-1\"", "MVK_SEMANTIC_INDEX_NOT_ALLOWED")
        for (scope in listOf("Claims", "Riders", "Vacant")) {
            rows += unique("/Policy", "../Reference", "Reference @From $scope", setOf("MVK_NO_WILDCARD"))
        }
        for (keys in listOf("ClaimRef", "/Policy/Reference @From /Policy/Claims")) {
            rows += unique("/Policy/Claims", "../../Reference", keys, setOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP"))
        }
        rows += inPolicy("Insured, Insured", "MVK_DUPLICATE_PARAM1")
        bothOrders("/Policy", "Claims/ClaimRef") { rows += inPolicy(it, "MVK_DUPLICATE_PARAM2") }
        rows += inPolicy("Insured, Insured/InsuredName", "MVK_DUPLICATE_PARAM2")

        val wrong = rows.mapNotNull { row ->
            val observed = compare(row)
            val expected = row.a12 to (OURS[row.condition] ?: row.a12)
            if (observed == expected) null else "${row.group} ${row.condition}: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /**
     * The empty scope's order is not the construct's: an empty repeatable group named without its star, from a
     * rule that iterates nothing, draws its missing star on every carrier (`validate.laws.EmptyGroupOperandLawsTest`),
     * and a star written on an empty fixed group draws the star's class.
     */
    @Test
    fun aGroupsStarClassAnswersAheadOfItsEmptinessOnEveryCarrier() {
        val rows = listOf(
            "GroupFilled(/Policy/Vacant)", "NumberOfFilledGroups(/Policy/Vacant, /Policy/Insured) > 0",
            "AtLeastOneGroupFilled(/Policy/Vacant, /Policy/Insured)",
            "NumberOfFilledFields(/Policy/Vacant, /Policy/Insured) > 0",
        ).map { Row("/Policy", "../Reference", "FieldFilled(Reference) And $it", setOf("MVK_NO_WILDCARD")) } +
            Row("/Policy", "../Reference", "FieldFilled(Reference) And NumberOfFilledGroups(/Policy/Dormant*) > 0",
                setOf("MVK_INVALID_WILDCARD")) +
            Row("/Policy", "../Reference", "FieldFilled(Reference) And NumberOfFilledGroups(/Policy/Vacant*) > 0",
                setOf("MVK_GROUP_IS_EMPTY")) +
            Row("/Policy", "../Reference", "FieldFilled(Reference) And GroupFilled(/Archive)", setOf("MVK_NO_WILDCARD")) +
            Row("/Policy", "../Reference", "FieldFilled(Reference) And GroupFilled(/Attic*)",
                setOf("MVK_INVALID_WILDCARD")) +
            Row("/Policy", "../Reference", "FieldFilled(Reference) And NumberOfFilledGroups(/Archive*) > 0",
                setOf("MVK_ROOT_GROUP_IS_EMPTY"))
        val wrong = rows.mapNotNull { row ->
            val observed = compare(row)
            val expected = row.a12 to (OURS[row.condition] ?: row.a12)
            if (observed == expected) null else "${row.condition}: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /**
     * A group-valued index outside `RepetitionNotUnique`, the discriminator for the scope rows: whether the checker
     * misses the scope's index because of its position or because the index sits on a group.
     */
    @Test
    fun aGroupsIndexIsReadOutsideTheScopeToo() {
        val rows = listOf(
            Row("/Policy", "../Claims/ClaimRef", "FieldFilled(Claims/ClaimRef) And GroupFilled(/Policy/Insured For \"k\")",
                setOf("MVK_NO_INDEX_FIELD")),
            Row("/Policy", "../Claims/ClaimRef", "FieldFilled(Claims/ClaimRef) And GroupFilled(/Policy/Claims For \"\")",
                setOf("MVK_INDEX_VALUE_INVALID")),
        )
        val wrong = rows.mapNotNull { row ->
            val observed = compare(row)
            val expected = row.a12 to (OURS[row.condition] ?: row.a12)
            if (observed == expected) null else "${row.condition}: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    /** One rule: where it sits, where it reports, its condition, and A12's classes on it. */
    private data class Row(val group: String, val errorField: String, val condition: String, val a12: Set<String>)

    private fun unique(group: String, errorField: String, keys: String, a12: Set<String>) =
        Row(group, errorField, "RepetitionNotUnique($keys)", a12)

    private fun inPolicy(keys: String, a12: String) = unique("/Policy", "../Claims/ClaimRef", keys, setOf(a12))

    private fun inClaims(keys: String, a12: String) = unique("/Policy/Claims", "../ClaimRef", keys, setOf(a12))

    /** The construct at [group], reporting at its first key, so the condition references its error field. */
    private fun atFirstKey(group: String, keys: String, vararg a12: String) =
        unique(group, "../" + keys.split(",", " @")[0].trim(), keys, a12.toSet())

    private fun bothOrders(first: String, second: String, add: (String) -> Unit) {
        add("$first, $second")
        add("$second, $first")
    }

    private fun compare(row: Row): Pair<Set<String>, Set<String>> {
        val rule = DraftRule.builder().groupPath(row.group).name("UniqueClaims").errorEntityRelPath(row.errorField)
            .errorCondition(row.condition).errorCode("PROBE").severity("ERROR").build()
        val result = validator.check(model, rule)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(model, rule))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        /** The rows where the checker answers otherwise, keyed by condition, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
