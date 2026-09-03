package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — what a `Having` conjunct that is NOT TRUE does to the aggregate around it, and how
 * far the empty-operand-as-zero rule reaches into a filter. a12-kernel-lean's `SPEC-2026-09-03-01` asks this
 * project to confirm both halves or supply a contrary witness; the rows below re-derive them here.
 *
 * **Half one — a non-true conjunct drops its own ROW and leaves the aggregate available.** The neighbouring
 * target rule says the first formally unavailable SELECTED cell makes the whole aggregate unavailable, so the
 * two positions had to be separated rather than assumed alike. They are: a document whose every row carries a
 * malformed FILTER cell still answers an ordinary `0`, while one malformed TARGET cell among the selected rows
 * takes the aggregate away entirely.
 *
 * **Half two — the empty-as-zero rule reaches an ABSENT operand and not a MALFORMED one.** An absent Number
 * participates as `0` and the comparison decides; a malformed one makes the conjunct non-true in BOTH
 * directions. That is why the ladder carries `> 5` and `< 5` over the same cell: a single comparison direction
 * cannot tell a substituted zero from a dropped row, because both spellings agree on `> 5`. Only the `< 5`
 * column separates them, and it is exactly where the absent and malformed documents part.
 *
 * **What makes it a per-row reading rather than an aggregate one.** A count ladder — `== 0`, `== 1`, `== 2` —
 * brackets the survivor count exactly, so the mixed document reports one survivor against the well-formed
 * control's two. A single `Sum` threshold would fire at either candidate value and establish nothing.
 *
 * Own domain (insurance claim settlement).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HavingNonTrueRowDiffTest {

    private companion object {
        const val POLICY = "/Policy"
        const val HOLDER = "/Policy/Holder"
        const val CLAIMS = "/Policy/Claims"
        const val ASSESSED = "/Policy/Claims/Assessed"
        const val PAID = "/Policy/Claims/Paid"
        const val MALFORMED = "under review"
    }

    private val base = DocumentModelDraft.named("claim-settlement-filter").locale("en_US")
        .rootGroup("Policy") { policy ->
            policy.stringField("Holder") { }
            policy.group("Claims") { claims ->
                claims.repeatable(50)
                claims.numberField("Assessed") { it.fractionDigits(0) }
                claims.numberField("Paid") { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val paidList = bound.flatten("$CLAIMS*/Paid")

    /** `Assessed < 5` — the filter the whole ladder rests on. */
    private fun below() = bound.num(ASSESSED).lt(5)

    /** `Assessed > 5`, the opposite direction over the identical cell. */
    private fun above() = bound.num(ASSESSED).gt(5)

    /** `FieldFilled(Holder) And <aggregate over the filtered Paid list> == [value]`. */
    private fun rule(code: String, filter: Condition, value: Long, counting: Boolean): Rule {
        val filtered = paidList.having(filter)
        val aggregate = if (counting) Dsl.numberOfFilledFields(filtered) else Dsl.sum(filtered)
        return bound.rule(bound.group(POLICY)).name("${code}Rule").errorField(bound.str(HOLDER))
            .`when`(Dsl.fieldFilled(bound.field(HOLDER)).and(aggregate.eq(value)))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()
    }

    /** `FieldFilled(Holder) And Sum(<unfiltered Paid list>) == 6` — reads no `Assessed` cell at all. */
    private fun unfilteredRule(): Rule =
        bound.rule(bound.group(POLICY)).name("PLAINRule").errorField(bound.str(HOLDER))
            .`when`(Dsl.fieldFilled(bound.field(HOLDER)).and(Dsl.sum(paidList).eq(6)))
            .code("PLAIN").severity(Severity.ERROR).message(Dsl.en("PLAIN")).build()

    private val rules = listOf(
        // The LIVENESS control, and it is the only rule here that does not consult the varied cell. Without it
        // every probe depends on `Assessed`, so a document where the malformed cell suppressed the whole rule
        // set would look exactly like one where no row survived — the probe-set confound CLAUDE.md names.
        unfilteredRule(),
        rule("ABOVE0", above(), 0, counting = false),
        rule("BELOW0", below(), 0, counting = false),
        rule("BELOW2", below(), 2, counting = false),
        rule("BELOW4", below(), 4, counting = false),
        rule("BELOW6", below(), 6, counting = false),
        rule("BELOW12", below(), 12, counting = false),
        rule("COUNT0", below(), 0, counting = true),
        rule("COUNT1", below(), 1, counting = true),
        rule("COUNT2", below(), 2, counting = true),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model would make every row below vacuous")

    /**
     * The complete matrix, asserted per document as an exact fired-code set. Read it column-wise:
     *
     * <p><b>`BELOW*` is where the two clear-of-value kinds part.</b> `absentFilterCell` answers exactly as the
     * well-formed control does — both rows kept, `Sum` 6, two survivors — because the absent cell participates
     * as `0` and `0 < 5` is true. `malformedFilterCell` drops that row instead: `Sum` 4, one survivor.
     *
     * <p><b>`ABOVE0` is the control that proves the pair is a reading rather than a coincidence.</b> Under
     * `> 5` every document agrees at `0`, since a substituted zero and a dropped row both fail that comparison.
     * A ladder in one direction would have shown nothing.
     *
     * <p><b>`everyFilterCellMalformed` is the load-bearing row.</b> No row survives and the aggregate is still
     * AVAILABLE at its ordinary `0` — `BELOW0` and `COUNT0` both fire — so a non-true conjunct excludes its own
     * row rather than routing the aggregate to unavailable.
     *
     * <p><b>`malformedTargetCell` is its contrast, and it carries both positions in ONE document.</b> Under
     * `< 5` the filter keeps both rows, one SELECTED `Paid` cell is malformed, and the whole `BELOW`/`COUNT`
     * ladder goes silent — the aggregate is unavailable. Under `> 5` on the same document no row is selected at
     * all, so nothing malformed is ever read and `ABOVE0` answers an ordinary `0`. One malformation, two
     * positions, two rules — which is the distinction the entry asks to be stated rather than left adjacent.
     *
     * <p><b>The last two rows are the ROW-POSITION control</b>, and they exist because the first version of this
     * file found a defect in this project's own interpreter: the formal-invalidity gate was resolved once at the
     * enclosing rule's row and consulted from every filter row, so ONE malformed cell in the first repetition
     * silently dropped every other row too (IF319). `malformedInTheSecondRowOnly` is the case that mechanism
     * predicts to behave correctly — the gate reads a clean first row, and the malformed second row is dropped
     * by its own value read — and `malformedFirstOfThree` is the case it predicts to fail, on two survivors
     * rather than one. Keeping both is what makes the fix's guard cover the class rather than the reported case.
     */
    @Test
    fun aNonTrueConjunctDropsItsRowWhileAMalformedTargetTakesTheAggregate() {
        val observed = linkedMapOf(
            "wellFormed" to observe(listOf("1" to "2", "3" to "4")),
            "absentFilterCell" to observe(listOf(null to "2", "3" to "4")),
            "malformedFilterCell" to observe(listOf(MALFORMED to "2", "3" to "4")),
            "everyFilterCellMalformed" to observe(listOf(MALFORMED to "2", MALFORMED to "4")),
            "malformedTargetCell" to observe(listOf("1" to MALFORMED, "3" to "4")),
            "malformedInTheSecondRowOnly" to observe(listOf("1" to "2", MALFORMED to "4")),
            "malformedFirstOfThree" to observe(listOf(MALFORMED to "2", "1" to "4", "3" to "8")),
        )
        // Asserted over the WHOLE matrix rather than per row, so a divergence reports every affected document at
        // once — the row-position control is only informative beside the row it contrasts with.
        val actual = observed.mapValues { it.value.dynamic }
        assertEquals(actual, observed.mapValues { it.value.static },
            "generated Java vs normative dynamic-Groovy")
        assertEquals(actual, observed.mapValues { it.value.interpreter },
            "interpreter vs normative dynamic-Groovy")
        assertEquals(
            linkedMapOf(
                "wellFormed" to setOf("PLAIN", "ABOVE0", "BELOW6", "COUNT2"),
                "absentFilterCell" to setOf("PLAIN", "ABOVE0", "BELOW6", "COUNT2"),
                "malformedFilterCell" to setOf("PLAIN", "ABOVE0", "BELOW4", "COUNT1"),
                "everyFilterCellMalformed" to setOf("PLAIN", "ABOVE0", "BELOW0", "COUNT0"),
                "malformedTargetCell" to setOf("ABOVE0"),
                "malformedInTheSecondRowOnly" to setOf("PLAIN", "ABOVE0", "BELOW2", "COUNT1"),
                "malformedFirstOfThree" to setOf("ABOVE0", "BELOW12", "COUNT2"),
            ),
            actual,
            "a non-true filter conjunct drops its own row and the aggregate stays available; the empty-as-zero " +
                "substitution reaches an absent operand and not a malformed one",
        )
    }

    // ---- the instrument ----

    /** One document's fired probe codes on each of the three engines, unjudged. */
    private data class Observation(
        val dynamic: Set<String>,
        val static: Set<String>,
        val interpreter: Set<String>,
    )

    private fun observe(rows: List<Pair<String?, String?>>): Observation {
        val codes = rules.map { it.code() }.toSet()
        return Observation(
            RuntimeLaws.messages(modelJson, kernelDocument(rows))
                .map { it.code() }.filter { it in codes }.toSortedSet(),
            staticEngine.messages(staticEngine.instance(kernelDocument(rows)))
                .map { it.code() }.filter { it in codes }.toSortedSet(),
            DmInterpreter(definition).validateFull(interpreterDocument(rows))
                .map { it.code }.filter { it in codes }.toSortedSet(),
        )
    }

    /** Per row `(Assessed, Paid)`; `null` writes no cell at all, which is the ABSENT arm. */
    private fun kernelDocument(rows: List<Pair<String?, String?>>): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(POLICY, 1).field(HOLDER, "Ridgeway Mutual", 1, 1)
        rows.forEachIndexed { i, (assessed, paid) ->
            val r = i + 1
            doc.group(CLAIMS, 1, r)
            assessed?.let { doc.field(ASSESSED, it, 1, r, 1) }
            paid?.let { doc.field(PAID, it, 1, r, 1) }
        }
        return doc
    }

    private fun interpreterDocument(rows: List<Pair<String?, String?>>): Document {
        val doc = definition.newDocument().group(POLICY, 1).field(HOLDER, "Ridgeway Mutual", 1, 1)
        rows.forEachIndexed { i, (assessed, paid) ->
            val r = i + 1
            doc.group(CLAIMS, 1, r)
            assessed?.let { doc.field(ASSESSED, it, 1, r, 1) }
            paid?.let { doc.field(PAID, it, 1, r, 1) }
        }
        return doc
    }
}
