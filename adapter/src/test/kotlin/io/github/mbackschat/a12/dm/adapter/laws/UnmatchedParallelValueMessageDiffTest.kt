package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A rule message interpolating the value of a parallel-iterated field whose side of the join is unmatched — the
 * pointer carrying the kernel's `-5` repetition. The changelog's A12K-3990 (kernel 31.0.0) states that this "could
 * have" thrown and that "the empty value is shown in the error message instead"; these rows read the rendered texts
 * on both kernel strategies and the interpreter.
 *
 * Demand holds Sales and Eng, Supply holds Sales and Ops, joined on Department, so the join spans Sales (matched),
 * Eng (Supply unmatched) and Ops (Demand unmatched). `FieldsNotCollectivelyFilled` fires on both unmatched rows
 * ([ParallelIterationDiffTest]), so each message renders one matched-side value beside one unmatched-side value,
 * and the two rows are each other's control for which side is empty. Own domain (staffing).
 */
class UnmatchedParallelValueMessageDiffTest {

    private companion object {
        const val STAFFING = "/Staffing"
        const val CODE = "PV"
    }

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")
    private val m = BoundModel.ofJson(staffing)

    private val model: String = RuntimeLaws.withRuleMessages(
        staffing, STAFFING, CODE, "../Demand/Headcount", CODE,
        RuntimeLaws.render(Dsl.fieldsNotCollectivelyFilled(m.field("/Staffing/Demand/Headcount"),
            m.field("/Staffing/Supply/Headcount"))),
        "d=[\$/Staffing/Demand/Headcount.value\$] s=[\$/Staffing/Supply/Headcount.value\$]", "x",
    )

    private fun document(model: String = this.model) = Both(RuntimeLaws.doc(), ModelLoader.load(model).newDocument())
        .group(STAFFING, 1).field("/Staffing/PlanName", "Q3", 1, 1)
        .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1)
        .field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
        .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1)
        .field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
        .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1)
        .field("/Staffing/Supply/Headcount", "5", 1, 1, 1)
        .group("/Staffing/Supply", 1, 2).field("/Staffing/Supply/Department", "Ops", 1, 2, 1)
        .field("/Staffing/Supply/Headcount", "2", 1, 2, 1)

    /**
     * Both sides gain an Enumeration `Grade` whose category `Band` maps `A` and `B` to `Hi` and `Lo`. `NI` renders the
     * headcounts' names and both departments, which its condition never names; `EK` renders each grade's value and band.
     */
    private val graded: String = EditSession.read(staffing).also { edit ->
        listOf("/Staffing/Demand", "/Staffing/Supply").forEach { group ->
            edit.group(group).enumField("Grade") { it.value("A").value("B").category("Band", "Hi", "Lo") }
        }
    }.toJson().let { json ->
        val names = RuntimeLaws.withRuleMessages(json, STAFFING, "NI", "../Demand/Headcount", "NI",
            RuntimeLaws.render(Dsl.fieldsNotCollectivelyFilled(m.field("/Staffing/Demand/Headcount"),
                m.field("/Staffing/Supply/Headcount"))),
            "nd=[\$/Staffing/Demand/Headcount\$] ns=[\$/Staffing/Supply/Headcount\$] " +
                "kd=[\$/Staffing/Demand/Department.value\$] ks=[\$/Staffing/Supply/Department.value\$]", "x")
        val bound = BoundModel.ofJson(names)
        RuntimeLaws.withRuleMessages(names, STAFFING, "EK", "../Demand/Grade", "EK",
            RuntimeLaws.render(Dsl.fieldsNotCollectivelyFilled(bound.field("/Staffing/Demand/Grade"),
                bound.field("/Staffing/Supply/Grade"))),
            "vd=[\$/Staffing/Demand/Grade.value\$] vs=[\$/Staffing/Supply/Grade.value\$] " +
                "cd=[\$/Staffing/Demand/Grade->Band\$] cs=[\$/Staffing/Supply/Grade->Band\$]", "x")
    }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    /** The departments are admitted although the condition reads only the headcounts (KERNEL-FINDINGS KF675). */
    @Test
    fun theGradedModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(graded))

    /**
     * A name renders the name on either side; an index field, an Enumeration value and its category render empty on
     * the unmatched side, the Enumeration as its stored token. Eng's demand is graded `B`, Ops' supply `A`.
     */
    @Test
    fun anUnmatchedSideRendersNamesAsNamesAndTheRestEmpty() {
        val d = document(graded)
            .field("/Staffing/Demand/Grade", "A", 1, 1, 1).field("/Staffing/Demand/Grade", "B", 1, 2, 1)
            .field("/Staffing/Supply/Grade", "B", 1, 1, 1).field("/Staffing/Supply/Grade", "A", 1, 2, 1)
        val codes = setOf("NI", "EK")
        val dynamic = RuntimeLaws.errorTextsOf(graded, "NI", d.kDoc, Locale.US) +
            RuntimeLaws.errorTextsOf(graded, "EK", d.kDoc, Locale.US)
        val java = RuntimeLaws.kernelJavaEngine(graded)
        val instance = java.instance(d.kDoc)
        val generated = java.errorTextsOf(instance, "NI", Locale.US) + java.errorTextsOf(instance, "EK", Locale.US)
        val interpreted = DmInterpreter(ModelLoader.load(graded)).validateFull(d.iDoc, "en_US")
            .filter { it.code in codes }.map { it.text }
        val expected = listOf(
            "nd=[Headcount] ns=[Headcount] kd=[Eng] ks=[]", "nd=[Headcount] ns=[Headcount] kd=[] ks=[Ops]",
            "vd=[B] vs=[] cd=[Lo] cs=[]", "vd=[] vs=[A] cd=[] cs=[Hi]",
        ).sorted()
        assertEquals(mapOf("dynamic" to expected, "static" to expected, "interpreter" to expected),
            mapOf("dynamic" to dynamic.sorted(), "static" to generated.sorted(), "interpreter" to interpreted.sorted()))
    }

    /**
     * The unmatched side renders exactly as an unfilled field of its type does: `0` for this scale-zero Number
     * ([IF121](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if121)), so the changelog's "empty value" is the type's unfilled rendering.
     */
    @Test
    fun anUnmatchedSideRendersAsAnUnfilledField() {
        val d = document()
        val dynamic = RuntimeLaws.errorTextsOf(model, CODE, d.kDoc, Locale.US).sorted()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextsOf(java.instance(d.kDoc), CODE, Locale.US).sorted()
        val interpreted = DmInterpreter(ModelLoader.load(model)).validateFull(d.iDoc, "en_US")
            .filter { it.code == CODE }.map { it.text }.sorted()
        assertEquals(mapOf("dynamic" to listOf("d=[0] s=[2]", "d=[3] s=[0]"), "static" to dynamic, "interpreter" to dynamic),
            mapOf("dynamic" to dynamic, "static" to generated, "interpreter" to interpreted))
    }
}
