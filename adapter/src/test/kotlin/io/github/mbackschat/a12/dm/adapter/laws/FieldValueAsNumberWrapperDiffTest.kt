package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-differential for direct numeric wrappers over stored- and category-selected
 * `FieldValueAsNumber` (SPEC-2026-07-23-01). It locks wrapper admission, authored/default scale,
 * missing-source fillability, exact present values, and formal-read poison without another evaluator path.
 */
class FieldValueAsNumberWrapperDiffTest {

    private val base = DocumentModelDraft.named("tariff-wrappers").locale("en_US")
        .rootGroup("Tariff") { group ->
            group.stringField("Anchor") { text -> text.maxLength(8) }
            group.enumField("Stored") { source -> source.value("-1.25").value(".5") }
            group.enumField("Band") { source ->
                source.value("NEGATIVE").value("LEADING").category("Numeric", "-1.25", ".5")
            }
            group.numberField("Down") { number -> number.fractionDigits(0) }
            group.numberField("Up") { number -> number.fractionDigits(1) }
            group.numberField("Accounting") { number -> number.fractionDigits(0) }
            group.numberField("Magnitude") { number -> number.fractionDigits(2) }
        }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val stored = Dsl.fieldValueAsNumber(bound.enumOf(STORED))
    private val category = Dsl.fieldValueAsNumber(bound.enumOf(BAND).category("Numeric"))
    private val rules = listOf(
        rule("DOWN", Dsl.roundDown(stored).eq(-2), STORED),
        rule("UP", Dsl.roundUp(category, 1).eq("-1.2")),
        rule("ACCOUNTING", Dsl.roundAccounting(stored).eq(-1), STORED),
        rule("ABS", Dsl.abs(category).eq("1.25")),
        rule("ROUND_EMPTY", Dsl.roundDown(category).eq(0)),
        rule("ABS_EMPTY_LE", Dsl.abs(category).le(0)),
        rule("ABS_EMPTY_GE", Dsl.abs(category).ge(0)),
    )
    private val computations = listOf(
        computation("DownProjection", DOWN, Dsl.roundDown(stored)),
        computation("UpProjection", UP, Dsl.roundUp(category, 1)),
        computation("AccountingProjection", ACCOUNTING, Dsl.roundAccounting(stored)),
        computation("MagnitudeProjection", MAGNITUDE, Dsl.abs(category)),
    )
    private val modelJson = computations.fold(
        rules.fold(base) { json, rule -> TypedRules.inject(json, rule) },
    ) { json, computation -> TypedRules.inject(json, computation) }
    private val model = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun rule(code: String, condition: Condition, errorPath: String = BAND): Rule =
        bound.rule(bound.group(ROOT)).name("${code}Rule").errorField(bound.field(errorPath))
            .`when`(condition).code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun computation(name: String, target: String, value: NumberExpr) =
        bound.computation(bound.num(target)).name(name).calc(value.expression())
            .message(Dsl.en("Projected wrapped value.")).build()

    @Test
    fun directWrappersAcceptStoredAndCategoryConversionsWithTheirExactScales() {
        assertSignals(
            "-1.25",
            "NEGATIVE",
            listOf(
                "ABS_EMPTY_GE|VALUE_ERROR",
                "ABS|VALUE_ERROR",
                "ACCOUNTING|VALUE_ERROR",
                "DOWN|VALUE_ERROR",
                "UP|VALUE_ERROR",
            ),
            "present selected tokens",
        )

        assertEquals(
            listOf("$ACCOUNTING|VALUE|-1", "$DOWN|VALUE|-2", "$MAGNITUDE|VALUE|1.25", "$UP|VALUE|-1.2"),
            compute("-1.25", "NEGATIVE", staleTargets = false),
        )
    }

    @Test
    fun missingConversionKeepsRoundingSymmetricAndAbsBecomesGrowOnly() {
        assertSignals(
            null,
            null,
            listOf("ABS_EMPTY_GE|VALUE_ERROR", "ABS_EMPTY_LE|OMISSION_ERROR", "ROUND_EMPTY|OMISSION_ERROR"),
            "missing source",
        )
    }

    @Test
    fun wrappersPreserveFormalReadPoisonInComputation() {
        assertEquals(
            listOf("$ACCOUNTING|CLEARED", "$DOWN|CLEARED", "$MAGNITUDE|CLEARED", "$UP|CLEARED"),
            compute("BOGUS", "BOGUS", staleTargets = true),
        )
    }

    private fun assertSignals(storedValue: String?, bandValue: String?, expected: List<String>, label: String) {
        val kernelDocument = kernelDocument(storedValue, bandValue, staleTargets = false)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument)).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1)
        if (storedValue == null && bandValue == null) interpreterDocument.field(ANCHOR, "active", 1, 1)
        if (storedValue != null) interpreterDocument.field(STORED, storedValue, 1, 1)
        if (bandValue != null) interpreterDocument.field(BAND, bandValue, 1, 1)
        val interpreter = DmInterpreter(model).validateFull(interpreterDocument).filter { it.code in CODES }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

        assertEquals(expected.sorted(), dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    private fun compute(storedValue: String, bandValue: String, staleTargets: Boolean): List<String> {
        val kernelDocument = kernelDocument(storedValue, bandValue, staleTargets)
        fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
            cleared -> "$path|CLEARED"
            errored -> "$path|ERRORED"
            else -> "$path|VALUE|$value"
        }
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = staticEngine.compute(staticEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(STORED, storedValue, 1, 1)
            .field(BAND, bandValue, 1, 1)
        if (staleTargets) {
            interpreterDocument.field(DOWN, "9", 1, 1).field(UP, "9.0", 1, 1)
                .field(ACCOUNTING, "9", 1, 1).field(MAGNITUDE, "9.00", 1, 1)
        }
        val interpreter = DmInterpreter(model).compute(interpreterDocument).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

        assertEquals(dynamic, static, "generated Java wrapper computations")
        assertEquals(dynamic, interpreter, "interpreter wrapper computations")
        return dynamic
    }

    private fun kernelDocument(storedValue: String?, bandValue: String?, staleTargets: Boolean): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1)
        if (storedValue == null && bandValue == null) document.field(ANCHOR, "active", 1, 1)
        if (storedValue != null) document.field(STORED, storedValue, 1, 1)
        if (bandValue != null) document.field(BAND, bandValue, 1, 1)
        if (staleTargets) {
            document.field(DOWN, "9", 1, 1).field(UP, "9.0", 1, 1)
                .field(ACCOUNTING, "9", 1, 1).field(MAGNITUDE, "9.00", 1, 1)
        }
        return document
    }

    private companion object {
        const val ROOT = "/Tariff"
        const val ANCHOR = "/Tariff/Anchor"
        const val STORED = "/Tariff/Stored"
        const val BAND = "/Tariff/Band"
        const val DOWN = "/Tariff/Down"
        const val UP = "/Tariff/Up"
        const val ACCOUNTING = "/Tariff/Accounting"
        const val MAGNITUDE = "/Tariff/Magnitude"
        val TARGETS = setOf(DOWN, UP, ACCOUNTING, MAGNITUDE)
        val CODES = setOf("DOWN", "UP", "ACCOUNTING", "ABS", "ROUND_EMPTY", "ABS_EMPTY_LE", "ABS_EMPTY_GE")
    }
}
