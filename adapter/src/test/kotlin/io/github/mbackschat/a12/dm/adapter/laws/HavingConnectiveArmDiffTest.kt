package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — a `Having` filter's own connectives behave DIFFERENTLY on the two arms, which is
 * a12-kernel-lean's `SPEC-2026-09-03-03`. Validation reads the filter as a strong-Kleene tree, where the
 * dominating operand wins from either side, so authored operand order is unobservable. Computation reads it left
 * to right and aborts on the first poison it actually reaches, so authored order decides the result.
 *
 * **Why the consumer consequence is the point.** Reordering a filter's operands is verdict-preserving on
 * validation and NOT result-preserving on computation, so a normalizing or rule-refactoring transformation
 * proved sound against validation alone is unsound for computations.
 *
 * **Both connectives short-circuit, each on its own dominating polarity** — `Or` on a true operand, `And` on a
 * false one — so this is a fact about the ARM rather than about `Or`. The design keeps the malformed cell fixed
 * and varies only what sits beside it and in which order:
 *
 * <ul>
 * <li>`carrierPresent` + `Or`: the carrier decides TRUE, so the spelling that reads it FIRST never touches the
 *     malformed weight and computes, while the reverse spelling poisons on the identical document.</li>
 * <li>`carrierAbsent` + `And`: the carrier decides FALSE, and the same asymmetry appears at the other
 *     connective, on the other polarity.</li>
 * <li>`carrierPresent` + `And` is the CONTROL that separates a short-circuit from a conjunction that ignores its
 *     poison: with nothing to dominate on, BOTH spellings abort.</li>
 * </ul>
 *
 * **The validation arm is measured on the very same models and documents**, and every spelling agrees there.
 * Without that half the file would be a poison-ordering test rather than an arm asymmetry — the two arms have to
 * be read side by side for the claim to mean anything.
 *
 * Own domain (parcel logistics).
 */
