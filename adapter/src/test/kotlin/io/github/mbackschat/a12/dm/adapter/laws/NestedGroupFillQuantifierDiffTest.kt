package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **all SEVEN field-fill quantifiers over a group operand whose expansion crosses a
 * NESTED repeatable level**, the extent [IG135] leaves open and that no existing lock covers.
 *
 * **What is new here is the fixture, not the operators.** The estate already measures group-operand fill
 * quantifiers on FLAT groups (`GroupStarFilledFieldsDiffTest`, `GroupStarFillComputeModeDiffTest`,
 * `GroupScopedFieldFillDiffTest`, `CollectivelyFilledGroupScopeDiffTest`). All of those put the repeatable level
 * AT the operand or below a single row, and [IG136] records why that is blind: "every current group-scope fixture
 * is flat or guards on a non-repeatable field, so the plan binds `Once` and nothing is pinned." Here the operand
 * is a NON-repeatable group holding one direct field and a nested repeatable subgroup, so the expansion has to
 * cross that subgroup's rows — which is exactly the shape that produced the fourth defect of
 * [IF287](../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s class.
 *
 * **Two of the seven are the discriminators, and the table is what makes that visible.** On the single document
 * below, a row-1-pinned expansion sees ONE filled cell (`PrimaryNote`) where a correct one sees TWO
 * (`PrimaryNote` and `Inspections[2]/Finding`). Only `MoreThanOneFieldFilled` and `NotExactlyOneFieldFilled`
 * change their answer between those two readings; the other five agree under both and are carried as controls
 * that the quantifiers evaluate at all. Asserting the whole table at once is deliberate — a row-by-row assertion
 * aborts at the first divergence and hides which of the seven moved.
 *
 * Conditions are hand-written because a group operand is not reachable through the fill quantifiers' typed slots
 * (the same type-level erasure as [DG35](../../../../../../../../../docs/DEV-API-GAPS.md)), and each is paired
 * with the equivalent EXPLICIT entity list so a disagreement cannot be read as a fixture defect.
 */
class NestedGroupFillQuantifierDiffTest {

    private companion object {
        const val REF = "/Return/Reference"
        const val HANDLING = "/Return/Handling"
        const val PRIMARY_NOTE = "/Return/Handling/PrimaryNote"
        const val INSPECTIONS = "/Return/Handling/Inspections"
        const val FINDING = "/Return/Handling/Inspections/Finding"
        const val CODE = "NGFQ"

        /** The explicit entity list the group expands to — the control operand. */
        const val EXPLICIT = "/Return/Handling/PrimaryNote, /Return/Handling/Inspections*/Finding"

        /**
         * Every quantifier with its verdict on [twoFilledOneOfThemNested]: 4 declared cells
         * (`PrimaryNote` + three `Inspections` rows), 2 of them filled, one of those in nested row 2.
         *
         * The `pinnedWouldDiffer` flag records which rows a row-1-pinned expansion (1 filled cell) answers
         * differently — those two are the discriminators, the rest are controls.
         */
        val QUANTIFIERS = listOf(
            Row("AllFieldsFilled", fires = false, pinnedWouldDiffer = false),
            Row("NoFieldFilled", fires = false, pinnedWouldDiffer = false),
            Row("AtLeastOneFieldFilled", fires = true, pinnedWouldDiffer = false),
            Row("NotAllFieldsFilled", fires = true, pinnedWouldDiffer = false),
            Row("FieldsNotCollectivelyFilled", fires = true, pinnedWouldDiffer = false),
            Row("NotExactlyOneFieldFilled", fires = true, pinnedWouldDiffer = true),
            Row("MoreThanOneFieldFilled", fires = true, pinnedWouldDiffer = true),
        )
    }

    private data class Row(val op: String, val fires: Boolean, val pinnedWouldDiffer: Boolean)

    private val base = DocumentModelDraft.named("return-handling").locale("en_US")
        .rootGroup("Return") { g ->
            g.stringField("Reference") { }
            g.group("Handling") { h ->
                h.stringField("PrimaryNote") { }
                h.group("Inspections") { i -> i.repeatable(3).stringField("Finding") { } }
            }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /**
     * Two filled cells, and the second one lives in nested row 2 — so a row-1-pinned expansion counts ONE. All
     * three declared inspection rows are instantiated, so no un-instantiated declared tail can supply its own
     * explanation for a differing declared-range answer.
     */
    private fun twoFilledOneOfThemNested(b: Both) {
        b.group("/Return", 1).field(REF, "RET-1", 1, 1)
            .field(PRIMARY_NOTE, "REPACKED", 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 2).field(FINDING, "SCUFFED", 1, 1, 2, 1)
            .group(INSPECTIONS, 1, 1, 3)
    }

    /**
     * `(groovy, javaStatic, interpreter)` for one condition as full `errorPath|type` SIGNATURES, with a kernel
     * rejection reported as a rejection.
     *
     * **Signatures rather than booleans**, because a message of the wrong TYPE reads as agreement under a boolean
     * and this file measures seven quantifiers whose polarity a later fix in the same session changed. That is not
     * a hypothetical: widening five sibling differentials from `any { code }` to this projection is what exposed
     * the seventeenth instance of this defect class, on a row written an hour earlier.
     */
    private fun verdicts(condition: String): Triple<List<String>, List<String>, List<String>> {
        val kModel = RuntimeLaws.withRule(base, "/Return", "R", "../Reference", CODE,
            "FieldFilled($REF) And $condition")
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoFilledOneOfThemNested)
        val messages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(messages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "the kernel REJECTED `$condition` (${messages.map { it.code() }.filter { it.startsWith("MVK_") }}), " +
                "so this row would compare two silences; that is a measurement about admission"
        }
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoFilledOneOfThemNested).kDoc
        return Triple(
            messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted(),
            RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US)
                .filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted(),
            DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
                .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
                .sorted(),
        )
    }

    /**
     * The whole table in one assertion: for each quantifier, the group operand and its explicit-list control,
     * each across all three engines. A single mismatch line names the quantifier, the spelling, and the engine.
     */
    @Test
    fun everyFillQuantifierAgreesOverAGroupCrossingANestedRepeatable() {
        val mismatches = mutableListOf<String>()
        for (row in QUANTIFIERS) {
            for ((spelling, operand) in listOf("GROUP" to HANDLING, "explicit-control" to EXPLICIT)) {
                val (groovy, javaStatic, interpreter) = verdicts("${row.op}($operand)")
                if (groovy.isNotEmpty() != row.fires) {
                    mismatches += "${row.op}/$spelling: expected the kernel to fire=${row.fires}, got $groovy"
                }
                if (javaStatic != groovy) {
                    mismatches += "${row.op}/$spelling: groovy=$groovy but java-static=$javaStatic (tri-check)"
                }
                if (interpreter != groovy) {
                    mismatches += "${row.op}/$spelling: kernel=$groovy but INTERPRETER=$interpreter" +
                        if (row.pinnedWouldDiffer) " — and a row-1-pinned expansion predicts exactly this" else ""
                }
            }
        }
        assertEquals(listOf<String>(), mismatches,
            "the nested-group fill-quantifier table moved; each line names one quantifier, spelling, and engine")
    }

    /**
     * The guard that keeps the table above from going vacuous — and it asserts a property of the **DOCUMENT**, not
     * a count of hand-written flags.
     *
     * The flag version a cold review caught could not do its stated job: its purpose is that a future edit cannot
     * quietly reduce this file to rows a row-1-pinned engine would also pass, and a fixture change that destroys
     * the discrimination leaves the flags intact and the guard green. What the rows actually need is that the
     * document's filled-cell count DIFFERS between the operand's full extent and a row-1-only read — two filled
     * cells versus one — because that difference is the only reason `MoreThanOneFieldFilled` and
     * `NotExactlyOneFieldFilled` can separate the readings at all.
     */
    @Test
    fun theFixtureItselfSeparatesTheFullExtentFromARowOneRead() {
        val b = Both(RuntimeLaws.doc(), ModelLoader.load(base).newDocument())
        twoFilledOneOfThemNested(b)
        val kModel = RuntimeLaws.withRule(base, "/Return", "R", "../Reference", CODE,
            "FieldFilled($REF) And NumberOfFilledFields($HANDLING) > 1")
        val def = ModelLoader.load(kModel)
        val doc = Both(RuntimeLaws.doc(), def.newDocument()).also(::twoFilledOneOfThemNested)
        val fullExtentFires = RuntimeLaws.messages(kModel, doc.kDoc).any { it.code() == CODE }
        assertTrue(fullExtentFires) {
            "the fixture no longer holds MORE than one filled cell below the operand, so no row in this table can " +
                "separate the full extent from a row-1-only read and every row would pass a pinned engine"
        }
    }
}
