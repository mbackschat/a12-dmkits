package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-differential for the selected-token runtime and empty polarity of `FieldValueAsNumber`
 * (SPEC-2026-07-22-13). The stored enum tokens are deliberately non-numeric; only category `Numeric`
 * supplies `.5`, `-1.25`, the 15-digit boundary, and Arabic-Indic decimal digits. This distinguishes
 * category projection from accidentally converting the stored token and catches an ASCII-only JS parser.
 */
class FieldValueAsNumberDomainDiffTest {

    private val base = DocumentModelDraft.named("tariff-domain").locale("en_US")
        .rootGroup("Tariff") { group ->
            group.enumField("Source") { source ->
                source.value("LEADING").value("NEGATIVE").value("LIMIT").value("UNICODE")
                    .category("Numeric", ".5", "-1.25", "123456789012345", "١٢.٥")
            }
            group.numberField("Expected") { number -> number.fractionDigits(2) }
            group.numberField("Projected") { number -> number.fractionDigits(2) }
        }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val source = bound.enumOf(SOURCE)
    private val selected = Dsl.fieldValueAsNumber(source.category("Numeric"))
    private val rules = listOf(
        rule("EQ", selected.eq(bound.num(EXPECTED).asNumber())),
        rule("LE", selected.le(0)),
        rule("GE", selected.ge(0)),
    )
    private val computation = bound.computation(bound.num(PROJECTED)).name("Projection")
        .calc(selected.expression()).message(Dsl.en("Projected numeric token." )).build()
    private val modelJson = TypedRules.inject(rules.fold(base) { json, rule -> TypedRules.inject(json, rule) }, computation)
    private val model = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun rule(code: String, condition: Condition): Rule =
        bound.rule(bound.group(ROOT)).name("${code}Rule").errorField(source.field()).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun agree(sourceValue: String?, expectedValue: String?, expected: List<String>, label: String) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        val interpreterDocument = model.newDocument().group(ROOT, 1)
        if (sourceValue != null) {
            kernelDocument.field(SOURCE, sourceValue, 1, 1)
            interpreterDocument.field(SOURCE, sourceValue, 1, 1)
        }
        if (expectedValue != null) {
            kernelDocument.field(EXPECTED, expectedValue, 1, 1)
            interpreterDocument.field(EXPECTED, expectedValue, 1, 1)
        }

        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument)).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val interpreter = DmInterpreter(model).validateFull(interpreterDocument).filter { it.code in CODES }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

        assertEquals(expected.sorted(), dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    @Test
    fun selectedCategoryTokenUsesTheFullDecimalGrammar() {
        agree("LEADING", "0.50", listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), "leading point")
        agree("NEGATIVE", "-1.25", listOf("EQ|VALUE_ERROR", "LE|VALUE_ERROR"), "leading minus")
        agree("LIMIT", "123456789012345", listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), "15-digit boundary")
        agree("UNICODE", "12.50", listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), "Arabic-Indic digits")
    }

    @Test
    fun missingSourceIsZeroAndFillableInBothDirections() {
        agree(
            null,
            "0",
            listOf("EQ|OMISSION_ERROR", "GE|OMISSION_ERROR", "LE|OMISSION_ERROR"),
            "empty selected source is symmetric numeric zero",
        )
    }

    @Test
    fun formallyInvalidSourceRemainsUnavailable() {
        agree("BOGUS", "0", emptyList(), "out-of-enum source suppresses every coercion rule")
    }

    @Test
    fun computationConvertsTheSelectedTokenAndPoisonsAFormalRead() {
        assertEquals("VALUE|12.5", compute("UNICODE"), "category token, not stored enum token, is converted")
        assertEquals("CLEARED", compute("BOGUS"), "a formally-invalid selected source poisons the computation")
    }

    private fun compute(sourceValue: String): String {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(SOURCE, sourceValue, 1, 1)
            .field(PROJECTED, "9.00", 1, 1)
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(SOURCE, sourceValue, 1, 1)
            .field(PROJECTED, "9.00", 1, 1)
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).single { it.path() == PROJECTED }.let {
            if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}"
        }
        val static = staticEngine.compute(staticEngine.instance(kernelDocument)).single { it.path() == PROJECTED }.let {
            if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}"
        }
        val interpreter = DmInterpreter(model).compute(interpreterDocument).single { it.path == PROJECTED }.let {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
        assertEquals(dynamic, static, "$sourceValue — generated Java")
        assertEquals(dynamic, interpreter, "$sourceValue — interpreter")
        return dynamic
    }

    private companion object {
        const val ROOT = "/Tariff"
        const val SOURCE = "/Tariff/Source"
        const val EXPECTED = "/Tariff/Expected"
        const val PROJECTED = "/Tariff/Projected"
        val CODES = setOf("EQ", "LE", "GE")
    }
}