// One instance per CLASS: model JSON, loaded definitions and kernel Java engines are per-spelling fields, and
// this Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HavingConnectiveArmDiffTest {

    private companion object {
        const val SHIPMENT = "/Shipment"
        const val TOTAL = "/Shipment/Total"
        const val MARKER = "/Shipment/Marker"
        const val PARCELS = "/Shipment/Parcels"
        const val WEIGHT = "/Shipment/Parcels/Weight"
        const val CARRIER = "/Shipment/Parcels/Carrier"
        const val UNITS = "/Shipment/Parcels/Units"

        /** Not a decimal, so the cell is present and formally invalid — the poison trigger. */
        const val MALFORMED = "oversize"
    }

    private val base = DocumentModelDraft.named("parcel-logistics-filter-arms").locale("en_US")
        .rootGroup("Shipment") { shipment ->
            shipment.stringField("Marker") { }
            shipment.numberField("Total") { it.fractionDigits(0) }
            shipment.group("Parcels") { parcels ->
                parcels.repeatable(50)
                parcels.numberField("Weight") { it.fractionDigits(0) }
                parcels.stringField("Carrier") { }
                parcels.numberField("Units") { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val unitList = bound.flatten("$PARCELS*/Units")

    private fun weightFilled() = Dsl.fieldFilled(bound.field(WEIGHT))
    private fun carrierFilled() = Dsl.fieldFilled(bound.field(CARRIER))

    /** The four spellings under test: each connective, each operand order, one malformed operand throughout. */
    private val spellings = linkedMapOf(
        "Or(weight, carrier)" to weightFilled().or(carrierFilled()),
        "Or(carrier, weight)" to carrierFilled().or(weightFilled()),
        "And(weight, carrier)" to weightFilled().and(carrierFilled()),
        "And(carrier, weight)" to carrierFilled().and(weightFilled()),
    )

    /** `Total` = the `Sum` of the `Parcels*` flatten of `Units`, filtered — the computation arm's carrier. */
    private fun computation(filter: Condition): Computation =
        bound.computation(bound.num(TOTAL)).name("TotalComp").group(bound.group(SHIPMENT))
            .calc(Dsl.sum(unitList.having(filter)).expression())
            .message(Dsl.en("Total units over the kept parcels.")).build()

    /** The same filtered `Sum` compared to `5`, beside the error field's own presence — the validation arm. */
    private fun rule(filter: Condition) =
        bound.rule(bound.group(SHIPMENT)).name("KeptRule").errorField(bound.str(MARKER))
            .`when`(Dsl.fieldFilled(bound.field(MARKER)).and(Dsl.sum(unitList.having(filter)).eq(5)))
            .code("KEPT").severity(Severity.ERROR).message(Dsl.en("KEPT")).build()

    /** One model per spelling, carrying BOTH arms so the two readings are of the same authored text. */
    private val models: Map<String, String> = spellings.mapValues { (_, filter) ->
        TypedRules.inject(TypedRules.inject(base, computation(filter)), rule(filter))
    }
    private val definitions = models.mapValues { ModelLoader.load(it.value) }
    private val staticEngines by lazy { models.mapValues { RuntimeLaws.kernelJavaEngine(it.value) } }

    @Test
    fun everySpellingIsAdmitted() = assertEquals(
        spellings.keys.associateWith { emptyList<String>() },
        models.mapValues { RuntimeLaws.consistencyMessages(it.value) },
        "a refused model would make every row below vacuous",
    )

    /**
     * The computation arm: authored operand order is DECISIVE. Read the first two rows against each other and
     * then the last two — one document each, identical cells, only the operand order differs.
     */
    @Test
    fun theComputationArmReadsLeftToRightAndAbortsOnTheFirstReachedPoison() = assertEquals(
        linkedMapOf(
            "carrierPresent/Or(weight, carrier)" to "CLEARED",
            "carrierPresent/Or(carrier, weight)" to "VALUE|5",
            "carrierPresent/And(weight, carrier)" to "CLEARED",
            "carrierPresent/And(carrier, weight)" to "CLEARED",
            "carrierAbsent/Or(weight, carrier)" to "CLEARED",
            "carrierAbsent/Or(carrier, weight)" to "CLEARED",
            "carrierAbsent/And(weight, carrier)" to "CLEARED",
            "carrierAbsent/And(carrier, weight)" to "VALUE|0",
        ),
        matrix { spelling, carrier -> computeOutcome(spelling, carrier) },
        "a dominating operand read FIRST short-circuits past the malformed one; with nothing to dominate on " +
            "(carrierPresent + And) both spellings abort, which is what makes it a short-circuit rather than a " +
            "conjunction that ignores poison",
    )

    /**
     * The validation arm on the identical models and documents: every spelling agrees, at both connectives and
     * both operand orders. This is the half that turns the matrix above into an ARM asymmetry.
     */
    @Test
    fun theValidationArmIsCommutativeAtBothConnectives() = assertEquals(
        linkedMapOf(
            "carrierPresent/Or(weight, carrier)" to "fired",
            "carrierPresent/Or(carrier, weight)" to "fired",
            "carrierPresent/And(weight, carrier)" to "silent",
            "carrierPresent/And(carrier, weight)" to "silent",
            "carrierAbsent/Or(weight, carrier)" to "silent",
            "carrierAbsent/Or(carrier, weight)" to "silent",
            "carrierAbsent/And(weight, carrier)" to "silent",
            "carrierAbsent/And(carrier, weight)" to "silent",
        ),
        matrix { spelling, carrier -> validationOutcome(spelling, carrier) },
        "on validation the malformed operand is UNKNOWN and Kleene's dominating value wins from either side, " +
            "so each connective answers the same under both orders",
    )

    /**
     * The sentence KF235 draws its consumer consequence from, given its own witness: the eager
     * `formalErrorsInOperands` inventory reports the malformed cell on EVERY spelling, including the two that
     * compute a value. So it records that an operand was formally invalid, not that a computation aborted, and a
     * consumer cannot use it as an abort predicate. Measured on the two spellings that differ in outcome, so the
     * constant answer is read against a varying one rather than on its own.
     */
    @Test
    fun theEagerOperandInventoryIsBlindToWhetherTheComputationAborted() = assertEquals(
        mapOf(
            "Or(carrier, weight) — computes VALUE|5" to "/Shipment[1]/Parcels[1]/Weight",
            "Or(weight, carrier) — clears" to "/Shipment[1]/Parcels[1]/Weight",
        ),
        mapOf(
            "Or(carrier, weight) — computes VALUE|5" to inventory("Or(carrier, weight)"),
            "Or(weight, carrier) — clears" to inventory("Or(weight, carrier)"),
        ),
        "the inventory is a pre-computation record of the operand, identical whether or not the read happened",
    )

    /**
     * **The COMPUTE arm of the per-row formal-invalidity gate ([IF319]).** That defect was found and fixed on the
     * validation arm, and `HavingNonTrueRowDiffTest` locks it there — but the same gate is consulted from the
     * compute path's poisoning seam, and every existing compute-plus-filter differential in this estate carries a
     * single repeatable row, where the enclosing instance's coordinates and the filter row's coincide and the
     * change is invisible. A cold review named that gap on 2026-09-03.
     *
     * <p>Two rows make them differ. Row 1 has a present `Carrier` and a MALFORMED `Weight`, so `Or` short-circuits
     * on the carrier and never reads the weight. Row 2 has an absent carrier and a well-formed weight, so it does
     * read it — and a gate resolved at the enclosing instance would judge row 2's read by ROW 1's malformed cell
     * and poison the whole computation. Both rows are kept, so the answer is the sum of both `Units`.
     */
    @Test
    fun theComputeArmJudgesEachFilterRowByItsOwnCell() = assertEquals(
        "VALUE|13",
        computeOutcomeOn("Or(carrier, weight)", twoRowDocument(), twoRowInterpreterDocument()),
        "row 2's own well-formed weight decides its read; row 1's malformed cell is never reached and must " +
            "not be judged in its place",
    )

    // ---- the instrument ----

    /** The eager pre-computation operand inventory for one spelling, as a sorted comma-joined field list. */
    private fun inventory(spelling: String): String =
        RuntimeLaws.computeWithFormalErrors(models.getValue(spelling), kernelDocument(carrierPresent = true))
            .formalErrorsInOperands().map { it.field() }.distinct().sorted().joinToString(",")

    private fun matrix(read: (String, Boolean) -> String): Map<String, String> = buildMap {
        for (carrierPresent in listOf(true, false)) {
            val label = if (carrierPresent) "carrierPresent" else "carrierAbsent"
            for (spelling in spellings.keys) put("$label/$spelling", read(spelling, carrierPresent))
        }
    }

    /** The `Total` outcome, asserted equal on both codegen strategies and the interpreter before it is read. */
    private fun computeOutcome(spelling: String, carrierPresent: Boolean): String {
        val model = models.getValue(spelling)
        val definition = definitions.getValue(spelling)
        val dynamic = RuntimeLaws.compute(model, kernelDocument(carrierPresent))
            .single { it.path() == TOTAL }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }
        val engine = staticEngines.getValue(spelling)
        val static = engine.computeOutcomesSorted(engine.instance(kernelDocument(carrierPresent)))
            .single { it.substringBefore('|') == TOTAL }
            .let {
                when (val v = it.substringAfter('|')) {
                    "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v"
                }
            }
        val interpreter = DmInterpreter(definition).compute(interpreterDocument(definition, carrierPresent))
            .single { it.path == TOTAL }
            .let {
                when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                }
            }
        assertEquals(dynamic, static, "$spelling: generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "$spelling: interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    private fun validationOutcome(spelling: String, carrierPresent: Boolean): String {
        val model = models.getValue(spelling)
        val definition = definitions.getValue(spelling)
        val dynamic = RuntimeLaws.messages(model, kernelDocument(carrierPresent)).any { it.code() == "KEPT" }
        val engine = staticEngines.getValue(spelling)
        val static = engine.messages(engine.instance(kernelDocument(carrierPresent))).any { it.code() == "KEPT" }
        val interpreter = DmInterpreter(definition)
            .validateFull(interpreterDocument(definition, carrierPresent)).any { it.code == "KEPT" }
        assertEquals(dynamic, static, "$spelling: generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "$spelling: interpreter vs normative dynamic-Groovy")
        return if (dynamic) "fired" else "silent"
    }

    /** Row 1: carrier present, weight MALFORMED, units 5. Row 2: carrier absent, weight 3, units 8. */
    private fun twoRowDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(SHIPMENT, 1)
        .field(MARKER, "consignment", 1, 1).field(TOTAL, "99", 1, 1)
        .group(PARCELS, 1, 1)
        .field(WEIGHT, MALFORMED, 1, 1, 1).field(CARRIER, "Northwind Freight", 1, 1, 1)
        .field(UNITS, "5", 1, 1, 1)
        .group(PARCELS, 1, 2)
        .field(WEIGHT, "3", 1, 2, 1).field(UNITS, "8", 1, 2, 1)

    private fun twoRowInterpreterDocument(): Document {
        val definition = definitions.getValue("Or(carrier, weight)")
        return definition.newDocument().group(SHIPMENT, 1)
            .field(MARKER, "consignment", 1, 1).field(TOTAL, "99", 1, 1)
            .group(PARCELS, 1, 1)
            .field(WEIGHT, MALFORMED, 1, 1, 1).field(CARRIER, "Northwind Freight", 1, 1, 1)
            .field(UNITS, "5", 1, 1, 1)
            .group(PARCELS, 1, 2)
            .field(WEIGHT, "3", 1, 2, 1).field(UNITS, "8", 1, 2, 1)
    }

    /** The tri-engine `Total` outcome for an explicit document pair. */
    private fun computeOutcomeOn(spelling: String, kDoc: RuntimeLaws.Doc, iDoc: Document): String {
        val model = models.getValue(spelling)
        val dynamic = RuntimeLaws.compute(model, kDoc).single { it.path() == TOTAL }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }
        val engine = staticEngines.getValue(spelling)
        val static = engine.computeOutcomesSorted(engine.instance(kDoc))
            .single { it.substringBefore('|') == TOTAL }
            .let {
                when (val v = it.substringAfter('|')) {
                    "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v"
                }
            }
        val interpreter = DmInterpreter(definitions.getValue(spelling)).compute(iDoc).single { it.path == TOTAL }
            .let {
                when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                }
            }
        assertEquals(dynamic, static, "$spelling: generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "$spelling: interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    /** One parcel row: a malformed `Weight`, `Units` = 5, and a `Carrier` that is present or absent. */
    private fun kernelDocument(carrierPresent: Boolean): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(SHIPMENT, 1).field(MARKER, "consignment", 1, 1)
            .field(TOTAL, "99", 1, 1)
            .group(PARCELS, 1, 1)
            .field(WEIGHT, MALFORMED, 1, 1, 1)
            .field(UNITS, "5", 1, 1, 1)
        if (carrierPresent) doc.field(CARRIER, "Northwind Freight", 1, 1, 1)
        return doc
    }

    private fun interpreterDocument(
        definition: ModelDef,
        carrierPresent: Boolean,
    ): Document {
        val doc = definition.newDocument().group(SHIPMENT, 1).field(MARKER, "consignment", 1, 1)
            .field(TOTAL, "99", 1, 1)
            .group(PARCELS, 1, 1)
            .field(WEIGHT, MALFORMED, 1, 1, 1)
            .field(UNITS, "5", 1, 1, 1)
        if (carrierPresent) doc.field(CARRIER, "Northwind Freight", 1, 1, 1)
        return doc
    }
}
