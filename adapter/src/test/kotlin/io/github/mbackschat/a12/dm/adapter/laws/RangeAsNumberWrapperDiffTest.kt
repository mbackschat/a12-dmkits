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

/** Tri-engine composition lock for SPEC-2026-07-23-02. */
class RangeAsNumberWrapperDiffTest {

    private val base = DocumentModelDraft.named("parcel-range-wrappers").locale("en_US")
        .rootGroup("Parcel") { group ->
            group.stringField("Anchor") { }
            group.stringField("Code") { text -> text.maxLength(4) }
            group.numberField("Down") { number -> number.fractionDigits(0) }
            group.numberField("Up") { number -> number.fractionDigits(2) }
            group.numberField("Accounting") { number -> number.fractionDigits(0) }
            group.numberField("Magnitude") { number -> number.fractionDigits(0) }
            group.numberField("Followup") { number -> number.fractionDigits(0) }
        }
        .build().toJson()
    private val bound = BoundModel.ofJson(base)
    private val selected = Dsl.rangeAsNumber(bound.field(CODE), 1, 3)
    private val rules = listOf(
        rule("DOWN", Dsl.roundDown(selected).eq(125)),
        rule("UP", Dsl.roundUp(selected, 2).eq("125.00")),
        rule("ACCOUNTING", Dsl.roundAccounting(selected).eq(125)),
        rule("ABS", Dsl.abs(selected).eq(125)),
        rule("ROUND_GE", Dsl.roundDown(selected).ge(0)),
        rule("ROUND_LE", Dsl.roundDown(selected).le(0)),
        rule("ABS_GE", Dsl.abs(selected).ge(0)),
        rule("ABS_LE", Dsl.abs(selected).le(0)),
    )
    private val computations = listOf(
        computation("DownProjection", DOWN, Dsl.roundDown(selected)),
        computation("UpProjection", UP, Dsl.roundUp(selected, 2)),
        computation("AccountingProjection", ACCOUNTING, Dsl.roundAccounting(selected)),
        computation("MagnitudeProjection", MAGNITUDE, Dsl.abs(selected)),
        computation("FollowupProjection", FOLLOWUP, bound.num(DOWN).asNumber().plus(1)),
    )
    private val modelJson = computations.fold(
        rules.fold(base) { json, rule -> TypedRules.inject(json, rule) },
    ) { json, computation -> TypedRules.inject(json, computation) }
    private val model = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private fun rule(code: String, condition: Condition): Rule =
        bound.rule(bound.group(ROOT)).name("${code}Rule").errorField(bound.field(CODE)).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun computation(name: String, target: String, value: NumberExpr) =
        bound.computation(bound.num(target)).name(name).calc(value.expression())
            .message(Dsl.en("Projected range value.")).build()

    @Test
    fun wrappersAcceptTheSelectedRangeAndAuthoredScale() {
        assertSignals(
            "125X",
            listOf("ABS|VALUE_ERROR", "ABS_GE|VALUE_ERROR", "ACCOUNTING|VALUE_ERROR", "DOWN|VALUE_ERROR", "ROUND_GE|VALUE_ERROR", "UP|VALUE_ERROR"),
            "numeric selection",
        )
        assertEquals(
            listOf(
                "$ACCOUNTING|VALUE|125",
                "$DOWN|VALUE|125",
                "$FOLLOWUP|VALUE|126",
                "$MAGNITUDE|VALUE|125",
                "$UP|VALUE|125",
            ),
            compute("125X", staleTargets = false),
        )
    }

    @Test
    fun missingIsGrowOnlyWhileAFilledFallbackZeroIsFixed() {
        assertSignals(
            null,
            listOf("ABS_GE|VALUE_ERROR", "ABS_LE|OMISSION_ERROR", "ROUND_GE|VALUE_ERROR", "ROUND_LE|OMISSION_ERROR"),
            "missing source",
        )
        assertSignals(
            "AB",
            listOf("ABS_GE|VALUE_ERROR", "ABS_LE|VALUE_ERROR", "ROUND_GE|VALUE_ERROR", "ROUND_LE|VALUE_ERROR"),
            "filled nonnumeric fallback zero",
        )
    }

    @Test
    fun formalInvalidityPoisonsTheWrappedTargetAndItsDependent() {
        assertEquals(
            listOf(
                "$ACCOUNTING|CLEARED",
                "$DOWN|CLEARED",
                "$FOLLOWUP|CLEARED",
                "$MAGNITUDE|CLEARED",
                "$UP|CLEARED",
            ),
            compute("TOO-LONG", staleTargets = true),
        )
    }

    private fun assertSignals(codeValue: String?, expected: List<String>, label: String) {
        val kernelDocument = kernelDocument(codeValue, staleTargets = false)
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument)).filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        if (codeValue != null) interpreterDocument.field(CODE, codeValue, 1, 1)
        val interpreter = DmInterpreter(model).validateFull(interpreterDocument).filter { it.code in CODES }
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

        assertEquals(expected.sorted(), dynamic, "$label — normative dynamic-Groovy")
        assertEquals(dynamic, static, "$label — generated Java")
        assertEquals(dynamic, interpreter, "$label — interpreter")
    }

    private fun compute(codeValue: String, staleTargets: Boolean): List<String> {
        val kernelDocument = kernelDocument(codeValue, staleTargets)
        fun outcome(path: String, value: String?, cleared: Boolean, errored: Boolean): String = when {
            cleared -> "$path|CLEARED"
            errored -> "$path|ERRORED"
            else -> "$path|VALUE|$value"
        }
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val static = staticEngine.compute(staticEngine.instance(kernelDocument)).filter { it.path() in TARGETS }
            .map { outcome(it.path(), it.value().orElse(null), it.cleared(), it.errored()) }.sorted()
        val interpreterDocument = model.newDocument().group(ROOT, 1).field(CODE, codeValue, 1, 1)
        if (staleTargets) TARGETS.forEach { interpreterDocument.field(it, "9", 1, 1) }
        val interpreter = DmInterpreter(model).compute(interpreterDocument).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

        assertEquals(dynamic, static, "generated Java RangeAsNumber wrapper computations")
        assertEquals(dynamic, interpreter, "interpreter RangeAsNumber wrapper computations")
        return dynamic
    }

    private fun kernelDocument(codeValue: String?, staleTargets: Boolean): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        if (codeValue != null) document.field(CODE, codeValue, 1, 1)
        if (staleTargets) TARGETS.forEach { document.field(it, "9", 1, 1) }
        return document
    }

    private companion object {
        const val ROOT = "/Parcel"
        const val ANCHOR = "/Parcel/Anchor"
        const val CODE = "/Parcel/Code"
        const val DOWN = "/Parcel/Down"
        const val UP = "/Parcel/Up"
        const val ACCOUNTING = "/Parcel/Accounting"
        const val MAGNITUDE = "/Parcel/Magnitude"
        const val FOLLOWUP = "/Parcel/Followup"
        val TARGETS = setOf(DOWN, UP, ACCOUNTING, MAGNITUDE, FOLLOWUP)
        val CODES = setOf("DOWN", "UP", "ACCOUNTING", "ABS", "ROUND_GE", "ROUND_LE", "ABS_GE", "ABS_LE")
    }
}
