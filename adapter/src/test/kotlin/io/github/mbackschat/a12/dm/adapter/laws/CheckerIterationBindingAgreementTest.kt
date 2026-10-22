package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which references bind a rule's iteration, the checker against A12, over both grids of
 * `rulekit.validate.laws.IterationBindingLawsTest` (a fixed root reporting on a repeatable claim, every row also
 * as a computation precondition) and the repeatable-root rows of `…RootRepetitionIterationAgreementLawsTest`.
 *
 * Where A12 answers `MVK_NEG_CONDITION_IN_ITERATION` the checker must answer it too, or decline the shape and name
 * the rule in a gap ([DECLINED]); silence without that gap is a disagreement like any other.
 *
 * Own domain (an insurance policy's claims, a fleet of vehicles).
 */
class CheckerIterationBindingAgreementTest {

    private val policy = DocumentModelDraft.named("policy-iteration-binding").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Holder") { }
            .numberField("Premium") { n -> n.fractionDigits(0) }
            .group("Claims") { c -> c.repeatable(3)
                .group("Detail") { d -> d.numberField("Units") { n -> n.fractionDigits(0) } }
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .dateField("FiledOn") { f -> f.format("yyyy-MM-dd") }
                .stringField("Note") { }
                .numberField("Reserve") { n -> n.fractionDigits(2) }
                .dateRangeField("CoverSpan") { r -> r.format("yyyy-MM-dd").separator("/") }
                .group("Windows") { w -> w.dateRangeField("Window") { r -> r.format("yyyy-MM-dd").separator("/") } }
                .numberField("Score") { n -> n.fractionDigits(0) } } }
        .build().toJson()

    private val fleet = DocumentModelDraft.named("fleet").locale("en_US")
        .rootGroup("Vehicles") { v -> v.repeatable(50)
            .stringField("Plate") { }
            .numberField("Mileage") { n -> n.fractionDigits(0) } }
        .rootGroup("Header") { h -> h.stringField("Title") { } }
        .build().toJson()

    /** Claims keyed by code, and the key the policy selects. */
    private val register = DocumentModelDraft.named("claims-register-binding").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Key") { }
            .group("Claims") { c -> c.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .numberField("Reserve") { n -> n.fractionDigits(2) } } }
        .build().toJson()

    /** A fixed root, a fixed branch and its repeatable claims: which containing group names the error field. */
    private val region = DocumentModelDraft.named("region-claims").locale("en_US")
        .rootGroup("Region") { r -> r
            .group("Branch") { b -> b
                .group("Claims") { c -> c.repeatable(5)
                    .numberField("Amount") { n -> n.fractionDigits(0) }
                    .numberField("Reserve") { n -> n.fractionDigits(0) } } } }
        .build().toJson()

    /** A repeatable root whose vehicles carry repeatable trips. */
    private val convoy = DocumentModelDraft.named("convoy").locale("en_US")
        .rootGroup("Vehicles") { v -> v.repeatable(50)
            .numberField("Mileage") { n -> n.fractionDigits(0) }
            .group("Trips") { t -> t.repeatable(9)
                .numberField("Km") { n -> n.fractionDigits(0) }
                .numberField("Fuel") { n -> n.fractionDigits(0) } } }
        .build().toJson()

    private val validator = RuleValidator()

    /** A12's rejection classes and ours, for one rule. */
    private fun rule(model: String, group: String, errorField: String, condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule(group, "ProbeRule", "../$errorField", condition, "PROBE", "ERROR")
        return judge(validator.check(model, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }, validator.inject(model, draft))
    }

    private fun computation(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftComputation("/Policy", "ScoreProbe", "../Claims/Score", condition,
            listOf(DraftComputation.Alternative(null, "1")))
        return judge(validator.check(policy, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }, validator.inject(policy, draft))
    }

    private fun judge(a12: List<String>, injected: String): Pair<List<String>, List<String>> {
        val report = check(ModelSource(injected))
        // The probe is the model's only iterating rule, so a declaration-level gap naming the code is its own.
        val declined = report.gaps.any { it.at != null && NEGATIVE in it.reason }
        return a12.sorted() to (report.findings.filter { it.justifiesRejection }.map { it.code } +
            listOfNotNull(DECLINED.takeIf { declined })).sorted()
    }

    /** A12's answer and ours agree, or A12 refuses on the iteration guard where this checker declined the shape. */
    private fun agrees(a12: List<String>, ours: List<String>) =
        ours - DECLINED == a12 || (a12 == listOf(NEGATIVE) && ours == listOf(DECLINED))

    @Test
    fun theCheckerNamesA12sBindingClassOnEveryRow() {
        val rows = linkedMapOf<String, Pair<List<String>, List<String>>>()
        for ((errorField, list, own) in CALLERS) {
            for ((variant, condition) in listOf("plain" to list, "sibling" to "FieldFilled(Claims/Reserve) And $list",
                "own" to "FieldFilled($own) And $list")) {
                rows["$list, $variant"] = rule(policy, "/Policy", errorField, condition)
                rows["$list, $variant, computation"] = computation(condition)
            }
        }
        for ((errorField, list, own) in MARKED_ROOT) {
            rows["$list, sibling"] = rule(policy, "/Policy", errorField, "FieldFilled(Claims/Reserve) And $list")
            rows["$list, own"] = rule(policy, "/Policy", errorField, "FieldFilled($own) And $list")
        }
        for (operator in GUARDS) {
            for ((shape, errorField, operands, guarded) in SHAPES) {
                val call = "$operator($operands)"
                val condition = if (guarded) "FieldFilled(Claims/Detail/Units) And $call" else call
                rows["$operator $shape"] = rule(policy, "/Policy", errorField, condition)
                rows["$operator $shape, computation"] = computation(condition)
            }
        }
        for (condition in FLEET) rows[condition] = rule(fleet, "/Vehicles", "Plate", condition)
        // Only the root names the error field, beside a deeper per-row read: not this arm's level.
        rows["root only, a deeper per-row read"] =
            rule(policy, "/Policy", "Claims/Amount", "GroupFilled(/Policy) And FieldFilled(Claims/Reserve)")
        for (condition in KEYED) rows[condition] = rule(register, "/Policy", "Claims/Amount", condition)
        for ((name, at) in REGION) rows[name] = rule(region, at.first, at.second, at.third)
        // Only the repeatable root names the error field while BOTH levels iterate: the deeper one answers first.
        rows["root only, the root and a trip read per row"] = rule(convoy, "/Vehicles", "Trips/Km",
            "NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage) And FieldFilled(Trips/Fuel)")
        // A GROUP error target, named only through a star beside a per-row read.
        rows["a group error target, starred"] = rule(policy, "/Policy", "Claims",
            "FieldFilled(Claims/Reserve) And NumberOfFilledGroups(Claims*) > 0")

        val wrong = rows.filter { (_, verdicts) ->
            val (a12, ours) = verdicts
            !agrees(a12, ours)
        }.map { (row, v) -> "$row: A12 ${v.first}, ours ${v.second}" }
        assertEquals(emptyList<String>(), wrong)
        // A12's own answer on the rows that separate the accounts, so the agreement cannot follow a drift on both sides.
        assertEquals(
            linkedMapOf(
                "Sum(Claims*/Amount) > 0, plain" to emptyList(),
                "Sum(Claims*/Amount) > 0, sibling" to listOf(BINDS),
                "Sum(Claims*/Amount) > 0, sibling, computation" to emptyList(),
                "Sum(Claims*/Amount) > 0, own" to emptyList(),
                "AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows), plain" to listOf(BINDS),
                "NoFieldFilled rowPair" to listOf(NEGATIVE),
                "AtLeastOneFieldFilled marked" to listOf(NEGATIVE),
                "NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage)" to listOf(BINDS),
                "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Plate)" to emptyList(),
                "root only, a deeper per-row read" to listOf("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
                KEYED[0] to emptyList(),
                KEYED[1] to listOf(BINDS),
                KEYED[2] to emptyList(),
                "rule at the claims, naming the branch above it" to listOf(BINDS),
                "rule at the branch, naming the branch" to listOf(BINDS),
                "root only, the root and a trip read per row" to listOf("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
                "a group error target, starred" to emptyList(),
            ),
            PINNED.associateWith { rows.getValue(it).first },
        )
    }

    private data class Shape(val name: String, val errorField: String, val operands: String, val guarded: Boolean)

    private companion object {
        const val BINDS = "MVK_ERROR_FIELD_AND_ITERATION_INVALID"
        const val NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION"
        const val DECLINED = "(declined, named in a gap)"
        const val ROOT_MARKED = " Having (GroupFilled(\$/Policy) And CurrentRepetition(Claims) > 0)"
        const val MARKED = "Claims*/Amount Having (FieldFilled(\$Claims/Detail/Units) And CurrentRepetition(Claims) > 0)"

        val CALLERS = listOf(
            Triple("Claims/Amount", "Sum(Claims*/Amount) > 0", "Claims/Amount"),
            Triple("Claims/Amount", "NumberOfFilledFields(Claims*/Amount) > 0", "Claims/Amount"),
            Triple("Claims/Note", "NumberOfDifferentValues(Claims*/Note) > 0", "Claims/Note"),
            Triple("Claims/Note", "FirstFilledValue(Claims*/Note) == \"a\"", "Claims/Note"),
            Triple("Claims/FiledOn", "MinValue(Claims*/FiledOn) == \"01.01.2024\"", "Claims/FiledOn"),
            Triple("Claims/Note", "AtLeastOneFieldFilled(Claims*/Note)", "Claims/Note"),
            Triple("Claims/Note", "FieldValuesNotUnique(Claims*/Note)", "Claims/Note"),
            Triple("Claims/CoverSpan", "DateRangesOverlap(Claims*/CoverSpan)", "Claims/CoverSpan"),
            Triple("Claims/Detail/Units", "Sum(Claims*/Detail) > 0", "Claims/Detail/Units"),
            Triple("Claims/Windows/Window", "AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows)",
                "Claims/Windows/Window"),
        )
        val MARKED_ROOT = listOf(
            Triple("Claims/Amount", "Sum(Claims*/Amount$ROOT_MARKED) > 0", "Claims/Amount"),
            Triple("Claims/Note", "FirstFilledValue(Claims*/Note$ROOT_MARKED) == \"a\"", "Claims/Note"),
            Triple("Claims/FiledOn", "MinValue(Claims*/FiledOn$ROOT_MARKED) == \"01.01.2024\"", "Claims/FiledOn"),
            Triple("Claims/CoverSpan", "DateRangesOverlap(Claims*/CoverSpan$ROOT_MARKED)", "Claims/CoverSpan"),
        )
        val GUARDS = listOf("AllFieldsFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "MoreThanOneFieldFilled",
            "NotAllFieldsFilled", "NotExactlyOneFieldFilled", "FieldsNotCollectivelyFilled", "FieldValuesNotUnique")
        val SHAPES = listOf(
            Shape("rowPair", "Claims/Amount", "Claims/Amount, Claims/Reserve", false),
            Shape("mixedPair", "Claims/Amount", "Claims/Amount, Premium", false),
            Shape("starAndRow", "Claims/Reserve", "Claims*/Amount, Claims/Reserve", false),
            Shape("marked", "Claims/Detail/Units", MARKED, false),
            Shape("guard", "Claims/Detail/Units", MARKED, true),
            Shape("candidate", "Claims/Amount", MARKED.replace("\$Claims", "Claims"), false),
            Shape("plain", "Claims/Amount", "Claims*/Amount", false),
        )
        val FLEET = listOf(
            "NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage)",
            "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Mileage)",
            "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Plate)",
            "Sum(/Vehicles*/Mileage) > 0 And FieldFilled(Mileage)",
        )
        /** A keyed read of the error field records an index, not an iteration ([KERNEL-FINDINGS KF205]). */
        val KEYED = listOf(
            "[Claims/Amount For Key] > 0",
            "FieldFilled(Claims/Reserve) And [Claims/Amount For Key] > 0",
            "FieldFilled(Claims/Amount) And [Claims/Amount For Key] > 0",
        )
        /** `RootOnlyErrorTargetIterationLawsTest.onlyARootGroupReferenceLeavesTheErrorFieldUnpinned`'s rows. */
        val REGION = linkedMapOf(
            "rule at the root, naming the root" to
                Triple("/Region", "Branch/Claims/Amount", "GroupFilled(/Region) And FieldFilled(Branch/Claims/Reserve)"),
            "rule at the claims, naming the root" to
                Triple("/Region/Branch/Claims", "Amount", "GroupFilled(/Region) And [Reserve] > 0"),
            "rule at the claims, naming the branch above it" to
                Triple("/Region/Branch/Claims", "Amount", "GroupFilled(/Region/Branch) And [Reserve] > 0"),
            "rule at the branch, naming the branch" to
                Triple("/Region/Branch", "Claims/Amount", "GroupFilled(/Region/Branch) And FieldFilled(Claims/Reserve)"),
            "rule at the claims, naming the claims" to
                Triple("/Region/Branch/Claims", "Amount", "GroupFilled(/Region/Branch/Claims) And [Reserve] > 0"),
        )
        val PINNED = listOf(
            "Sum(Claims*/Amount) > 0, plain", "Sum(Claims*/Amount) > 0, sibling",
            "Sum(Claims*/Amount) > 0, sibling, computation", "Sum(Claims*/Amount) > 0, own",
            "AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows), plain",
            "NoFieldFilled rowPair", "AtLeastOneFieldFilled marked",
            "NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage)",
            "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Plate)",
            "root only, a deeper per-row read",
        ) + KEYED + listOf("rule at the claims, naming the branch above it", "rule at the branch, naming the branch",
            "root only, the root and a trip read per row", "a group error target, starred")
    }
}
