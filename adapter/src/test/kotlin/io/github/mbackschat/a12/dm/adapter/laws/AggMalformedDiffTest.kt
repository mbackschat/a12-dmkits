package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a **numeric combiner with a malformed element** (KERNEL-FINDINGS §3/§1). Surfaced by
 * `InterpreterKernelFuzzTest`: a malformed NUMBER reached through a starred aggregate (or an operand-list
 * `Min`/`Max`) made the interpreter **crash** (`dec("abc")`), where the kernel makes the whole combiner
 * **non-evaluable** — it emits the field's formal error and **suppresses** the referencing rule (probed: a
 * `Sum` with one `"abc"` element fires only `zahlHatUngueltigeZeichen`, not the rule). The interpreter now
 * parses defensively (malformed → `Val.Unknown`) and propagates that: any `Unknown` element → the combiner is
 * `Unknown` → the rule is suppressed. Verified for every starred aggregate + an operand-list `Min`, both with
 * a malformed element AND all-valid (the combiner still computes). Compared on the rule code + `zahl…` formals.
 */
class AggMalformedDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    // The numeric-combiner conditions are authored with the rulekit typed DSL ([m]) and rendered to the
    // canonical string fed to BOTH engines — no free-text condition literals. The MALFORMED operand values
    // (e.g. "abc") stay in the doc-building below; only the condition authoring changes.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private val prices = m.flatten("/Cart/Lines*/Price")

    /** Both engines' code lists for one starred-aggregate document, plus the kernel's own `code@row` pointers. */
    private data class Reading(val kernel: List<String>, val interpreter: List<String>, val kernelRows: List<String>)

    /** A starred-aggregate rule at /Cart over two Lines (Price `p1`, `p2`). */
    private fun starredCase(cond: String, code: String, p1: String, p2: String): Reading {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", code, "../Lines/Price", code, cond)
        val def = model(RuleDef("/Cart/$code", "/Cart", cond, code, "../Lines/Price"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .group("/Cart/Lines", 1, 2).field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
            .field(ITEM, "B", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
        val (kernel, interpreter) = codes(code, kModel, kDoc, def, iDoc)
        val relevant = { c: String -> c == code || c.startsWith("zahl") }
        // `errorField` is the kernel's raw pointer toString by RuntimeLaws' documented contract; take it from the
        // first '[' so the row identity does not pin the pointer IMPL's class name.
        val rows = RuntimeLaws.messages(kModel, kDoc).filter { relevant(it.code) }
            .map { "${it.code}@${it.errorField.substringAfter('[').removeSuffix("]")}" }.sorted()
        return Reading(kernel, interpreter, rows)
    }

    /** A starred-aggregate rule at /Cart over two Lines (Price `p1`, `p2`) — kernel and interpreter must agree. */
    private fun starred(cond: String, code: String, p1: String, p2: String) {
        val (kernel, interp) = starredCase(cond, code, p1, p2)
        assertEquals(kernel, interp, "$code  (kernel=$kernel interp=$interp)")
    }

    /** An operand-list `Min([Qty], [Price])` rule on one Line — kernel and interpreter must agree. */
    private fun operandMin(qty: String, price: String) {
        val cond = render(Dsl.min(m.num(QTY).asNumber(), m.num(PRICE).asNumber()).gt(0))
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "OM", "../Qty", "OM", cond)
        val def = model(RuleDef("/Cart/Lines/OM", "/Cart/Lines", cond, "OM", "../Qty"))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
            .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(QTY, qty, 1, 1, 1).field(PRICE, price, 1, 1, 1)
        assertAgree("OM", kModel, kDoc, def, iDoc)
    }

    /** The rule code plus the NUMBER formals, as the dynamic kernel and the interpreter each report them. */
    private fun codes(code: String, kModel: String, kDoc: RuntimeLaws.Doc, def: ModelDef, iDoc: Document):
        Pair<List<String>, List<String>> {
        val relevant = { c: String -> c == code || c.startsWith("zahl") }
        return RuntimeLaws.messages(kModel, kDoc).map { it.code }.filter(relevant).sorted() to
            DmInterpreter(def).validateFull(iDoc).map { it.code }.filter(relevant).sorted()
    }

    private fun assertAgree(code: String, kModel: String, kDoc: RuntimeLaws.Doc, def: ModelDef, iDoc: Document) {
        val (kernel, interp) = codes(code, kModel, kDoc, def, iDoc)
        assertEquals(kernel, interp, "$code  (kernel=$kernel interp=$interp)")
    }

    private fun model(rule: RuleDef) = ModelDef(
        fields, listOf(rule), repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM),
    )

    private fun doc(def: ModelDef) = def.newDocument()

    @Test fun sumSuppressedByMalformedElement() = starred(render(Dsl.sum(prices).gt(100)), "SM", "200", "abc")
    @Test fun sumComputesWhenAllValid() = starred(render(Dsl.sum(prices).gt(100)), "SM", "200", "50")
    @Test fun maxValueSuppressedByMalformedElement() = starred(render(Dsl.maxValue(prices).gt(100)), "MX", "200", "abc")
    @Test fun minValueSuppressedByMalformedElement() = starred(render(Dsl.minValue(prices).gt(0)), "MN", "200", " 5 ")
    @Test fun distinctSuppressedByMalformedElement() = starred(render(Dsl.numberOfDifferentValues(prices).gt(1)), "ND", "200", "abc")
    @Test fun minOperandSuppressedByMalformedOperand() = operandMin("abc", "50")
    @Test fun minOperandComputesWhenAllValid() = operandMin("10", "50")

    /**
     * The malformed cell's authored position does not change an all-rows aggregate's **VERDICT**: `Sum`, `MaxValue`,
     * `MinValue` and `NumberOfDifferentValues` stay suppressed with the formal error alone whether the bad cell is
     * head or tail. Scope is deliberately the verdict and nothing wider — the emitted formal error still names the
     * offending ROW, so validation *output* is not position-blind, and the `kernelRows` column below pins that.
     *
     * Two controls make the suppression rows non-vacuous. The **all-valid** column proves the rule code surfaces
     * through the very filter the suppressed rows are read through (without it, a lost channel would read as
     * suppression). The **row** column proves the head/tail swap was actually applied (without it, passing the same
     * document twice satisfies the equality — a mutant that survived until this column existed).
     *
     * This measures an outcome, not a mechanism: verdict identity is what BOTH an ordered early return and a
     * whole-selection survey predict, so it does not by itself establish either. The kernel's ordering account,
     * its pre-loop survey, and what actually separates these operators from `FirstFilledValue` are in
     * KERNEL-FINDINGS §3 with their source basis named.
     */
    @Test fun theMalformedCellsPositionDoesNotChangeAnAllRowsAggregateVerdict() {
        val rows = mutableListOf<String>()
        for ((code, cond) in listOf(
            "SM" to render(Dsl.sum(prices).gt(100)),
            "MX" to render(Dsl.maxValue(prices).gt(100)),
            "MN" to render(Dsl.minValue(prices).gt(0)),
            "ND" to render(Dsl.numberOfDifferentValues(prices).gt(1)),
        )) {
            val head = starredCase(cond, code, "abc", "200")
            val tail = starredCase(cond, code, "200", "abc")
            val valid = starredCase(cond, code, "200", "50")
            assertEquals(head.kernel, head.interpreter, "$code malformed head — kernel/interpreter")
            assertEquals(tail.kernel, tail.interpreter, "$code malformed tail — kernel/interpreter")
            assertEquals(valid.kernel, valid.interpreter, "$code all valid — kernel/interpreter")
            rows += "$code head=${head.kernel} tail=${tail.kernel} valid=${valid.kernel}"
            rows += "$code rows head=${head.kernelRows} tail=${tail.kernelRows}"
        }
        assertEquals(
            listOf(
                "SM head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[SM]",
                "SM rows head=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 1, 1]]" +
                    " tail=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 2, 1]]",
                "MX head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[MX]",
                "MX rows head=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 1, 1]]" +
                    " tail=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 2, 1]]",
                "MN head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[MN]",
                "MN rows head=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 1, 1]]" +
                    " tail=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 2, 1]]",
                "ND head=[zahlHatUngueltigeZeichen] tail=[zahlHatUngueltigeZeichen] valid=[ND]",
                "ND rows head=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 1, 1]]" +
                    " tail=[zahlHatUngueltigeZeichen@/Cart/Lines/Price, [1, 2, 1]]",
            ),
            rows,
            "position must not select the VERDICT, while the formal error still names the offending row",
        )
    }

    /**
     * Closes two gaps a cold review left open. (1) The positional cases above read the **dynamic-Groovy** leg only,
     * so the verdict was never tri-checked; the static-Java codegen strategy is compared here. (2) `SumOfProducts`
     * is measured, having twice proved wrong wherever it was folded into a list with the `Combiner`-based
     * aggregates — it reaches neither `combineFeldListe` nor the three-value pre-survey, so nothing about its
     * third-state behaviour may be inherited from them.
     *
     * Deliberately TWO operators and two documents each, not the full matrix: each case builds and compiles a
     * generated Java model, and this class shares `:adapter:test` with wall-clock perf floors that a per-case
     * codegen sweep has inflated before.
     */
    @Test fun theStaticJavaStrategyAgreesOnThePositionalVerdictIncludingSumOfProducts() {
        val qtys = m.flatten("/Cart/Lines*/Qty")
        val readings = mutableListOf<String>()
        for ((code, cond) in listOf(
            "SM" to render(Dsl.sum(prices).gt(100)),
            "SP" to render(Dsl.sumOfProducts(qtys, prices).gt(100)),
        )) {
            for ((where, p1, p2) in listOf(
                Triple("head", "abc", "200"), Triple("tail", "200", "abc"), Triple("valid", "200", "50"),
            )) {
                val kModel = RuntimeLaws.withRule(fuzz, "/Cart", code, "../Lines/Price", code, cond)
                val def = model(RuleDef("/Cart/$code", "/Cart", cond, code, "../Lines/Price"))
                val kDoc = RuntimeLaws.doc().group("/Cart", 1)
                    .group("/Cart/Lines", 1, 1).field(ITEM, "A", 1, 1, 1).field(QTY, "2", 1, 1, 1)
                    .field(PRICE, p1, 1, 1, 1)
                    .group("/Cart/Lines", 1, 2).field(ITEM, "B", 1, 2, 1).field(QTY, "3", 1, 2, 1)
                    .field(PRICE, p2, 1, 2, 1)
                val iDoc = doc(def).field(ITEM, "A", 1, 1, 1).field(QTY, "2", 1, 1, 1).field(PRICE, p1, 1, 1, 1)
                    .field(ITEM, "B", 1, 2, 1).field(QTY, "3", 1, 2, 1).field(PRICE, p2, 1, 2, 1)
                val relevant = { c: String -> c == code || c.startsWith("zahl") }
                val dynamic = RuntimeLaws.messages(kModel, kDoc).map { it.code }.filter(relevant).sorted()
                val java = RuntimeLaws.kernelJavaEngine(kModel)
                val staticJava = java.messages(java.instance(kDoc)).map { it.code }.filter(relevant).sorted()
                val interpreter = DmInterpreter(def).validateFull(iDoc).map { it.code }.filter(relevant).sorted()
                assertEquals(dynamic, staticJava, "$code/$where — dynamic-Groovy vs static-Java")
                assertEquals(dynamic, interpreter, "$code/$where — kernel vs interpreter")
                readings += "$code/$where => $dynamic"
            }
        }
        assertEquals(EXPECTED_TRI, readings, "the positional verdict as BOTH kernel strategies report it")
    }

    private companion object {
        val EXPECTED_TRI = listOf(
            "SM/head => [zahlHatUngueltigeZeichen]",
            "SM/tail => [zahlHatUngueltigeZeichen]",
            "SM/valid => [SM]",
            "SP/head => [zahlHatUngueltigeZeichen]",
            "SP/tail => [zahlHatUngueltigeZeichen]",
            "SP/valid => [SP]",
        )
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val PRICE = "/Cart/Lines/Price"
    }
}
