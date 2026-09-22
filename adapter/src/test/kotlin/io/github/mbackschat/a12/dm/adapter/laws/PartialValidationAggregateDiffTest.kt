package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — starred aggregates over a repeatable group under partial validation are PER-OPERATOR (IF42).
 * `/Cart { Total, Lines[2] { Qty, Price } }`; the always-relevant `Total` is each rule's error field, so only the
 * aggregate's cell relevance varies. Row 1 `{Qty 182, Price 600}`, row 2 `{Qty empty, Price 600}`.
 *
 *  - **all-rows aggregates** (`Sum`/`Max` of `Lines` `Price`): the kernel evaluates them ONLY when the relevant
 *    set WILDCARDS the `Lines` level — concrete per-row enumeration, even of BOTH rows, leaves them UNKNOWN.
 *  - **`FirstFilled`** (of `Lines` `Qty`): order-aware — it fires whenever the FIRST FILLED cell (row 1) is
 *    relevant (wildcard, both-rows, OR row-1-only), and is UNKNOWN only when row 1 is not relevant (row-2-only).
 *
 * Each relevant set is compared kernel-vs-interpreter (not a hardcoded expectation). The broad randomized net is
 * `perf.PartialValidationFuzzDiffTest`; this pins the canonical cases. KERNEL-FINDINGS "Partial validation".
 */
class PartialValidationAggregateDiffTest {

    private val json = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Total", Consumer { })
                .group("Lines", Consumer<GroupBuilder> { it.repeatable(2).numberField("Qty", Consumer { }).numberField("Price", Consumer { }) })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    private fun cond(agg: NumberExpr) = RuntimeLaws.render(Dsl.fieldFilled(m.field(TOTAL)).and(agg.gt(50)))

