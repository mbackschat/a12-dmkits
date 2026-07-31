package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-engine, kernel-free, JVM+JS coverage of the [SeedGenerator] (INTERPRETER-PERFORMANCE.md "model-driven
 * seed generator"). It proves the generator builds a **best-effort candidate** in which, on this fixture,
 * required fields are filled → no spurious `mandatoryField`, in-format values → no formal errors; that an empty-strategy
 * triggers requiredness, and that generation is **deterministic for a fixed seed** (the property the fuzz net
 * relies on to materialize one instance into both engines). Kernel parity is the `:adapter` fuzz net's job.
 */
class SeedGeneratorTest {

    private val NAME = "/Shop/Items/Name"
    private val PRICE = "/Shop/Items/Price"
    private val STOCK = "/Shop/Items/Stock"

    private fun model(rules: List<RuleDef>) = ModelDef(
        listOf(
            FieldDef(NAME, Kind.STRING, required = Requiredness.ABSOLUTE),   // required under the repeatable Items
            FieldDef(PRICE, Kind.NUMBER, scale = 2),
            FieldDef(STOCK, Kind.NUMBER),
        ),
        rules,
        repeatableGroups = setOf("/Shop/Items"),
        declaredReps = mapOf("/Shop/Items" to 100),
    )

    private fun doc(m: ModelDef) = m.newDocument()

    private fun itemRows(count: Int): (String, List<Int>, kotlin.random.Random) -> Int =
        { group, _, _ -> if (group == "/Shop/Items") count else 0 }

    @Test
    fun fillsEveryRowWithValidValues() {
        // Rule fires once per row whose Price is filled; ValidValues fills every field → fires on every row,
        // and the required Name is filled → no mandatoryField, no formal errors.
        val m = model(listOf(RuleDef("/Shop/Items/HasPrice", "/Shop/Items", "FieldFilled(Price)", "HASPRICE", "../Price")))
        val d = doc(m)
        SeedGenerator(m).populate(d.asSink(), rowsOf = itemRows(7))
        val msgs = DmInterpreter(m).validateFull(d)
        assertEquals(7, msgs.count { it.code == "HASPRICE" }, "all 7 generated rows have a Price value")
        assertEquals(0, msgs.count { it.code == "mandatoryField" }, "the required Name is filled by ValidValues")
        assertEquals(0, msgs.count { it.code == "datumFormatFalsch" || it.code == "zahlHatUngueltigeZeichen" },
            "ValidValues are in-format → no formal errors")
    }

    @Test
    fun emptyStrategyTriggersRequiredness() {
        // A strategy that empties the required Name on every row → one mandatoryField per instantiated row.
        val m = model(emptyList())
        val d = doc(m)
        SeedGenerator(m).populate(d.asSink(), rowsOf = itemRows(5)) { field, reps, rnd ->
            if (field.path == NAME) null else ValidValues.value(field, reps, rnd)
        }
        val msgs = DmInterpreter(m).validateFull(d)
        assertEquals(5, msgs.count { it.code == "mandatoryField" }, "Name emptied on 5 rows → 5 mandatoryField")
    }

    @Test
    fun deterministicForSameSeed() {
        // Random row counts AND random values, but a fixed seed → identical document → identical validation.
        val m = model(listOf(RuleDef("/Shop/Items/HighPrice", "/Shop/Items", "[Price] > 50", "HI", "../Price")))
        fun validateSeed(seed: Long): List<String> {
            val d = doc(m)
            SeedGenerator(m).populate(d.asSink(), seed, rowsOf = { g, _, rnd -> if (g == "/Shop/Items") rnd.nextInt(3, 12) else 0 })
            return DmInterpreter(m).validateFull(d).map { it.code }.sorted()
        }
        assertEquals(validateSeed(42), validateSeed(42), "same seed → identical instance → identical fired codes")
    }

    @Test
    fun walksTheCompleteDeclaredGroupInventoryIncludingFieldlessGroups() {
        val m = ModelDef(
            fields = listOf(
                FieldDef("/Catalog/Named/Label", Kind.STRING),
                FieldDef("/Catalog/Lines/Code", Kind.STRING),
            ),
            rules = emptyList(),
            repeatableGroups = setOf("/Catalog/Lines"),
            declaredReps = mapOf("/Catalog/Lines" to 5),
            groupPaths = setOf(
                "/Catalog",
                "/Catalog/Empty",
                "/Catalog/Named",
                "/Catalog/Lines",
            ),
        )
        val d = doc(m)

        SeedGenerator(m).populate(d.asSink(), seed = 0L, rowsByGroup = emptyMap(), defaultRows = 1)

        val placements = d.placementsSnapshot()
        assertEquals(
            m.groupPaths,
            placements.filter { it.isGroup }.map { it.path }.toSet(),
            "a model-shaped seed must include declared containers even when they have no fields",
        )
        assertEquals(
            m.fields.map { it.path }.toSet(),
            placements.filterNot { it.isGroup }.map { it.path }.toSet(),
            "a model-shaped seed carries every field",
        )
    }

    @Test
    fun authoringPresetLeavesComputationOwnedTargetsPresentButEmpty() {
        val source = "/Permit/RequestedArea"
        val target = "/Permit/ReviewedArea"
        val m = ModelDef(
            fields = listOf(
                FieldDef(source, Kind.NUMBER),
                FieldDef(target, Kind.NUMBER),
            ),
            rules = emptyList(),
            computations = listOf(
                ComputationDef(target, "/Permit", "[RequestedArea]"),
            ),
            groupPaths = setOf("/Permit"),
        )
        val d = doc(m)

        SeedGenerator(m).populate(d.asSink(), seed = 0L, rowsByGroup = emptyMap(), defaultRows = 1)

        val fields = d.placementsSnapshot().filterNot { it.isGroup }.associateBy { it.path }
        assertEquals(setOf(source, target), fields.keys, "the authoring candidate still carries every field")
        assertEquals(null, fields.getValue(target).raw, "a computation target is present but engine-owned")
        assertEquals(false, fields.getValue(source).raw.isNullOrEmpty(), "an editable source field receives a useful value")
    }

    @Test
    fun validValuesHonorTheUniversalFifteenTotalDigitGate() {
        val rate = "/Permit/Rate"
        val m = ModelDef(
            fields = listOf(FieldDef(rate, Kind.NUMBER, scale = 14)),
            rules = emptyList(),
            groupPaths = setOf("/Permit"),
        )

        repeat(128) { seed ->
            val d = doc(m)
            SeedGenerator(m).populate(d.asSink(), seed.toLong(), emptyMap(), 1)
            val generated = d.placementsSnapshot().single { it.path == rate }.raw.orEmpty()
            assertTrue(
                generated.count(Char::isDigit) <= 15,
                "seed $seed generated '$generated', which exceeds A12's 15-total-digit gate",
            )
        }
    }
}
