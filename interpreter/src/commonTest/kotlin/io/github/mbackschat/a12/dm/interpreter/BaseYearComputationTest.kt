package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable lock for numeric `BaseYear` in direct and arithmetic Number computations. */
class BaseYearComputationTest {

    @Test
    fun numberComputationsKeepBaseYearNumeric() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(INPUT, Kind.NUMBER, scale = 0),
                FieldDef(DIRECT, Kind.NUMBER, scale = 0),
                FieldDef(PLUS_ONE, Kind.NUMBER, scale = 0),
                FieldDef(PLUS_FIELD, Kind.NUMBER, scale = 0),
            ),
            rules = emptyList(),
            computations = listOf(
                ComputationDef(DIRECT, ROOT, "BaseYear"),
                ComputationDef(PLUS_ONE, ROOT, "BaseYear + 1"),
                ComputationDef(PLUS_FIELD, ROOT, "Input + BaseYear"),
            ),
            baseYear = 2020,
        )
        val document = model.newDocument().group(ROOT, 1).field(INPUT, "5", 1, 1)

        val values = DmInterpreter(model).compute(document).associate { it.path to (it.outcome to it.value) }

        assertEquals(ComputeOutcome.VALUE to "2020", values[DIRECT])
        assertEquals(ComputeOutcome.VALUE to "2021", values[PLUS_ONE])
        assertEquals(ComputeOutcome.VALUE to "2025", values[PLUS_FIELD])
    }

    private companion object {
        const val ROOT = "/Forecast"
        const val INPUT = "/Forecast/Input"
        const val DIRECT = "/Forecast/Direct"
        const val PLUS_ONE = "/Forecast/PlusOne"
        const val PLUS_FIELD = "/Forecast/PlusField"
    }
}