    /** Same shape, but a threshold a DISTINCT COUNT can cross — `> 50` makes NumberOfDifferentValues vacuous. */
    private fun condLow(agg: NumberExpr) = RuntimeLaws.render(Dsl.fieldFilled(m.field(TOTAL)).and(agg.gt(0)))
    private val sumOp = cond(Dsl.sum(m.flatten("$LINES*/Price")))
    private val ffOp = cond(Dsl.firstFilledNumber(m.flatten("$LINES*/Qty")))
    private val kModel = RuntimeLaws.withRule(RuntimeLaws.withRule(
        json, CART, "SUM", "../Total", "SUM", sumOp), CART, "FF", "../Total", "FF", ffOp)
    private val def = ModelDef(
        listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
        listOf(RuleDef("$CART/SUM", CART, sumOp, "SUM", "../Total"), RuleDef("$CART/FF", CART, ffOp, "FF", "../Total")),
        repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 2),
    )

    private fun kDoc() = RuntimeLaws.doc().group(CART, 1).field(TOTAL, "1", 1, 1)
        .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
        .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)
    private fun iDoc() = def.newDocument().group(CART, 1).field(TOTAL, "1", 1, 1)
        .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
        .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)

    private fun agree(kRel: Set<Relevant>, iRel: Set<RelevantEntity>, label: String) {
        val k = RuntimeLaws.messagesPart(kModel, kDoc(), kRel).map { it.code }.filter { it in CODES }.toSortedSet()
        val i = DmInterpreter(def).validatePart(iDoc(), iRel).messages.map { it.code }.filter { it in CODES }.toSortedSet()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun wildcardLinesFiresBoth() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 0)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity.anyRep(LINES)), "wildcard Lines")

    @Test fun aWildcardFieldCanEstablishTheStarredExtent() {
        val kernelRelevant = setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(PRICE, 0, 0, 0))
        val interpreterRelevant = setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity.anyRep(PRICE))
        val kernelFires = RuntimeLaws.messagesPart(kModel, kDoc(), kernelRelevant).any { it.code == "SUM" }
        val interpreterFires = DmInterpreter(def).validatePart(iDoc(), interpreterRelevant).messages.any { it.code == "SUM" }

        assertTrue(kernelFires, "the kernel accepts a wildcard field as a complete starred extent")
        assertEquals(kernelFires, interpreterFires, "the field-wildcard positive control must agree")
    }

    @Test fun concreteGroupRowsThatSurviveReductionMakeTheStarredExtentUnknown() {
        val kernelRelevant = setOf(
            Relevant.of(TOTAL, 0, 0),
            Relevant.of(PRICE, 1, 0, 1),
            Relevant.of(LINES, 0, 1),
            Relevant.of(LINES, 0, 2),
        )
        val interpreterRelevant = setOf(
            RelevantEntity.anyRep(TOTAL),
            RelevantEntity(PRICE, listOf(1, 0, 1)),
            RelevantEntity(LINES, listOf(0, 1)),
            RelevantEntity(LINES, listOf(0, 2)),
        )
        val kernelFires = RuntimeLaws.messagesPart(kModel, kDoc(), kernelRelevant).any { it.code == "SUM" }
        val interpreterFires = DmInterpreter(def).validatePart(iDoc(), interpreterRelevant).messages.any { it.code == "SUM" }

        assertFalse(kernelFires, "the kernel does not merge physical-root and semantic-root selections into one extent")
        assertEquals(kernelFires, interpreterFires, "mixed root-coordinate modes must not be unioned")
    }

    @Test fun mixedRelevantIdentifiersAreReducedBeforeTheStarredLevelGate() {
        val aggregate = Dsl.numberOfDifferentValues(m.flatten("$LINES*/Price")).ge(2)
        val rule = m.rule(m.group(CART)).name("ND_COORDINATES").errorField(m.num(PRICE))
            .`when`(aggregate).code("ND_COORDINATES").severity(Severity.ERROR).message(Dsl.en("x")).build()
        val kernelModel = RuntimeLaws.withRule(
            json, CART, rule.name(), rule.errorEntityRelPath(), rule.code(), rule.render(),
        )
        val interpreterModel = ModelDef(
            listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
            listOf(RuleDef("$CART/${rule.name()}", CART, rule.render(), rule.code(), rule.errorEntityRelPath())),
            repeatableGroups = setOf(LINES),
            declaredReps = mapOf(LINES to 2),
        )
        val kernelDoc = RuntimeLaws.doc().group(CART, 1)
            .group(LINES, 1, 1).field(PRICE, "600", 1, 1, 1)
            .group(LINES, 1, 2).field(PRICE, "700", 1, 2, 1)
        val interpreterDoc = interpreterModel.newDocument().group(CART, 1)
            .group(LINES, 1, 1).field(PRICE, "600", 1, 1, 1)
            .group(LINES, 1, 2).field(PRICE, "700", 1, 2, 1)
        val field = Relevant.of(PRICE, 1, 0, 1)
        val interpreterField = RelevantEntity(PRICE, listOf(1, 0, 1))
        val semanticField = Relevant.of(PRICE, 0, 0, 0)
        val interpreterSemanticField = RelevantEntity.anyRep(PRICE)
        val selections = listOf(
            "field-only" to (setOf(field) to setOf(interpreterField)),
            "semantic-root groups" to (
                setOf(field, Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2)) to
                    setOf(
                        interpreterField,
                        RelevantEntity(LINES, listOf(0, 1)),
                        RelevantEntity(LINES, listOf(0, 2)),
                    )
                ),
            "semantic field with concrete groups" to (
                setOf(semanticField, Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2)) to
                    setOf(
                        interpreterSemanticField,
                        RelevantEntity(LINES, listOf(0, 1)),
                        RelevantEntity(LINES, listOf(0, 2)),
                    )
                ),
            "physical field with wildcard group" to (
                setOf(field, Relevant.of(LINES, 0, 0)) to
                    setOf(interpreterField, RelevantEntity.anyRep(LINES))
                ),
            "physical-root groups" to (
                setOf(field, Relevant.of(LINES, 1, 1), Relevant.of(LINES, 1, 2)) to
                    setOf(
                        interpreterField,
                        RelevantEntity(LINES, listOf(1, 1)),
                        RelevantEntity(LINES, listOf(1, 2)),
                    )
                ),
        )
        val readings = selections.map { (label, relevant) ->
            val (kernelRelevant, interpreterRelevant) = relevant
            val kernelFires = RuntimeLaws.messagesPart(kernelModel, kernelDoc, kernelRelevant)
                .any { it.code == rule.code() }
            val interpreterFires = DmInterpreter(interpreterModel).validatePart(interpreterDoc, interpreterRelevant)
                .messages.any { it.code == rule.code() }
            "$label kernel=$kernelFires interpreter=$interpreterFires"
        }

        assertEquals(
            listOf(
                "field-only kernel=true interpreter=true",
                "semantic-root groups kernel=false interpreter=false",
                "semantic field with concrete groups kernel=true interpreter=true",
                "physical field with wildcard group kernel=true interpreter=true",
                "physical-root groups kernel=false interpreter=false",
            ),
            readings,
            "semantic and physical coordinate modes are distinct partial-validation selections",
        )
    }

    @Test fun sumOfProductsAcceptsCompleteWildcardFieldExtents() {
        val rule = cond(Dsl.sumOfProducts(m.flatten("$LINES*/Qty"), m.flatten("$LINES*/Price")))
        val kernelModel = RuntimeLaws.withRule(json, CART, "SOP_FIELDS", "../Total", "SOP_FIELDS", rule)
        val interpreterModel = ModelDef(
            listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
            listOf(RuleDef("$CART/SOP_FIELDS", CART, rule, "SOP_FIELDS", "../Total")),
            repeatableGroups = setOf(LINES),
            declaredReps = mapOf(LINES to 2),
        )
        val kernelRelevant = setOf(
            Relevant.of(TOTAL, 0, 0),
            Relevant.of(QTY, 0, 0, 0),
            Relevant.of(PRICE, 0, 0, 0),
        )
        val interpreterRelevant = setOf(
            RelevantEntity.anyRep(TOTAL),
            RelevantEntity.anyRep(QTY),
            RelevantEntity.anyRep(PRICE),
        )
        val kernelFires = RuntimeLaws.messagesPart(kernelModel, kDoc(), kernelRelevant).any { it.code == "SOP_FIELDS" }
        val interpreterFires = DmInterpreter(interpreterModel).validatePart(iDoc(), interpreterRelevant)
            .messages.any { it.code == "SOP_FIELDS" }

        assertTrue(kernelFires, "SumOfProducts uses complete field extent rather than the combiner group gate")
        assertEquals(kernelFires, interpreterFires, "the SumOfProducts sibling must retain its weaker field-extent gate")
    }

    @Test fun concreteBothRowsOnlyFirstFilled() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1)), RelevantEntity(LINES, listOf(0, 2))),
            "concrete both rows (Sum/Max UNKNOWN, FirstFilled fires)")

    @Test fun row1OnlyFirstFilledFires() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1))), "row 1 only")

    @Test fun row2OnlySuppressesAll() =
        agree(setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 2)),
            setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 2))), "row 2 only (FirstFilled's first cell non-relevant)")

    /**
     * The DENOMINATOR of §12's "all-rows aggregates" claim. KERNEL-SEMANTICS names five operators as evaluating only
     * when the starred level is wildcarded — `Sum`, `MaxValue`, `MinValue`, `NumberOfDifferentValues`,
     * `SumOfProducts` — but the cases above exercise only `Sum`, so four were carried by the list rather than by a
     * measurement. `SumOfProducts` is the one with real reason to differ: it runs its own two-list loop with no
     * `Combiner`, so it does not share the pre-loop three-value survey the others reach.
     *
     * This pins what the KERNEL ITSELF reports per operator, not merely that the two engines agree — agreement
     * cannot distinguish a shared correct answer from a shared wrong one, and that is exactly how the retracted
     * wording survived.
     */
    @Test fun everyAllRowsAggregateNamedByTheRelevanceClaimIsMeasured() {
        val readings = mutableListOf<String>()
        val divergences = mutableListOf<String>()
        val diverged = mutableListOf<String>()
        for ((code, agg) in listOf<Pair<String, NumberExpr>>(
            "SUM" to Dsl.sum(m.flatten("$LINES*/Price")),
            "MXV" to Dsl.maxValue(m.flatten("$LINES*/Price")),
            "MNV" to Dsl.minValue(m.flatten("$LINES*/Price")),
            "NDV" to Dsl.numberOfDifferentValues(m.flatten("$LINES*/Price")),   // threshold below
            "SOP" to Dsl.sumOfProducts(m.flatten("$LINES*/Qty"), m.flatten("$LINES*/Price")),
            "FFV" to Dsl.firstFilledNumber(m.flatten("$LINES*/Qty")),
        )) {
            val rule = if (code == "NDV") condLow(agg) else cond(agg)
            val km = RuntimeLaws.withRule(json, CART, code, "../Total", code, rule)
            val d = ModelDef(
                listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
                listOf(RuleDef("$CART/$code", CART, rule, code, "../Total")),
                repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 2),
            )
            fun kd() = RuntimeLaws.doc().group(CART, 1).field(TOTAL, "1", 1, 1)
                .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
                .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)
            fun id() = d.newDocument().group(CART, 1).field(TOTAL, "1", 1, 1)
                .group(LINES, 1, 1).field(QTY, "182", 1, 1, 1).field(PRICE, "600", 1, 1, 1)
                .group(LINES, 1, 2).field(PRICE, "600", 1, 2, 1)
            // WILDCARD is the positive control: every operator must be able to fire, so a silent CONCRETE row below
            // is a relevance decision and not an operator that simply never fires on this document.
            val wildcard = setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 0))
            val iWildcard = setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity.anyRep(LINES))
            val concrete = setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2))
            val iConcrete = setOf(RelevantEntity.anyRep(TOTAL),
                RelevantEntity(LINES, listOf(0, 1)), RelevantEntity(LINES, listOf(0, 2)))
            fun read(what: String, kRel: Set<Relevant>, iRel: Set<RelevantEntity>): String {
                val k = RuntimeLaws.messagesPart(km, kd(), kRel).map { it.code }.filter { it == code }.sorted()
                val i = DmInterpreter(d).validatePart(id(), iRel).messages
                    .map { it.code }.filter { it == code }.sorted()
                divergences += "$code/$what kernel=${if (k.isEmpty()) "silent" else "fires"}" +
                    " interp=${if (i.isEmpty()) "silent" else "fires"}"
                if (k != i) diverged += "$code/$what"
                return if (k.isEmpty()) "silent" else "fires"
            }
            readings += "$code wildcard=${read("wildcard", wildcard, iWildcard)}" +
                " concrete=${read("concrete", concrete, iConcrete)}"
        }
        assertEquals(listOf<String>(), diverged, "engines diverge:\n" + divergences.joinToString("\n"))
        assertEquals(EXPECTED_RELEVANCE, readings, "per-operator relevance as the dynamic kernel reports it")
    }

    /**
     * The axis the per-operator sweep above does NOT vary: its `concrete` set enumerates BOTH declared rows, so
     * "concrete" there is also COMPLETE. `SumOfProducts` was exempted from the IF42 gate on that single reading,
     * and `perf.PartialValidationFuzzDiffTest` then found the kernel SILENT where the interpreter fires — always
     * on a set that enumerates only SOME rows. Enumeration COMPLETENESS is therefore its own axis, and this pins
     * each granularity against the kernel itself rather than against a generalization of one measurement.
     */
    @Test fun sumOfProductsSeparatesCompleteFromPartialRowEnumeration() {
        val rule = cond(Dsl.sumOfProducts(m.flatten("$LINES*/Qty"), m.flatten("$LINES*/Price")))
        val km = RuntimeLaws.withRule(json, CART, "SOP", "../Total", "SOP", rule)
        val d = ModelDef(
            listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
            listOf(RuleDef("$CART/SOP", CART, rule, "SOP", "../Total")),
            repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to 2),
        )
        val readings = mutableListOf<String>()
        val diverged = mutableListOf<String>()
        for ((label, sets) in listOf(
            "wildcard" to (setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 0)) to
                setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity.anyRep(LINES))),
            "both-rows" to (setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1), Relevant.of(LINES, 0, 2)) to
                setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1)), RelevantEntity(LINES, listOf(0, 2)))),
            "row-1-only" to (setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 1)) to
                setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 1)))),
            "row-2-only" to (setOf(Relevant.of(TOTAL, 0, 0), Relevant.of(LINES, 0, 2)) to
                setOf(RelevantEntity.anyRep(TOTAL), RelevantEntity(LINES, listOf(0, 2)))),
        )) {
            val (kRel, iRel) = sets
            val k = RuntimeLaws.messagesPart(km, kDoc(), kRel).map { it.code }.filter { it == "SOP" }.sorted()
            val i = DmInterpreter(d).validatePart(iDoc(), iRel).messages
                .map { it.code }.filter { it == "SOP" }.sorted()
            readings += "SOP $label kernel=${if (k.isEmpty()) "silent" else "fires"}" +
                " interp=${if (i.isEmpty()) "silent" else "fires"}"
            if (k != i) diverged += label
        }
        assertEquals(listOf<String>(), diverged, "SumOfProducts diverges by enumeration granularity:\n" +
            readings.joinToString("\n"))
        assertEquals(EXPECTED_SOP_GRANULARITY, readings, "what the dynamic kernel reports per granularity")
    }

    private companion object {
        const val CART = "/Cart"; const val TOTAL = "/Cart/Total"
        const val LINES = "/Cart/Lines"; const val QTY = "/Cart/Lines/Qty"; const val PRICE = "/Cart/Lines/Price"
        val CODES = setOf("SUM", "FF")
        val EXPECTED_SOP_GRANULARITY = listOf(
            "SOP wildcard kernel=fires interp=fires",
            "SOP both-rows kernel=fires interp=fires",
            "SOP row-1-only kernel=silent interp=silent",
            "SOP row-2-only kernel=silent interp=silent",
        )
        val EXPECTED_RELEVANCE = listOf(
            "SUM wildcard=fires concrete=silent",
            "MXV wildcard=fires concrete=silent",
            "MNV wildcard=fires concrete=silent",
            "NDV wildcard=fires concrete=silent",
            "SOP wildcard=fires concrete=fires",
            "FFV wildcard=fires concrete=fires",
        )
    }
}
