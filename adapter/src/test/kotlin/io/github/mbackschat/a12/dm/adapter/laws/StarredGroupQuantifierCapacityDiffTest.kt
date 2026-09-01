package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the filled-group COUNT and the two starred threshold QUANTIFIERS read the **in-capacity**
 * extent (`SPEC-2026-08-31-17`).
 *
 * **This file carried the opposite conclusion for one commit, and it was wrong.** It instantiated `Lines[3]` in
 * a capacity-2 group, saw `AtLeastOneGroupFilled` fire beside the kernel's own `zuGrosseZeile`, and concluded
 * that an over-limit row is tallied. a12-kernel-lean named the hole: `zuGrosseZeile` proves the GROUP is over
 * capacity, not that rows 1 and 2 are absent, so the firing was equally consistent with the kernel having
 * materialized the prefix.
 *
 * **Their ladder settles it, against the earlier reading.** A `NumberOfFilledGroups(Lines*)` equality ladder on
 * the same document answers **2** — `createGroupInstance(path, [1, 3])` materialized `Lines[1]` and `Lines[2]`,
 * the count sees exactly those two in-capacity rows and NOT the over-limit third, and the quantifier fired
 * because of them. A boolean quantifier could never have shown this; the ladder is what makes the tally visible,
 * which is the transferable half — a silent operator needs a quantity beside it, not another silence.
 *
 * **The third route does not need a sparse positional document.** The repeatable parent remains dense, while a
 * nonrepeatable child can exist only below one chosen parent row. One full-validation document carries two
 * symmetric child groups: one filled only below in-capacity row 2, the other filled only below over-limit row 3.
 * Both kernel strategies fire `AtLeastOneGroupFilled` for the first and `NoGroupFilled` for the second. Partial
 * validation independently separates row 2 from row 3 by relevance. The interpreter already matched both
 * observations, so reconciliation requires a law and corrected documentation rather than a runtime change.
 */
class StarredGroupQuantifierCapacityDiffTest {

    /**
     * **The prefix is materialized, so this document holds three instantiated rows and not one** — and the
     * count tallies two of them. That is the whole correction: the quantifier's firing here is explained by
     * rows 1 and 2, and the over-limit row is shown to contribute to the count not at all.
     */
    @Test
    fun instantiatingAnOverLimitRowMaterializesThePrefixAndTheCountExcludesTheOverLimitRow() {
        assertEquals(
            setOf(AT_LEAST_ONE, OVER_REPETITION, COUNT_IS_2),
            allKernelCodes(row = 3),
            "the count reads 2 — Lines[1] and Lines[2] exist and Lines[3] is not tallied",
        )
    }

    /** The in-capacity row alone tallies one, which is the ladder's positive control. */
    @Test
    fun anInCapacityRowTalliesOne() {
        assertEquals(
            setOf(AT_LEAST_ONE, COUNT_IS_1),
            allKernelCodes(row = 1),
            "one instantiated in-capacity row — the ladder reads 1",
        )
    }

    /**
     * The empty control: with no row instantiated the quantifier pair flips and the ladder reads zero. Without
     * it, every row above would be consistent with a quantifier that never answers false.
     */
    @Test
    fun withNoRowInstantiatedThePairFlipsAndTheLadderReadsZero() {
        assertEquals(setOf(NONE_FILLED, COUNT_IS_0), kernelCodesWithNoRow(), "zero instantiated rows")
    }

    /**
     * An instantiated row is a filled group whatever its cells hold — the half of a12-kernel-lean's
     * `SPEC-2026-08-31-17` that does hold here, and the reason the rows above turn on coordinates and not
     * content.
     */
    @Test
    fun anInstantiatedRowCountsWhateverItsCellsHold() {
        assertEquals(
            allKernelCodes(row = 1),
            allKernelCodes(row = 1, sku = "A-1"),
            "an empty in-capacity row and a filled one tally alike",
        )
    }

    /** Partial validation confirms the same capacity boundary when exactly one parent row is relevant. */
    @Test
    fun partialValidationExcludesTheOverLimitRowFromTheThresholdQuantifiers() {
        assertEquals(emptySet<String>(), partialKernelFired(coveredRow = null), "no group row is relevant")
        assertEquals(setOf(AT_LEAST_ONE), partialKernelFired(coveredRow = 2), "in-capacity control")
        assertEquals(
            emptySet<String>(),
            partialKernelFired(coveredRow = 3),
            "an over-limit row does not enter the quantifier's relevant extent",
        )
    }

    /** Both engines preserve the kernel's capacity boundary under the discriminating partial coverage. */
    @Test
    fun theInterpreterExcludesTheOverLimitRowWhenItIsTheOnlyRelevantGroupRow() {
        assertEquals(
            partialKernelFired(coveredRow = 3),
            partialInterpreterFired(coveredRow = 3),
            "over-limit-only coverage — kernel vs interpreter",
        )
    }

