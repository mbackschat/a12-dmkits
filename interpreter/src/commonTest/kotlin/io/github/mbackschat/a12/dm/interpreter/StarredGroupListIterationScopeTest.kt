package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + JS lock for SPEC-2026-07-26-01's legal captured-prefix runtime shape. */
class StarredGroupListIterationScopeTest {

    @Test
    fun starredGroupListBindsTheExactCapturedOuterRow() {
        val model = ModelDef(
            fields = listOf(FieldDef(RESULT, Kind.STRING)),
            rules = listOf(
                RuleDef(
                    "$REGIONS/FlagPopulatedRegion",
                    REGIONS,
                    "AtLeastOneGroupFilled(Checks*)",
                    CODE,
                    "../Checks/Result",
                ),
            ),
            computations = emptyList(),
            repeatableGroups = setOf(REGIONS, CHECKS),
            declaredReps = mapOf(REGIONS to 5, CHECKS to 5),
        )
        val document = model.newDocument().group(ROOT, 1)
            .group(REGIONS, 1, 1)
            .group(REGIONS, 1, 2)
            .group(CHECKS, 1, 2, 1)
            .field(RESULT, "reviewed", 1, 2, 1, 1)

        val fired = DmInterpreter(model).validateFull(document)
            .filter { it.code == CODE }
            .map { "${it.errorPath}|${it.type.name}" }

        assertEquals(
            listOf("/Survey[1]/Regions[2]/Checks[1]/Result|${MsgType.VALUE.name}"),
            fired,
        )
    }

    private companion object {
        const val CODE = "REGION_CHECKS"
        const val ROOT = "/Survey"
        const val REGIONS = "$ROOT/Regions"
        const val CHECKS = "$REGIONS/Checks"
        const val RESULT = "$CHECKS/Result"
    }
}
