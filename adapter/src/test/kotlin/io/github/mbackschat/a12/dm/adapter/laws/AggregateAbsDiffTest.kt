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

/** Tri-engine composition lock for SPEC-2026-07-23-03. */
class AggregateAbsDiffTest {

    private val base = DocumentModelDraft.named("invoice-aggregate-absolute-value").locale("en_US")
        .rootGroup("Invoice") { invoice ->
            invoice.stringField("Anchor") { }
            invoice.numberField("SumMagnitude") { number -> number.fractionDigits(2, 2) }
            invoice.numberField("MinMagnitude") { number -> number.fractionDigits(2, 2) }
            invoice.numberField("MaxMagnitude") { number -> number.fractionDigits(2, 2) }
            invoice.numberField("DistinctMagnitude") { number -> number.fractionDigits(0) }
            invoice.numberField("Followup") { number -> number.fractionDigits(2, 2) }
            invoice.group("Lines") { lines ->
                lines.repeatable(2)
                lines.numberField("Amount") { number -> number.fractionDigits(2) }
            }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val amounts = bound.flatten(AMOUNT_STAR)
    private val sum = Dsl.sum(amounts)
    private val min = Dsl.minValue(amounts)
    private val max = Dsl.maxValue(amounts)
    private val distinct = Dsl.numberOfDifferentValues(amounts)
    private val rules = listOf(
        rule("SUM_FOLD", Dsl.abs(sum).eq("2.25")),
        rule("MIN_VALUE", Dsl.abs(min).eq("3.25")),
        rule("MAX_VALUE", Dsl.abs(max).eq("1.00")),
        rule("DISTINCT", Dsl.abs(distinct).eq(2)),
        rule("SUM_BASE_GE", sum.ge(0)),
        rule("SUM_ABS_GE", Dsl.abs(sum).ge(0)),
        rule("SUM_ABS_LE", Dsl.abs(sum).le(0)),
        rule("MIN_ABS_GE", Dsl.abs(min).ge(0)),
        rule("MIN_ABS_LE", Dsl.abs(min).le(0)),
        rule("MAX_ABS_GE", Dsl.abs(max).ge(0)),
        rule("MAX_ABS_LE", Dsl.abs(max).le(0)),
        rule("DISTINCT_BASE_GE", distinct.ge(0)),
        rule("DISTINCT_ABS_GE", Dsl.abs(distinct).ge(0)),
        rule("DISTINCT_ABS_LE", Dsl.abs(distinct).le(0)),
    )
    private val computations = listOf(
        computation("SumMagnitudeProjection", SUM_MAGNITUDE, Dsl.abs(sum)),
        computation("MinMagnitudeProjection", MIN_MAGNITUDE, Dsl.abs(min)),
        computation("MaxMagnitudeProjection", MAX_MAGNITUDE, Dsl.abs(max)),
        computation("DistinctMagnitudeProjection", DISTINCT_MAGNITUDE, Dsl.abs(distinct)),
        computation("FollowupProjection", FOLLOWUP, bound.num(SUM_MAGNITUDE).asNumber().plus(1)),
    )
    private val modelJson = computations.fold(
        rules.fold(base) { json, rule -> TypedRules.inject(json, rule) },
    ) { json, computation -> TypedRules.inject(json, computation) }
    private val model = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun rule(code: String, condition: Condition): Rule =
        bound.rule(bound.group(ROOT)).name("${code}Rule").errorField(bound.field(AMOUNT)).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun computation(name: String, target: String, value: NumberExpr) =
        bound.computation(bound.num(target)).name(name).calc(value.expression())
            .message(Dsl.en("Absolute aggregate value.")).build()

    @Test
    fun aggregateFoldsBeforeAbsAndRetainsDerivedScale() {
        assertSignals(
            listOf(1 to "-3.25", 2 to "1.00"),
            listOf("DISTINCT", "DISTINCT_ABS_GE", "DISTINCT_BASE_GE", "MAX_ABS_GE", "MAX_VALUE", "MIN_ABS_GE", "MIN_VALUE", "SUM_ABS_GE", "SUM_FOLD"),
            "negative fold",
        )
        assertEquals(
            listOf(
                "$DISTINCT_MAGNITUDE|VALUE|2",
                "$FOLLOWUP|VALUE|3.25",
                "$MAX_MAGNITUDE|VALUE|1.00",
                "$MIN_MAGNITUDE|VALUE|3.25",
                "$SUM_MAGNITUDE|VALUE|2.25",
            ),
            compute(listOf(1 to "-3.25", 2 to "1.00"), staleTargets = false),
        )
    }

    @Test
    fun absTransformsSymmetricAggregateZeroWhileDistinctZeroWasAlreadyGrowOnly() {
        assertSignals(
            emptyList(),
            listOf(
                "DISTINCT_ABS_GE",
                "DISTINCT_ABS_LE:OMISSION",
                "DISTINCT_BASE_GE",
                "MAX_ABS_GE",
                "MAX_ABS_LE:OMISSION",
                "MIN_ABS_GE",
                "MIN_ABS_LE:OMISSION",
                "SUM_ABS_GE",
                "SUM_ABS_LE:OMISSION",
                "SUM_BASE_GE:OMISSION",
            ),
            "all-empty aggregate identities",
        )
    }

    @Test
    fun formalInvalidityPoisonsEveryWrappedAggregateAndItsDependent() {
        assertEquals(
            listOf(
                "$DISTINCT_MAGNITUDE|CLEARED",
                "$FOLLOWUP|CLEARED",
                "$MAX_MAGNITUDE|CLEARED",
                "$MIN_MAGNITUDE|CLEARED",
                "$SUM_MAGNITUDE|CLEARED",
            ),
            compute(listOf(1 to "invalid"), staleTargets = true),
        )
    }

    private fun assertSignals(rows: List<Pair<Int, String>>, expected: List<String>, label: String) {
        val kernelDocument = kernelDocument(rows, staleTargets = false)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in CODES }
            .map { "${it.code()}${if (it.type().name == "OMISSION_ERROR") ":OMISSION" else ""}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument)).filter { it.code() in CODES }
            .map { "${it.code()}${if (it.type().name == "OMISSION_ERROR") ":OMISSION" else ""}" }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        rows.forEach { (row, value) ->
            interpreterDocument.group(LINES, 1, row).field(AMOUNT, value, 1, row, 1)
        }
        val interpreter = DmInterpreter(model).validateFull(interpreterDocument).filter { it.code in CODES }
            .map { "${it.code}${if (it.type == MsgType.OMISSION) ":OMISSION" else ""}" }.sorted()

        assertEquals(expected.sorted(), dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    private fun compute(rows: List<Pair<Int, String>>, staleTargets: Boolean): List<String> {
        val kernelDocument = kernelDocument(rows, staleTargets)
        fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
            cleared -> "$path|CLEARED"
            errored -> "$path|ERRORED"
            else -> "$path|VALUE|$value"
        }
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = staticEngine.compute(staticEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        rows.forEach { (row, value) ->
            interpreterDocument.group(LINES, 1, row).field(AMOUNT, value, 1, row, 1)
        }
        if (staleTargets) TARGETS.forEach { interpreterDocument.field(it, "9", 1, 1) }
        val interpreter = DmInterpreter(model).compute(interpreterDocument).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

        assertEquals(dynamic, static, "generated Java Abs-aggregate computations")
        assertEquals(dynamic, interpreter, "interpreter Abs-aggregate computations")
        return dynamic
    }

    private fun kernelDocument(rows: List<Pair<Int, String>>, staleTargets: Boolean): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        rows.forEach { (row, value) -> document.group(LINES, 1, row).field(AMOUNT, value, 1, row, 1) }
        if (staleTargets) TARGETS.forEach { document.field(it, "9", 1, 1) }
        return document
    }

    private companion object {
        const val ROOT = "/Invoice"
        const val ANCHOR = "/Invoice/Anchor"
        const val LINES = "/Invoice/Lines"
        const val AMOUNT = "/Invoice/Lines/Amount"
        const val AMOUNT_STAR = "/Invoice/Lines*/Amount"
        const val SUM_MAGNITUDE = "/Invoice/SumMagnitude"
        const val MIN_MAGNITUDE = "/Invoice/MinMagnitude"
        const val MAX_MAGNITUDE = "/Invoice/MaxMagnitude"
        const val DISTINCT_MAGNITUDE = "/Invoice/DistinctMagnitude"
        const val FOLLOWUP = "/Invoice/Followup"
        val TARGETS = setOf(SUM_MAGNITUDE, MIN_MAGNITUDE, MAX_MAGNITUDE, DISTINCT_MAGNITUDE, FOLLOWUP)
        val CODES = setOf(
            "SUM_FOLD", "MIN_VALUE", "MAX_VALUE", "DISTINCT", "SUM_BASE_GE", "SUM_ABS_GE", "SUM_ABS_LE",
            "MIN_ABS_GE", "MIN_ABS_LE", "MAX_ABS_GE", "MAX_ABS_LE", "DISTINCT_BASE_GE", "DISTINCT_ABS_GE",
            "DISTINCT_ABS_LE",
        )
    }
}