    /**
     * **THE FULL-VALIDATION DISCRIMINATOR.** A positional document must materialize parent rows 1 and 2 before
     * over-limit row 3, but it need not materialize the SAME descendant below them. Two symmetric nonrepeatable
     * children therefore separate the threshold pair in one document: `InCapacityMarker` is filled only below
     * row 2 and `OverCapacityMarker` only below row 3. The first fires `AtLeastOneGroupFilled`; the second fires
     * `NoGroupFilled`. If the threshold quantifiers read every instantiated parent row, those two answers swap
     * to the same positive result. The symmetric carriers differ only by the parent repetition holding their
     * filled witness.
     */
    @Test
    fun aDescendantPresentOnlyBelowTheOverLimitParentIsOutsideBothThresholdQuantifiers() {
        val expected = setOf(IN_CAP_PRESENT, OVER_CAP_ABSENT)
        assertEquals(expected, descendantKernelFired(), "full validation — normative kernel")
        assertEquals(expected, descendantInterpreterFired(), "full validation — interpreter")
    }

    /** Both engines agree, so the extent rule is shared rather than a kernel behaviour we merely tolerate. */
    @Test
    fun theInterpreterAgreesWithTheKernel() {
        listOf("over-limit row 3" to 3, "in-capacity row 1" to 1).forEach { (name, row) ->
            assertEquals(kernelFired(row = row), interpreterFired(row = row), "$name — kernel vs interpreter")
        }
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun kernelFired(row: Int, sku: String? = null): Set<String> =
        kernelCodes(row, sku).filterTo(sortedSetOf()) { it in CODES }

    /** Unfiltered, so the ladder rung and the over-repetition report are visible beside the quantifiers. */
    private fun allKernelCodes(row: Int, sku: String? = null): Set<String> = kernelCodes(row, sku).toSortedSet()

    private fun kernelCodes(row: Int, sku: String?): Set<String> {
        return RuntimeLaws.firedCodes(model, kernelDocument(row, sku)).toSortedSet()
    }

    private fun kernelCodesWithNoRow(): Set<String> {
        val doc = RuntimeLaws.doc()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        return RuntimeLaws.firedCodes(model, doc).toSortedSet()
    }

    /** The same document on the interpreter. Built separately because the two engines take different document
     *  types; the coordinates are the parameter, so the two builders cannot drift on what they describe. */
    private fun interpreterFired(row: Int, sku: String? = null): Set<String> {
        val def = ModelLoader.load(model)
        val doc = interpreterDocument(def, row, sku)
        return DmInterpreter(def).validate(doc).messages.map { it.code }.filterTo(sortedSetOf()) { it in CODES }
    }

    private fun partialKernelFired(coveredRow: Int?): Set<String> {
        val document = kernelDocument(row = 3)
        val relevant = buildSet {
            add(Relevant.of(REF, 0, 0))
            if (coveredRow != null) add(Relevant.of(LINES, 1, coveredRow))
        }
        val dynamic = RuntimeLaws.messagesPart(model, document, relevant).map { it.code() }
            .filterTo(sortedSetOf()) { it in CODES }
        val static = staticEngine.messagesPart(document, relevant).map { it.code() }
            .filterTo(sortedSetOf()) { it in CODES }
        assertEquals(dynamic, static, "partial row $coveredRow — dynamic-Groovy vs static-Java")
        return dynamic
    }

    private fun partialInterpreterFired(coveredRow: Int): Set<String> {
        val def = ModelLoader.load(model)
        val relevant = setOf(
            RelevantEntity.anyRep(REF),
            RelevantEntity(LINES, listOf(1, coveredRow)),
        )
        return DmInterpreter(def).validatePart(interpreterDocument(def, row = 3), relevant).messages
            .map { it.code }.filterTo(sortedSetOf()) { it in CODES }
    }

    private fun descendantKernelFired(): Set<String> {
        val document = descendantKernelDocument()
        val dynamic = RuntimeLaws.messages(descendantModel, document).map { it.code() }
            .filterTo(sortedSetOf()) { it in DESCENDANT_CODES }
        val static = descendantStaticEngine.messages(descendantStaticEngine.instance(document)).map { it.code() }
            .filterTo(sortedSetOf()) { it in DESCENDANT_CODES }
        assertEquals(dynamic, static, "descendant separator — dynamic-Groovy vs static-Java")
        return dynamic
    }

    private fun descendantInterpreterFired(): Set<String> {
        val def = ModelLoader.load(descendantModel)
        return DmInterpreter(def).validateFull(descendantInterpreterDocument(def)).map { it.code }
            .filterTo(sortedSetOf()) { it in DESCENDANT_CODES }
    }

    private fun descendantKernelDocument(): RuntimeLaws.Doc = kernelDocument(row = 3)
        .group(IN_CAP_MARKER, 1, 2, 1)
        .field(IN_CAP_VALUE, "yes", 1, 2, 1, 1)
        .group(OVER_CAP_MARKER, 1, 3, 1)
        .field(OVER_CAP_VALUE, "yes", 1, 3, 1, 1)

    private fun descendantInterpreterDocument(def: ModelDef) =
        interpreterDocument(def, row = 3)
            .group(IN_CAP_MARKER, 1, 2, 1)
            .field(IN_CAP_VALUE, "yes", 1, 2, 1, 1)
            .group(OVER_CAP_MARKER, 1, 3, 1)
            .field(OVER_CAP_VALUE, "yes", 1, 3, 1, 1)

    private fun kernelDocument(row: Int, sku: String? = null): RuntimeLaws.Doc = RuntimeLaws.doc()
        .group(ORDER, 1)
        .field(REF, "R-1", 1, 1)
        .group(LINES, 1, row)
        .also { document -> if (sku != null) document.field(SKU, sku, 1, row, 1) }

    private fun interpreterDocument(
        def: ModelDef,
        row: Int,
        sku: String? = null,
    ) = def.newDocument()
        .group(ORDER, 1)
        .field(REF, "R-1", 1, 1)
        .group(LINES, 1, row)
        .also { document -> if (sku != null) document.field(SKU, sku, 1, row, 1) }

    private companion object {

        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val REF = "/Order/Reference"
        const val SKU = "/Order/Lines/Sku"
        const val IN_CAP_MARKER = "/Order/Lines/InCapacityMarker"
        const val IN_CAP_VALUE = "$IN_CAP_MARKER/Value"
        const val OVER_CAP_MARKER = "/Order/Lines/OverCapacityMarker"
        const val OVER_CAP_VALUE = "$OVER_CAP_MARKER/Value"

        const val NONE_FILLED = "NONE_FILLED"
        const val AT_LEAST_ONE = "AT_LEAST_ONE"
        const val OVER_REPETITION = "zuGrosseZeile"
        const val COUNT_IS_0 = "COUNT_IS_0"
        const val COUNT_IS_1 = "COUNT_IS_1"
        const val COUNT_IS_2 = "COUNT_IS_2"
        const val IN_CAP_PRESENT = "IN_CAP_PRESENT"
        const val OVER_CAP_PRESENT = "OVER_CAP_PRESENT"
        const val OVER_CAP_ABSENT = "OVER_CAP_ABSENT"
        val CODES = setOf(NONE_FILLED, AT_LEAST_ONE)
        val DESCENDANT_CODES = setOf(IN_CAP_PRESENT, OVER_CAP_PRESENT, OVER_CAP_ABSENT)
        val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(model) }
        val descendantStaticEngine by lazy { RuntimeLaws.kernelJavaEngine(descendantModel) }

        private val baseModel: String = DocumentModelDraft.named("capacity").locale("en_US")
            .rootGroup("Order") { order ->
                order.field("Reference", FieldKind.STRING)
                    .group("Lines") { lines ->
                        lines.repeatable(2)
                            .field("Sku", FieldKind.STRING)
                            .group("InCapacityMarker") { marker -> marker.field("Value", FieldKind.STRING) }
                            .group("OverCapacityMarker") { marker -> marker.field("Value", FieldKind.STRING) }
                    }
            }.build().toJson()

        /** Both starred quantifiers plus the count ladder over one scope, so the operator is the only variable. */
        val model: String = run {
            val base = BoundModel.ofJson(baseModel)
            val lines = base.flattenGroup("$LINES*")
            val counted = base.group(LINES)
            val guard = Dsl.fieldFilled(base.field(REF))
            fun render(c: Condition) = DslRenderer().render(c)
            var json = baseModel
            listOf(
                NONE_FILLED to render(guard.and(Dsl.noGroupFilled(lines))),
                AT_LEAST_ONE to render(guard.and(Dsl.atLeastOneGroupFilled(lines))),
                // The ladder: exactly one rung fires and names the tally, which a boolean quantifier cannot.
                COUNT_IS_0 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(0))),
                COUNT_IS_1 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(1))),
                COUNT_IS_2 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(2))),
            ).forEach { (code, condition) ->
                json = RuntimeLaws.withRule(json, ORDER, code, "../Reference", code, condition)
            }
            json
        }

        /** The descendant-only separator layered over the original count/quantifier fixture. */
        val descendantModel: String = run {
            val base = BoundModel.ofJson(baseModel)
            val guard = Dsl.fieldFilled(base.field(REF))
            val inCapacityMarker = base.flattenGroup("$LINES*/InCapacityMarker")
            val overCapacityMarker = base.flattenGroup("$LINES*/OverCapacityMarker")
            fun render(c: Condition) = DslRenderer().render(c)
            var json = model
            listOf(
                IN_CAP_PRESENT to render(guard.and(Dsl.atLeastOneGroupFilled(inCapacityMarker))),
                OVER_CAP_PRESENT to render(guard.and(Dsl.atLeastOneGroupFilled(overCapacityMarker))),
                OVER_CAP_ABSENT to render(guard.and(Dsl.noGroupFilled(overCapacityMarker))),
            ).forEach { (code, condition) ->
                json = RuntimeLaws.withRule(json, ORDER, code, "../Reference", code, condition)
            }
            json
        }
    }
}
