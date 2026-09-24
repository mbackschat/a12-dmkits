package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the IMPLICIT computation-validation
 * rule (IF32/IF65) is anchored at the computation's **TARGET**, not its declaring group (the IG56 E3 drill's
 * first minimal: a real workspace computation declared in an EMPTY `*_Rules` container root, targeting a field
 * in ANOTHER root — the validation twin of IF89's "a computation iterates where it WRITES").
 *
 * The kernel's rule-from-computation fires at the error entity (the computed field): declared-in-`/HrRules`,
 * targeting `/Employee/ContractedHours`, it fires `HoursComp|…|/Employee[1]/ContractedHours` whenever the
 * stored value disagrees with the applicable tier's term — regardless of `/HrRules` being empty. The
 * interpreter's synthetic rule was anchored at the DECLARING group, so on a document with no `/HrRules`
 * content the row-gate excluded it entirely: the implicit validation never fired (a `kernel-only` unit), and
 * for a REPEATABLE target it also missed every row ≥ 2 (the histogram's `kernel-only … row≥2` class).
 */
class ImplicitComputationValidationScopeDiffTest {

    private companion object {
        const val HOURS = "/Employee/ContractedHours"
        const val START = "/Assignments/Assignment/StartDate"
        const val CAPACITY = "/Depots/Depot/Capacity"
    }

    private val base = DocumentModelDraft.named("workforce").locale("en_US")
        .rootGroup("Employee") { e -> e.numberField("ContractedHours") { it.fractionDigits(0, 2) } }
        .rootGroup("Assignments") { a ->
            a.group("Assignment") { g -> g.repeatable(10).dateField("StartDate") { } }
        }
        .rootGroup("Depots") { d ->
            d.group("Depot") { g -> g.repeatable(10).numberField("Capacity") { it.fractionDigits(0, 2) } }
        }
        .rootGroup("HrRules") { h -> h.stringField("Note") { } }.build().toJson()

    /** The corpus shape: declared in the EMPTY container, guarded on "no assignment started", term −1. */
    private fun hoursModel(): String = RuntimeLaws.withTieredComputation(base, "/HrRules", "HoursComp",
        "../../Employee/ContractedHours", null,
        listOf(RuntimeLaws.CompAlt("NoFieldFilled(/Assignments/Assignment*/StartDate)", "-1")))

    /** The repeatable-target arm: same empty declaring container, target rows in another root. (An ITERATING
     *  condition — a starred quantifier — with a cross-group repeatable error field is REJECTED at codegen,
     *  `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`, so the precondition here is star-free.) */
    private fun capacityModel(): String = RuntimeLaws.withTieredComputation(base, "/HrRules", "CapComp",
        "../../Depots/Depot/Capacity", null,
        listOf(RuntimeLaws.CompAlt("FieldNotFilled(/Employee/ContractedHours)", "-1")))

    private fun agree(kModel: String, code: String, expect: List<String>, msg: String, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == code }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == code }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == code }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ── the corpus shape: an empty declaring container must not silence the implicit validation ─────────────

    @Test fun firesAtTheTargetWhenTheDeclaringContainerIsEmpty() =
        agree(hoursModel(), "HoursComp",
            expect = listOf("/Employee[1]/ContractedHours|OMISSION_ERROR"),
            "no assignments → term −1 ≠ stored 40.00 → the implicit validation fires AT THE TARGET") { b ->
            b.group("/Employee", 1).field(HOURS, "40.00", 1, 1)
        }

    @Test fun silentWhenTheStoredValueMatchesTheTerm() =
        agree(hoursModel(), "HoursComp", expect = emptyList(),
            "stored −1 == the term → the implicit validation is silent") { b ->
            b.group("/Employee", 1).field(HOURS, "-1", 1, 1)
        }

    @Test fun silentWhenThePreconditionIsFalse() =
        agree(hoursModel(), "HoursComp", expect = emptyList(),
            "an assignment started → the tier does not apply → silent") { b ->
            b.group("/Employee", 1).field(HOURS, "40.00", 1, 1)
            b.group("/Assignments/Assignment", 1, 1).field(START, "2024-02-27", 1, 1, 1)
        }

    // ── reachability control: a CROSS-GROUP computation into a REPEATABLE target is codegen-REJECTED ────────

    /** `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` fires even with a star-free precondition — the implicit validation's
     *  own error field sits in a repeatable group outside the rule group — so the repeatable-target cross-group
     *  shape is parity by construction; only a NON-repeatable cross-group target reaches the runtime. */
    @Test fun crossGroupRepeatableTargetIsRejectedByTheKernel() {
        val doc = RuntimeLaws.doc().group("/Employee", 1)
        val thrown = runCatching { RuntimeLaws.messages(capacityModel(), doc) }.exceptionOrNull()
        assertEquals(true, thrown?.message?.contains("MVK_ERROR_FIELD_NOT_IN_RULEGROUP"),
            "the kernel rejects the repeatable cross-group target: ${thrown?.message?.take(160)}")
    }
}
