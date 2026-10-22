package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The portable checker over the operand kinds of `FieldValuesNotUnique`'s iteration guard (KERNEL-FINDINGS KF533,
 * `rulekit.validate.laws.UniquenessGuardSlotLawsTest`): selections, root, selected and claim groups, nested
 * repeatable children, on the Number, String and Date overloads.
 *
 * Where A12 answers `MVK_NEG_CONDITION_IN_ITERATION` the checker must answer it too, or decline the shape and name
 * the rule in a gap ([DECLINED]); where A12 admits, the checker must not refuse.
 *
 * Own domain (an insurance policy, its claims, riders and keyed covers).
 */
class CheckerUniquenessGuardAgreementTest {

    private val policy = DocumentModelDraft.named("policy-uniqueness-guard").locale("en_US")
        .rootGroup("Policy") { p -> p
            .numberField("Premium") { n -> n.fractionDigits(0) }
            .numberField("Deductible") { n -> n.fractionDigits(0) }
            .stringField("Holder") { }
            .stringField("Agent") { }
            .dateField("StartsOn") { d -> d.format("yyyy-MM-dd") }
            .dateField("RenewsOn") { d -> d.format("yyyy-MM-dd") }
            .group("Claims") { c -> c.repeatable(3)
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .stringField("Ref") { }
                .dateField("FiledOn") { d -> d.format("yyyy-MM-dd") }
                .group("Paid") { g -> g.numberField("PaidFirst") { n -> n.fractionDigits(0) }
                    .numberField("PaidSecond") { n -> n.fractionDigits(0) } }
                .group("Refs") { g -> g.stringField("RefFirst") { }.stringField("RefSecond") { } }
                .group("Dates") { g -> g.dateField("DateFirst") { d -> d.format("yyyy-MM-dd") }
                    .dateField("DateSecond") { d -> d.format("yyyy-MM-dd") } }
                .group("Repair") { g -> g.numberField("Labour") { n -> n.fractionDigits(0) }
                    .group("Parts") { r -> r.repeatable(2).numberField("Cost") { n -> n.fractionDigits(0) } } } }
            .group("Riders") { r -> r.repeatable(2)
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .stringField("Ref") { }
                .dateField("FiledOn") { d -> d.format("yyyy-MM-dd") } }
            .group("Endorsement") { e -> e.numberField("Amount") { n -> n.fractionDigits(0) } }
            .group("Covers") { c -> c.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .stringField("Ref") { }
                .dateField("FiledOn") { d -> d.format("yyyy-MM-dd") }
                .group("Limits") { g -> g.numberField("Lower") { n -> n.fractionDigits(0) }
                    .numberField("Upper") { n -> n.fractionDigits(0) } }
                .group("Labels") { g -> g.stringField("LabelFirst") { }.stringField("LabelSecond") { } }
                .group("Terms") { g -> g.dateField("TermFirst") { d -> d.format("yyyy-MM-dd") }
                    .dateField("TermSecond") { d -> d.format("yyyy-MM-dd") } }
                .group("Excess") { g -> g.numberField("Floor") { n -> n.fractionDigits(0) } }
                .group("Tiers") { g -> g.group("Bands") { b -> b.repeatable(2)
                    .numberField("Rate") { n -> n.fractionDigits(0) } } } }
            .group("Totals") { g -> g.numberField("Gross") { n -> n.fractionDigits(0) }
                .numberField("Net") { n -> n.fractionDigits(0) } }
            .group("Names") { g -> g.stringField("First") { }.stringField("Last") { } }
            .group("Signing") { g -> g.dateField("Issued") { d -> d.format("yyyy-MM-dd") }
                .dateField("Signed") { d -> d.format("yyyy-MM-dd") } }
            .group("Levy") { g -> g.numberField("Rate") { n -> n.fractionDigits(0) } }
            .group("Alias") { g -> g.stringField("Nick") { } }
            .group("Witness") { g -> g.dateField("SignedOn") { d -> d.format("yyyy-MM-dd") } }
            .group("Region") { g -> g.group("Branches") { b -> b.repeatable(2)
                .numberField("Score") { n -> n.fractionDigits(0) } } }
            .group("Office") { g -> g.numberField("Staff") { n -> n.fractionDigits(0) }
                .group("Desks") { d -> d.repeatable(2).numberField("Seats") { n -> n.fractionDigits(0) } } }
            .group("Archive") { g -> g.group("Shelf") { s -> s.numberField("Box") { n -> n.fractionDigits(0) } } } }
        .build().toJson()

