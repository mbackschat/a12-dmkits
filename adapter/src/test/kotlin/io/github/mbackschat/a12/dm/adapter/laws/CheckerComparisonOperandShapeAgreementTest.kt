package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which operand SHAPES an Enumeration's equality gates read (a12-kernel-lean SPEC-2026-10-09-02). [KF407] settled
 * the gates on direct field reads; these rows put a brace or a selector on either side. In Kernel 30.8.1
 * `CheckVergleichsBedingungImpl` the three gates read three different things:
 * - the display gate against a TEXT reads how the text side is WRITTEN (`isFeldWertOp`: an immediate field read)
 *   and only the enumeration's TYPE, so a braced text partner passes and a braced enumeration does not;
 * - the display gate between two enumerations reads both types and nothing written;
 * - the value-domain gate reads a CONSTANT, which `CheckOpUtils.isConstant` finds inside any brace.
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
    fun theTextGateReadsTheWrittenTextSideAndTheEnumerationsType() {
        assertAgreement(linkedMapOf(
            "a text left of a labelled one — control" to ("[Holder] == [Labelled]" to TO_STRING),
            "a text right of a labelled one — control" to ("[Labelled] == [Holder]" to TO_STRING),
            "a braced text, left" to ("{[Holder]} == [Labelled]" to ADMITTED),
            "a braced text, right" to ("[Labelled] == {[Holder]}" to ADMITTED),
            "a braced labelled one, right" to ("[Holder] == {[Labelled]}" to TO_STRING),
            "a braced labelled one, left" to ("{[Labelled]} == [Holder]" to TO_STRING),
            "identity labels" to ("[Holder] == [Mirrored]" to ADMITTED),
            "braced identity labels" to ("[Holder] == {[Mirrored]}" to ADMITTED),
            "a category access" to ("[Holder] == [Categorized -> Band]" to ADMITTED),
        ))
    }

    @Test
    fun theEnumerationGateReadsBothTypesWhateverIsWritten() {
        assertAgreement(linkedMapOf(
            "conflicting labels, the labelled one braced" to ("[TokenConflict] == {[Labelled]}" to TO_ENUM),
            "conflicting labels, the other braced" to ("{[TokenConflict]} == [Labelled]" to TO_ENUM),
            "matching labels, one braced" to ("[LabelledTwin] == {[Labelled]}" to ADMITTED),
            "labels beside none, one braced" to ("[Plain] == {[Labelled]}" to TO_STRING),
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
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val TO_STRING = setOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")
        val TO_ENUM = setOf("MVK_INVALID_COMPARE_ENUM_TO_ENUM")
        val NOT_A_VALUE = setOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
