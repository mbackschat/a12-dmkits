package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedGeneratorPatternPreparationTest {
    @Test
    fun preparesEachDistinctPatternOncePerGeneratorAcrossManyCells() {
        val rows = "/Load/Rows"
        val model = ModelDef(
            fields = listOf(
                FieldDef("$rows/PostalCode", Kind.STRING, pattern = "\\d{5}"),
                FieldDef("$rows/BackupPostalCode", Kind.STRING, pattern = "\\d{5}"),
                FieldDef("$rows/RegionCode", Kind.STRING, pattern = "[A-Z]{3}"),
            ),
            rules = emptyList(),
            repeatableGroups = setOf(rows),
            declaredReps = mapOf(rows to 10_000),
        )

        val first = SeedGenerator(model)
        val firstOutput = record(first, rows, 2_000, 42L)
        val repeatedOutput = record(first, rows, 2_000, 42L)

        assertEquals(firstOutput, repeatedOutput, "prepared generation must preserve fixed-seed determinism")
        assertEquals(2, first.patternPreparationCount(), "equal sources across fields share one prepared sample")
        assertEquals(2, first.patternCompilationCount(), "2,000 rows must not trigger per-cell compilation")

        val independent = SeedGenerator(model)
        assertEquals(0, independent.patternCompilationCount(), "a new generator must not inherit a global cache")
        assertEquals(firstOutput, record(independent, rows, 2_000, 42L))
        assertEquals(2, independent.patternCompilationCount(), "each generator owns its distinct-pattern cache")
    }

    private fun record(generator: SeedGenerator, rows: String, count: Int, seed: Long): List<String> {
        val placements = mutableListOf<String>()
        generator.populate(
            recordingSink(placements),
            seed,
            rowsOf = { group, _, _ -> if (group == rows) count else 0 },
        )
        return placements
    }

    private fun recordingSink(out: MutableList<String>): PlacementSink = object : PlacementSink {
        override fun group(path: String, vararg reps: Int) {
            out += "G:$path:${reps.joinToString(",")}"
        }

        override fun field(path: String, value: String, vararg reps: Int) {
            out += "F:$path:${reps.joinToString(",")}:$value"
        }

        override fun emptyField(path: String, vararg reps: Int) {
            out += "E:$path:${reps.joinToString(",")}"
        }
    }
}
