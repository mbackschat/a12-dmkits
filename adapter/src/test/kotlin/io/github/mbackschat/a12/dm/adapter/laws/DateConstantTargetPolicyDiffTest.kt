package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.LocalTime

/**
 * Tri-engine differential — a bare Date CONSTANT on a target declaring the pre-1900 check, and its unchecked twin.
 *
 * Through kernel 31.1.1 the constant was NOT exempt from the target's opt-in policy (a12-kernel-lean's
 * `SPEC-2026-09-01-01`): the checked target was ERRORED retaining `1899-12-31` while its twin held the value.
 * Kernel 31.2 stopped evaluating `youngerThan1900Check` (A12K-4073,
 * [KF677](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf677)), so both targets now hold the value on
 * every engine. The pair stays the instrument: two ISO Date declarations differing in exactly one builder call,
 * the identical constant into both from two computations of one model on one document.
 *
 * Own domain (consumer-electronics warranty registration).
 */
// One instance per CLASS: these files hold their model JSON, the loaded definition and a kernel Java
// engine as fields, and this Gradle task also carries a clock-reading assertion. Per-method lifecycle
// would redo codegen and model loading on every case for no gain (CLAUDE.md: a new test must not
// perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DateConstantTargetPolicyDiffTest {

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val WARRANTY = "/Warranty"
        const val CLAIMS = "/Warranty/Claims"
        const val GUARDED = "/Warranty/Claims/GuardedOn"
        const val OPEN = "/Warranty/Claims/OpenOn"
        const val BOUNDARY = "/Warranty/Claims/BoundaryOn"
        const val REFERENCE = "/Warranty/Reference"

        /** Below 1900 and well above the always-on 1583-10-16 Gregorian floor, so only the retired check could bite. */
        val PRE_1900: LocalDate = LocalDate.of(1899, 12, 31)

        /** The threshold itself — the control that keeps the guard from reading as a blanket refusal. */
        val AT_1900: LocalDate = LocalDate.of(1900, 1, 1)

        const val ROWS = 2
        const val STARTS_AT = "/Warranty/Claims/StartsAt"
    }

    /** Two ISO Date targets differing in exactly one builder call, plus the boundary control beside them. */
    @Suppress("DEPRECATION") // the retired check is what this measures
    private val base = DocumentModelDraft.named("warranty-date-constant-policy").locale("en_US")
        .rootGroup("Warranty") { warranty ->
            warranty.stringField("Reference") { }
            warranty.group("Claims") { claims ->
                claims.repeatable(ROWS)
                claims.dateField("GuardedOn") { it.format(ISO).rejectBefore1900() }
                claims.dateField("OpenOn") { it.format(ISO) }
                claims.dateField("BoundaryOn") { it.format(ISO).rejectBefore1900() }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    private fun assign(target: String, name: String, constant: DateExpr) =
        bound.computation(bound.date(target).field()).name(name).calc(constant.expression())
            .message(Dsl.en("The warranty reference date this claim inherits.")).build()

    private val modelJson = listOf(
        assign(GUARDED, "GuardedAssign", Dsl.dateValue(PRE_1900)),
        assign(OPEN, "OpenAssign", Dsl.dateValue(PRE_1900)),
        assign(BOUNDARY, "BoundaryAssign", Dsl.dateValue(AT_1900)),
    ).fold(base) { json, computation -> TypedRules.inject(json, computation) }

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /**
     * The kernel must ADMIT this model. A constant the admission gate refused would compute nothing, and reading
     * that absence as "the target rejected the value" would report the same thing whether the policy exists or
     * not — the trap [TemporalConstantLiteralWitnessLawsTest] raises the same guard against.
     */
    @Test
    fun theModelIsAdmittedSoAnAbsentOutcomeWouldBeARejectionAndNotARefusal() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model computes nothing, which is indistinguishable from a rejected value")

    @Test
    fun theSameConstantIsAValueOnTheCheckedTargetAsOnItsUncheckedTwin() {
        val expected = (1..ROWS).flatMap { row ->
            listOf(
                outcomeKey(GUARDED, row) to "VALUE|1899-12-31",
                outcomeKey(OPEN, row) to "VALUE|1899-12-31",
                outcomeKey(BOUNDARY, row) to "VALUE|1900-01-01",
            )
        }.toMap()
        assertAgreement(
            expected,
            "one constant, two targets differing only in rejectBefore1900, and the threshold control",
        )
    }

    /**
     * **The date family's SIBLING kind, swept because the fix keys on the target's KIND while the defect keys on
     * its declared FORMAT.** `evalAssignedExpr` resolves DATE and DATE_TIME and leaves TIME alone, on the ground
     * that a time literal is already the ISO `HH:mm:ss` shape. A cold review flagged that as an assumption about
     * the declared format dressed as a reason (2026-09-03) — a TIME field does carry its own format, so a target
     * declaring anything else would land in the identical uncanonicalized-store defect.
     *
     * <p>**It is not an assumption, and the basis is derived rather than recalled.**
     * [`TEMPORAL-FORMAT-VOCABULARY.md`](../../../../../../../../../../../docs/TEMPORAL-FORMAT-VOCABULARY.md) puts 135
     * candidate format strings to the kernel and finds 12 admitted, of which `HH:mm:ss` is the ONLY
     * clock-bearing one. So there is no second TIME format for a constant to be mis-stored into: the literal's
     * spelling and the sole admissible declaration are the same string. Both halves are measured here rather
     * than cited — the admitted one end to end on three engines, and one refused neighbour against the kernel's
     * own consistency oracle, which is also what gives our client-side loader's refusal of it a kernel basis.
     */
    @Test
    fun theOnlyAdmissibleTimeDeclarationIsTheLiteralsOwnShapeSoAConstantCannotBeMisStored() {
        val document = RuntimeLaws.doc().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
            .also { d -> for (row in 1..ROWS) d.group(CLAIMS, 1, row) }
        val model = timeModel("HH:mm:ss")
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "the sole admitted clock format must be admitted, or the store below reads nothing")

        val groovy = RuntimeLaws.compute(model, document).associate { it.pointer() to signature(it) }
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = engine.compute(engine.instance(document)).associate { it.pointer() to signature(it) }
        val timeDefinition = ModelLoader.load(model)
        val interpreterDoc = timeDefinition.newDocument().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
            .also { d -> for (row in 1..ROWS) d.group(CLAIMS, 1, row) }
        val interpreter = DmInterpreter(timeDefinition).compute(interpreterDoc)
            .associate { formatPointer(it.path, it.indices) to signature(it) }

        assertEquals(
            (1..ROWS).associate { row -> "/Warranty[1]/Claims[$row]/StartsAt" to "VALUE|10:30:45" },
            groovy,
            "the time constant stores unchanged, once per row",
        )
        assertEquals(groovy, static, "generated Java")
        assertEquals(groovy, interpreter, "interpreter")

        // The neighbour, read off the KERNEL rather than off our loader: `HH:mm` is one of the 123 refused
        // candidates, so a TIME target cannot declare a shape the literal would have to be reshaped into — and
        // our own loader refusing it is delegation rather than an invented client-side gate.
        assertTrue(
            RuntimeLaws.consistencyMessages(timeDraft("HH:mm")).isNotEmpty(),
            "if the kernel admitted `HH:mm` there WOULD be a second TIME declaration and the exclusion above " +
                "would need its own measurement",
        )
    }

    /** A TIME target at one declared format, receiving the kernel's `HH:mm:ss` time literal as a constant. */
    private fun timeModel(format: String): String {
        val draft = timeDraft(format)
        val timeBound = BoundModel.ofJson(draft)
        return TypedRules.inject(
            draft,
            timeBound.computation(timeBound.time(STARTS_AT).field()).name("StartAssign")
                .calc(Dsl.timeValue(LocalTime.of(10, 30, 45)).expression())
                .message(Dsl.en("The service window this claim opens in.")).build(),
        )
    }

    /** The bare model at one declared TIME format — no computation, so a REFUSED format can still be read. */
    private fun timeDraft(format: String): String =
        DocumentModelDraft.named("warranty-time-constant").locale("en_US")
            .rootGroup("Warranty") { warranty ->
                warranty.stringField("Reference") { }
                warranty.group("Claims") { claims ->
                    claims.repeatable(ROWS)
                    claims.timeField("StartsAt") { it.format(format) }
                }
            }.build().toJson()

    // ---- the instrument ----

    private fun assertAgreement(expected: Map<String, String>, label: String) {
        val groovy = RuntimeLaws.compute(modelJson, kernelDocument())
            .associate { outcomeKey(it.path(), it.pointer()) to signature(it) }
        val static = javaEngine.compute(javaEngine.instance(kernelDocument()))
            .associate { outcomeKey(it.path(), it.pointer()) to signature(it) }
        val interpreter = DmInterpreter(definition).compute(interpreterDocument())
            .associate { outcomeKey(it.path, formatPointer(it.path, it.indices)) to signature(it) }

        assertEquals(expected, groovy, "$label — the normative dynamic-Groovy outcome")
        assertEquals(groovy, static, "$label — generated Java")
        assertEquals(groovy, interpreter, "$label — interpreter")
    }

    private fun signature(c: RuntimeLaws.Computed): String {
        val bucket = if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE"
        return "$bucket|${c.value().orElse("<none>")}"
    }

    private fun signature(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String {
        val bucket = when (c.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE"
        }
        return "$bucket|${c.value ?: "<none>"}"
    }

    private fun outcomeKey(path: String, pointer: String) = "$path@$pointer"

    private fun outcomeKey(path: String, row: Int) =
        "$path@/Warranty[1]/Claims[$row]/${path.substringAfterLast('/')}"

    private fun kernelDocument(): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
        for (row in 1..ROWS) d.group(CLAIMS, 1, row)
        return d
    }

    private fun interpreterDocument(): Document {
        val d = definition.newDocument().group(WARRANTY, 1).field(REFERENCE, "WR-40318", 1, 1)
        for (row in 1..ROWS) d.group(CLAIMS, 1, row)
        return d
    }
}