    private val validator = RuleValidator()

    /** A12's rejection classes and ours, for one rule at `/Policy`. */
    private fun rule(errorField: String, condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../$errorField", condition, "PROBE", "ERROR")
        val a12 = validator.check(policy, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val report = check(ModelSource(validator.inject(policy, draft)))
        // The probe is the model's only rule, so a declaration-level gap naming the code is its own.
        val declined = report.gaps.any { it.at != null && NEGATIVE in it.reason }
        val ours = report.findings.filter { it.justifiesRejection }.map { it.code } + listOfNotNull(DECLINED.takeIf { declined })
        return a12.sorted() to ours.sorted()
    }

    private data class Kind(val field: String, val root1: String, val root2: String, val rowGroup: String,
        val rowGroupField: String, val rootGroup: String, val selectedGroup: String, val singleGroup: String)

    private val kinds = listOf(
        Kind("Amount", "Premium", "Deductible", "Paid", "PaidFirst", "Totals", "Limits", "Levy"),
        Kind("Ref", "Holder", "Agent", "Refs", "RefFirst", "Names", "Labels", "Alias"),
        Kind("FiledOn", "StartsOn", "RenewsOn", "Dates", "DateFirst", "Signing", "Terms", "Witness"))

    private fun rows(): Map<String, Pair<String, String>> = buildMap {
        for (k in kinds) {
            val own = "Claims/${k.field}"
            val ownGroupField = "Claims/${k.rowGroup}/${k.rowGroupField}"
            val sel = "Covers/${k.field} For \"c1\""
            put("$own, ${k.root1}", own to "$own, ${k.root1}")
            put("$own, ${k.root1}, ${k.root2}", own to "$own, ${k.root1}, ${k.root2}")
            put("$own, Riders*/${k.field}", own to "$own, Riders*/${k.field}")
            put("$own, $sel", own to "$own, $sel")
            put("$own, $sel, c2", own to "$own, $sel, Covers/${k.field} For \"c2\"")
            put("$own, $sel, ${k.root1}", own to "$own, $sel, ${k.root1}")
            put("$own, Claims/${k.rowGroup}", own to "$own, Claims/${k.rowGroup}")
            put("$own, ${k.rootGroup}", own to "$own, ${k.rootGroup}")
            put("Claims/${k.rowGroup} alone", ownGroupField to "Claims/${k.rowGroup}")
            put("$own, Covers/${k.selectedGroup} For c1", own to "$own, Covers/${k.selectedGroup} For \"c1\"")
            put("Claims/${k.rowGroup}, ${k.root1}", ownGroupField to "Claims/${k.rowGroup}, ${k.root1}")
            put("$own, ${k.singleGroup}", own to "$own, ${k.singleGroup}")
            put("$own, ${k.singleGroup}, ${k.root1}", own to "$own, ${k.singleGroup}, ${k.root1}")
        }
        for (operand in listOf("Region", "Office", "Region/Branches*", "Covers/Tiers For \"c1\"", "Claims/Repair",
            "Archive", "Covers/Excess For \"c1\"", "Endorsement*/Amount")) {
            put("Claims/Amount, $operand", "Claims/Amount" to "Claims/Amount, $operand")
        }
    }

    @Test
    fun theCheckerAgreesWithA12OrLeavesTheGuardToIt() {
        val verdicts = rows().mapValues { (_, row) -> rule(row.first, "FieldValuesNotUnique(${row.second})") }
        val wrong = verdicts.filter { (_, v) ->
            v.second - DECLINED != v.first && !(v.first == listOf(NEGATIVE) && v.second == listOf(DECLINED))
        }
            .map { (row, v) -> "$row: A12 ${v.first}, ours ${v.second}" }
        assertEquals(emptyList<String>(), wrong)
        // The guard is live on this carrier, so the silence above is not silence on nothing: seven refused shapes
        // per overload and the four nested ones.
        assertEquals(25, verdicts.count { it.value.first == listOf(NEGATIVE) })
        // Of those, the selections are the shapes the checker leaves to A12, each named at the rule.
        assertEquals(10, verdicts.count { it.value.first == listOf(NEGATIVE) && it.value.second == listOf(DECLINED) })
        assertEquals(listOf("MVK_INVALID_WILDCARD") to listOf("MVK_INVALID_WILDCARD"),
            verdicts.getValue("Claims/Amount, Endorsement*/Amount"))
    }

    private companion object {
        const val NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION"
        const val DECLINED = "(declined, named in a gap)"
    }
}
