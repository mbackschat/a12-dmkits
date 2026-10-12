package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The INDEX-FIELD STORED DEFAULT's phase-specific staging (SPEC-2026-07-23-14) on the PURE engine, JVM AND
 * JS/Node — the cross-platform twin of `adapter.laws.IndexDefaultStagingDiffTest` (whose expectations are the
 * real kernel's, dual-strategy). An eligible clean-empty index cell (enum S-value declared, index not
 * explicitly mandatory, exactly one instantiated index-group row under the current parent, cell absent or
 * present-empty):
 *  - FULL validation stages the S-value transiently — no `mandatoryField`/`uniqueIndex`, dependent authored
 *    rules see the staged token, and the DOCUMENT IS NOT MUTATED;
 *  - PARTIAL validation silently marks the cell unavailable — neither generated message, dependent authored
 *    conditions suppressed, the containing group retains error state (a `NoGroupFilled` cannot fire);
 *  - ineligible shapes (multiple rows, admitted explicit value, explicitly mandatory, invalid explicit)
 *    keep ordinary behavior and never expose the S-value.
 * E-commerce domain (own fixtures).
 */
class IndexDefaultStagingMultiplatformTest {

    private companion object {
        const val ORDER = "/Order"
        const val NOTE = "/Order/Note"
        const val LINES = "/Order/Lines"
        const val SERVICE_LEVEL = "/Order/Lines/ServiceLevel"
        const val QTY = "/Order/Lines/Qty"
    }

    private fun model(
        sValue: String? = "STD",
        required: Requiredness = Requiredness.NOT_REQUIRED,
        rules: List<RuleDef> = emptyList(),
    ) = ModelDef(
        listOf(
            FieldDef(NOTE, Kind.STRING),
            FieldDef(SERVICE_LEVEL, Kind.STRING, enumValues = listOf("STD", "EXPRESS"),
                enumSValue = sValue, required = required),
            FieldDef(QTY, Kind.NUMBER),
        ),
        rules,
        repeatableGroups = setOf(LINES),
        declaredReps = mapOf(LINES to 10),
        indexFields = setOf(SERVICE_LEVEL),
    )

    /** SL_STD proves the staged token reaches AUTHORED evaluation (error field Qty, MVK-style referenced). */
    private val dependentRule = RuleDef("$LINES/SlStd", LINES, "[Qty] >= 0 And [ServiceLevel] == \"STD\"", "SL_STD", "../Qty")

    /** GNF proves the containing group's state (error field Note). */
    private val groupRule = RuleDef("$ORDER/LinesGone", ORDER, "FieldFilled(Note) And NoGroupFilled(Lines*)", "GNF", "../Note")

    private fun codes(out: ValidationOutcome) = out.messages.map { it.code }.sorted()

    // ── FULL validation stages the default ──────────────────────────────────────────────────────────────

    @Test
    fun fullAbsentEligibleIndexStagesTheDefaultWithoutMutatingTheDocument() {
        val def = model(rules = listOf(dependentRule))
        val doc = def.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1)

        val out = DmInterpreter(def).validate(doc)

