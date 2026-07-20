package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

class PreparationWorkCounterTest {

    @Test
    fun modelStaticWorkRunsOncePerPreparedEngine() {
        val model = ModelDef(
            fields = listOf(
                FieldDef("/Invoice/Base", Kind.NUMBER),
                FieldDef("/Invoice/Net", Kind.NUMBER),
            ),
            rules = listOf(
                RuleDef("/Invoice/Positive", "/Invoice", "[Base] > 0", "POS", "../Base"),
            ),
            computations = listOf(
                ComputationDef(
                    "/Invoice/Net",
                    "/Invoice",
                    listOf(ComputationAlternative(null, "[Base] + 1")),
                    name = "NetComp",
                ),
            ),
        )
        val observed = mutableListOf<PreparationEvent>()
        val engine = DmInterpreter(model).also {
            it.preparationObserver = PreparationObserver(observed::add)
        }
        val first = model.newDocument().apply { field("/Invoice/Base", "1", 1, 1) }
        val second = model.newDocument().apply { field("/Invoice/Base", "2", 1, 1) }

        engine.supportAudit()
        engine.validate(first)
        engine.compute(first)
        engine.computeField("/Invoice/Net", first)
        val prepared = observed.toList()
        engine.validate(second)
        engine.compute(second)
        engine.computeField("/Invoice/Net", second)

        assertEquals(prepared, observed, "row count, document identity, and repeated calls must add no model-static work")
        assertEquals(
            mapOf(
                PreparationWork.RULE_AST to 1,
                PreparationWork.COMPUTATION_AST to 1,
                PreparationWork.IMPLICIT_RULE_AST to 1,
                PreparationWork.RULE_PLAN to 2,
                PreparationWork.COMPUTATION_PLAN to 1,
                PreparationWork.ALL_COMPUTATION_ORDER to 1,
                PreparationWork.COMPUTATION_CLOSURE to 1,
            ),
            observed.groupingBy { it.work }.eachCount(),
        )
    }
}
