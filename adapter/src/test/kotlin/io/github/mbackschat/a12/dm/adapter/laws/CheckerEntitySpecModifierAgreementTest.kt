package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An entity spec takes its modifiers in one order, each at most once — `-> Category`, then `For key`, then
 * `Having filter` (`entitySpec` in the kernel's `GrammarOnly_ConditionParser.g4`) — and each modifier keeps the
 * gates it has without the others (KERNEL-FINDINGS KF568; a12-kernel-lean's semantic-index captures of
 * 2026-10-14 reported the keyed category rows). Every row is pinned for A12 and compared against the portable
 * checker, which had read a category after the key and refused A12's own order as an unaddressed read.
 *
 * The filter rows are the third gate of `CheckEntitySpecImpl.checkFilterCondition`: a starred operand whose own
 * string key sits above its star is refused for the key before the body is walked, so the body is not a
 * variable. Raw condition text is the subject here, since the refused orders are what the typed surface cannot
 * spell.
 *
 * Own domain (an order's lines and packages).
 */
class CheckerEntitySpecModifierAgreementTest {

    private val order = DocumentModelDraft.named("order-modifiers").locale("en_US")
        .rootGroup("Order") { o -> o
            .numberField("Total") { it.fractionDigits(0) }
            .stringField("Pick") { }
            .enumField("Status") { e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low") }
            .group("Lines") { l -> l.repeatable(5).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) }
                .enumField("Grade") { e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low") } }
            .group("Packages") { p -> p.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .group("Parcels") { c -> c.repeatable(5)
                    .numberField("Weight") { it.fractionDigits(0) }
                    .enumField("Kind") { e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low") } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theModifiersOfAnEntitySpecFollowTheGrammarsOrder() = assertAgreement(linkedMapOf(
        "category, then key" to ("[Lines/Grade -> Tier For \"a\"] == \"High\"" to admitted),
        "key, then category on a string key" to ("[Lines/Grade For \"a\" -> Tier] == \"High\"" to unexpected),
        "a second key" to ("[Lines/Grade -> Tier For \"a\" For \"b\"] == \"High\"" to unexpected),
        "a category after the filter" to
            ("FirstFilledValue(Lines*/Grade Having [Lines/Qty] > 1 -> Tier, Status -> Tier) == \"High\"" to unexpected),
        "category, then filter" to
            ("FirstFilledValue(Lines*/Grade -> Tier Having [Lines/Qty] > 1, Status -> Tier) == \"High\"" to admitted),
    ))

    @Test
    fun aKeyedCategoryKeepsTheCategorysGates() = assertAgreement(linkedMapOf(
        "in a field-fill quantifier" to
            ("NotAllFieldsFilled(Lines/Grade -> Tier For \"a\")" to setOf("MVK_CATEGORY_NOT_ALLOWED")),
        "on a group" to
            ("FirstFilledValue(Lines -> Tier For \"a\", Status -> Tier) == \"High\"" to setOf("MVK_NO_GROUPS_ALLOWED")),
        "on a field, in FirstFilledValue — the control" to
            ("FirstFilledValue(Lines/Grade -> Tier For \"a\", Status -> Tier) == \"High\"" to admitted),
    ))

    @Test
    fun aFilteredOperandTakesNoKeyAboveItsStar() = assertAgreement(linkedMapOf(
        "a body on the filtered path" to ("Sum(Packages/Parcels*/Weight For \"p\" Having " +
            "[Packages/Parcels/Weight] > 1) > 0" to notAllowed),
        "a body reading the root only" to
            ("Sum(Packages/Parcels*/Weight For \"p\" Having [Total] > 1) > 0" to notAllowed),
        "a body iterating another repetition" to
            ("Sum(Packages/Parcels*/Weight For \"p\" Having [Lines/Qty] > 1) > 0" to notAllowed),
        "a category beside the key" to ("FirstFilledValue(Packages/Parcels*/Kind -> Tier For \"p\" Having " +
            "[Packages/Parcels/Weight] > 1, Status -> Tier) == \"High\"" to notAllowed),
        "no filter — the control" to ("Sum(Packages/Parcels*/Weight For \"p\") > 0" to admitted),
        "the star on the keyed level, raised at the key" to
            ("Sum(Lines*/Qty For \"a\" Having [Lines/Qty] > 1) > 0" to setOf("MVK_SEMANTIC_INDEX_AND_WILDCARD")),
        "a group, refused as `Having` is read" to ("NumberOfFilledGroups(Packages/Parcels* For \"p\" Having " +
            "[Packages/Parcels/Weight] > 1) > 0" to setOf("MVK_NO_GROUPS_ALLOWED")),
        "a bad name in the body, raised while it is read" to
            ("Sum(Packages/Parcels*/Weight For \"p\" Having [NoSuch] > 1) > 0" to setOf("MVK_INVALID_ENTITY")),
    ))

    @Test
    fun aStringKeyedBracketMeetsTheReadsGates() = assertAgreement(linkedMapOf(
        "a star below the key" to
            ("[Packages/Parcels*/Weight For \"p\"] > 1" to setOf("MVK_NO_WILDCARDS_ALLOWED")),
        "a category beside it" to
            ("[Packages/Parcels*/Kind -> Tier For \"p\"] == \"High\"" to setOf("MVK_NO_WILDCARDS_ALLOWED")),
        "a group" to ("[Lines For \"a\"] == \"x\"" to setOf("MVK_NO_GROUPS_ALLOWED")),
        "a field — the control" to ("[Lines/Qty For \"a\"] > 1" to admitted),
    ))

    /**
     * A correlated key never meets its uncorrelated twin: refused at a plain locus, and inside a filter refused as
     * every key is there, the literal key being the control that separates the two accounts.
     */
    @Test
    fun aCorrelatedKeyIsRefusedWhereverAKeyedCategoryStands() = assertAgreement(linkedMapOf(
        "at a plain locus" to ("[Lines/Grade -> Tier For \$Pick] == \"High\"" to outerIteration),
        "as a value-list member beside its twin" to ("AtLeastOneFieldValueIncludedInValueList(" +
            "Lines/Grade -> Tier For \$Pick, Lines/Grade -> Tier For Pick In \"High\")" to outerIteration),
        "inside a filter" to ("NumberOfFilledFields(Lines*/Sku Having [Lines/Sku] == [\$Pick] And " +
            "[Lines/Grade -> Tier For \$Pick] == \"High\") > 0" to notAllowed),
        "a literal key inside a filter — the control" to ("NumberOfFilledFields(Lines*/Sku Having " +
            "[Lines/Sku] == [\$Pick] And [Lines/Grade -> Tier For \"a\"] == \"High\") > 0" to notAllowed),
    ))

    private val admitted = emptySet<String>()
    private val outerIteration = setOf("MVK_INVALID_OUTER_ITERATION")
    private val unexpected = setOf("MVK_UNEXPECTED_TOKEN")
    private val notAllowed = setOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED")

    private fun assertAgreement(rows: Map<String, Pair<String, Set<String>>>) {
        val observed = rows.mapValues { (_, row) -> verdicts("FieldFilled(Total) And ${row.first}") }
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, observed, "(A12, ours)")
    }

    private fun verdicts(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Order", "ProbeRule", "../Total", condition, "PROBE", "ERROR")
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }
}
