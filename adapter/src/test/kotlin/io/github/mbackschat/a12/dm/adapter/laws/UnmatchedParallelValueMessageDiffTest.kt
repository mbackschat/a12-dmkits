package io.github.mbackschat.a12.dm.adapter.laws

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

    private fun document() = Both(RuntimeLaws.doc(), ModelLoader.load(model).newDocument())
        .group(STAFFING, 1).field("/Staffing/PlanName", "Q3", 1, 1)
        .group("/Staffing/Demand", 1, 1).field("/Staffing/Demand/Department", "Sales", 1, 1, 1)
        .field("/Staffing/Demand/Headcount", "5", 1, 1, 1)
        .group("/Staffing/Demand", 1, 2).field("/Staffing/Demand/Department", "Eng", 1, 2, 1)
        .field("/Staffing/Demand/Headcount", "3", 1, 2, 1)
        .group("/Staffing/Supply", 1, 1).field("/Staffing/Supply/Department", "Sales", 1, 1, 1)
        .field("/Staffing/Supply/Headcount", "5", 1, 1, 1)
        .group("/Staffing/Supply", 1, 2).field("/Staffing/Supply/Department", "Ops", 1, 2, 1)
        .field("/Staffing/Supply/Headcount", "2", 1, 2, 1)

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

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
