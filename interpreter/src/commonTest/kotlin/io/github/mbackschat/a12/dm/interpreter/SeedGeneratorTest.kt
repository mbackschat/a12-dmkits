package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-engine, kernel-free, JVM+JS coverage of the [SeedGenerator] (INTERPRETER-PERFORMANCE.md "model-driven
 * seed generator"). It proves the generator builds **valid-by-construction** documents (required fields
 * filled → no spurious `mandatoryField`; in-format values → no formal errors), that an empty-strategy
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

    private fun doc(m: ModelDef) = Document(m.kinds, m.declaredReps, m.unsignedFields, m.rangeConfig)

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
}