        assertEquals(listOf("SL_STD"), codes(out), "the dependent rule sees the staged S-value; no mandatoryField")
        assertFalse(doc.filledAt(SERVICE_LEVEL, listOf(1, 1, 1)), "staging is transient — the document is NOT mutated")
    }

    @Test
    fun fullPresentEmptyEligibleIndexStagesTheDefault() {
        val def = model(rules = listOf(dependentRule))
        val doc = def.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1).emptyField(SERVICE_LEVEL, 1, 1, 1)

        assertEquals(listOf("SL_STD"), codes(DmInterpreter(def).validate(doc)),
            "a present-empty cell is clean-empty too — same staging")
    }

    @Test
    fun fullIneligibleShapesGetNoDefault() {
        val def = model(rules = listOf(dependentRule))

        val multiRow = def.newDocument().group(ORDER, 1)
            .field(QTY, "5", 1, 1, 1).field(QTY, "7", 1, 2, 1)
        assertEquals(listOf("mandatoryField", "mandatoryField"), codes(DmInterpreter(def).validate(multiRow)),
            "two clean-empty rows: no defaults, no injected duplicate pair — ordinary mandatory errors")

        val explicit = def.newDocument().group(ORDER, 1)
            .field(QTY, "5", 1, 1, 1).field(SERVICE_LEVEL, "EXPRESS", 1, 1, 1)
        assertEquals(emptyList(), codes(DmInterpreter(def).validate(explicit)),
            "an admitted explicit value is never overridden")

        val invalid = def.newDocument().group(ORDER, 1)
            .field(QTY, "5", 1, 1, 1).field(SERVICE_LEVEL, "OVERNIGHT", 1, 1, 1)
        val out = DmInterpreter(def).validate(invalid)
        assertFalse(out.messages.any { it.code == "SL_STD" }, "an invalid explicit value never exposes the S-value")
        assertFalse(out.messages.any { it.code == "mandatoryField" }, "an invalid explicit value is not 'empty'")
        assertTrue(out.messages.isNotEmpty(), "the formal enum-domain error is reported")

        val mandatory = model(required = Requiredness.ABSOLUTE, rules = listOf(dependentRule))
        val bare = mandatory.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1)
        assertEquals(listOf("mandatoryField"), codes(DmInterpreter(mandatory).validate(bare)),
            "an explicitly mandatory index receives no default")
    }

    @Test
    fun fullPerParentScopingIsIndependent() {
        // Shipments[1] has ONE bare parcel (eligible → defaulted); Shipments[2] has TWO (ineligible).
        val def = ModelDef(
            listOf(
                FieldDef("/Warehouse/Shipments/Carrier", Kind.STRING),
                FieldDef("/Warehouse/Shipments/Parcels/SizeClass", Kind.STRING,
                    enumValues = listOf("S", "M", "L"), enumSValue = "M"),
                FieldDef("/Warehouse/Shipments/Parcels/Weight", Kind.NUMBER),
            ),
            emptyList(),
            repeatableGroups = setOf("/Warehouse/Shipments", "/Warehouse/Shipments/Parcels"),
            declaredReps = mapOf("/Warehouse/Shipments" to 5, "/Warehouse/Shipments/Parcels" to 9),
            indexFields = setOf("/Warehouse/Shipments/Parcels/SizeClass"),
        )
        val doc = def.newDocument().group("/Warehouse", 1)
            .group("/Warehouse/Shipments", 1, 1)
            .field("/Warehouse/Shipments/Parcels/Weight", "2", 1, 1, 1, 1)
            .group("/Warehouse/Shipments", 1, 2)
            .field("/Warehouse/Shipments/Parcels/Weight", "3", 1, 2, 1, 1)
            .field("/Warehouse/Shipments/Parcels/Weight", "4", 1, 2, 2, 1)

        val out = DmInterpreter(def).validate(doc)

        assertEquals(2, out.messages.count { it.code == "mandatoryField" },
            "only the TWO-row shipment's parcels are flagged: ${out.messages}")
        assertTrue(out.messages.filter { it.code == "mandatoryField" }.all { "Shipments[2]" in it.errorPath },
            "the flagged parcels belong to shipment 2: ${out.messages}")
    }

    // ── PARTIAL validation suppresses silently ──────────────────────────────────────────────────────────

    @Test
    fun partialEligibleIndexIsSilentlyUnavailable() {
        val def = model(rules = listOf(dependentRule))
        val doc = def.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1)

        val out = DmInterpreter(def).validatePart(doc, setOf(RelevantEntity.anyRep(ORDER)))

        assertEquals(emptyList(), codes(out),
            "no mandatoryField, no uniqueIndex, dependent rule suppressed — even with EVERYTHING relevant")
    }

    @Test
    fun partialContainingGroupRetainsErrorState() {
        val def = model(rules = listOf(groupRule))
        val bareRow = def.newDocument().group(ORDER, 1).field(NOTE, "n", 1, 1).group(LINES, 1, 1)

        val out = DmInterpreter(def).validatePart(bareRow, setOf(RelevantEntity.anyRep(ORDER)))

        assertFalse(out.messages.any { it.code == "GNF" },
            "the suppressed cell leaves the group in error state — absence cannot be proven")
        assertFalse(out.messages.any { it.code == "mandatoryField" }, "the suppression is silent")
    }

    @Test
    fun partialAdmittedExplicitValueKeepsOrdinaryBehavior() {
        val def = model(rules = listOf(dependentRule))
        val doc = def.newDocument().group(ORDER, 1)
            .field(QTY, "5", 1, 1, 1).field(SERVICE_LEVEL, "STD", 1, 1, 1)

        val out = DmInterpreter(def).validatePart(doc, setOf(RelevantEntity.anyRep(ORDER)))

        assertEquals(listOf("SL_STD"), codes(out), "an admitted explicit value evaluates normally in partial")
    }
    @Test
    fun invalidSingletonDefaultResolvesOnlyFullMessagePointers() {
        for (error in listOf("../Note", "../Lines/Qty")) {
            val selected = RuleDef("$ORDER/Selected", ORDER,
                "FieldFilled(Note) Or [Lines/Qty For \"STD\"] > 0", "SELECTED", error)
            val def = model(rules = listOf(dependentRule, selected))
            val doc = def.newDocument().group(ORDER, 1).field(NOTE, "review", 1, 1)
                .field(QTY, "5", 1, 1, 1).field(SERVICE_LEVEL, "OVERNIGHT", 1, 1, 1)
            val interpreter = DmInterpreter(def)
            val outcomes = listOf(interpreter.validate(doc),
                interpreter.validatePart(doc, setOf(RelevantEntity.anyRep(ORDER))), interpreter.validate(doc))
            for ((i, outcome) in outcomes.withIndex()) {
                assertEquals(emptyList(), outcome.unsupported)
                assertEquals(listOf("SELECTED", "stringFalschesMuster"), codes(outcome))
                val row = if (i == 1) -5 else 1
                val message = outcome.messages.single { it.code == "SELECTED" }
                assertEquals(listOf("/Order[1]/Lines[$row]/Qty", "/Order[1]/Lines[$row]/ServiceLevel", "/Order[1]/Note"),
                    message.referenced.sorted())
                assertEquals(if (error == "../Note") "/Order[1]/Note" else "/Order[1]/Lines[$row]/Qty", message.errorPath)
                assertEquals(emptyList(), message.fillToFix)
            }
            assertEquals("OVERNIGHT", doc.contextsFor(LINES).single().displayValue(SERVICE_LEVEL))
        }
    }

}
