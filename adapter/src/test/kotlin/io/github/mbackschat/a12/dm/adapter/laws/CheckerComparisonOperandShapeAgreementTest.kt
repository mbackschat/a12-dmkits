package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which operand SHAPES an Enumeration's equality gate reads (a12-kernel-lean SPEC-2026-10-09-02). [KF407] settled
 * the gates on direct field reads; these rows put a brace or a selector on either side. Kernel 30.8.1 had two display
 * gates beside the value-domain one, which kernel 31.1.1 deleted (KF655), so every shape that once met one is now
 * admitted, and those rows stay as the lock that no remnant of either survives on any written arm. The value-domain
 * gate reads a CONSTANT, which `CheckOpUtils.isConstant` finds inside any brace.
 *
 * `FirstFilledValue` over textual fields is a String (`CheckEntityListenUtils.treatEnumAsString`), even over two
 * Enumerations, so it meets neither the display gate as an immediate read nor the domain gate as an Enumeration.
 *
 * Own domain (insurance policy tiers).
 */
class CheckerComparisonOperandShapeAgreementTest {

    private val policy = DocumentModelDraft.named("policy-comparison-shape").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Holder") { }
            .enumField("Plain") { e -> e.value("BASIC").value("PLUS") }
            .enumField("PlainTwin") { e -> e.value("BASIC").value("PLUS") }
            .enumField("Mirrored") { e -> e
                .value("BASIC") { l -> l.label("en_US", "BASIC") }
                .value("PLUS") { l -> l.label("en_US", "PLUS") } }
            .enumField("Labelled") { e -> e
                .value("BASIC") { l -> l.label("en_US", "Basic cover") }
                .value("PLUS") { l -> l.label("en_US", "Plus cover") } }
            .enumField("LabelledTwin") { e -> e
                .value("BASIC") { l -> l.label("en_US", "Basic cover") }
                .value("PLUS") { l -> l.label("en_US", "Plus cover") } }
            .enumField("TokenConflict") { e -> e
                .value("BASIC") { l -> l.label("en_US", "Budget cover") }
                .value("PLUS") { l -> l.label("en_US", "Plus cover") } }
            .enumField("Categorized") { e -> e
                .value("BASIC") { l -> l.label("en_US", "Budget cover") }
                .value("PLUS") { l -> l.label("en_US", "Plus cover") }
                .category("Band", "LOW", "HIGH") } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun noShapeMeetsADisplayGate() {
        assertAgreement(linkedMapOf(
            "a text left of a labelled one" to ("[Holder] == [Labelled]" to ADMITTED),
            "a text right of a labelled one" to ("[Labelled] == [Holder]" to ADMITTED),
            "a braced text, left" to ("{[Holder]} == [Labelled]" to ADMITTED),
            "a braced text, right" to ("[Labelled] == {[Holder]}" to ADMITTED),
            "a braced labelled one, right" to ("[Holder] == {[Labelled]}" to ADMITTED),
            "a braced labelled one, left" to ("{[Labelled]} == [Holder]" to ADMITTED),
            "identity labels" to ("[Holder] == [Mirrored]" to ADMITTED),
            "braced identity labels" to ("[Holder] == {[Mirrored]}" to ADMITTED),
            "a category access" to ("[Holder] == [Categorized -> Band]" to ADMITTED),
            "conflicting labels, the labelled one braced" to ("[TokenConflict] == {[Labelled]}" to ADMITTED),
            "conflicting labels, the other braced" to ("{[TokenConflict]} == [Labelled]" to ADMITTED),
            "matching labels, one braced" to ("[LabelledTwin] == {[Labelled]}" to ADMITTED),
            "labels beside none, one braced" to ("[Plain] == {[Labelled]}" to ADMITTED),
            "conflicting labels under a category" to ("[Labelled] == [Categorized -> Band]" to ADMITTED),
        ))
    }

    @Test
    fun theDomainGateFindsAConstantInsideABrace() {
        assertAgreement(linkedMapOf(
            "a braced stored value" to ("{\"BASIC\"} == [Labelled]" to ADMITTED),
            "a braced unknown value, left" to ("{\"missing\"} == [Labelled]" to NOT_A_VALUE),
            "a braced unknown value, right" to ("[Labelled] == {\"missing\"}" to NOT_A_VALUE),
            "a twice-braced unknown value" to ("[Labelled] == {{\"missing\"}}" to NOT_A_VALUE),
        ))
    }

    @Test
    fun aFirstFilledValueOverTextIsAString() {
        assertAgreement(linkedMapOf(
            "two Enumerations against an unknown value" to ("FirstFilledValue(Plain, PlainTwin) == \"missing\"" to ADMITTED),
            "an Enumeration and identity labels against an unknown value" to
                ("FirstFilledValue(Plain, Mirrored) == \"missing\"" to ADMITTED),
            "text and a labelled one against an unknown value" to
                ("FirstFilledValue(Holder, Labelled) == \"missing\"" to ADMITTED),
            "a labelled one against it" to ("[Labelled] == FirstFilledValue(Holder, Plain)" to ADMITTED),
        ))
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../Holder", "FieldFilled(Holder) And $probe", "PROBE", "ERROR")
        val a12 = validator.check(policy, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        }
        val ours = rejectingCodes(validator.inject(policy, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val NOT_A_VALUE = setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
