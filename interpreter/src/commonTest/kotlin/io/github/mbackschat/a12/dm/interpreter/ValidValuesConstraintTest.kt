package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-engine, JVM+JS coverage of [ValidValues]' **constraint-aware** seeding (WI-7 / S2): a valid value stays
 * inside a field's declared NUMBER range and STRING length, and a `pattern` is either cheaply satisfied (and
 * always verified) or the field is left empty — so the real-corpus sweep exercises rules instead of tripping the
 * kernel's per-cell formal checks (which fire identically on both engines and only suppress the rule under test).
 */
class ValidValuesConstraintTest {

    private fun sample(field: FieldDef, seeds: Int = 64): List<String?> =
        (0 until seeds).map { ValidValues.value(field, listOf(1), Random(it.toLong())) }

    @Test
    fun numberStaysWithinDeclaredRange() {
        val f = FieldDef("/M/N", Kind.NUMBER, scale = 2, minValue = "10.00", maxValue = "20.00")
        val values = sample(f).map { assertNotNull(it) }
        assertTrue(values.all { it == "10.00" || it == "20.00" }, "out-of-range value seeded: $values")
        assertTrue(values.toSet().size == 2, "both bounds should be exercised across seeds: ${values.toSet()}")
    }

    @Test
    fun numberWithOnlyMinUsesTheBound() {
        val f = FieldDef("/M/N", Kind.NUMBER, scale = 0, minValue = "5")
        assertTrue(sample(f).all { it == "5" }, "min-only field must seed its lower bound")
    }

    @Test
    fun unconstrainedNumberKeepsRandomMagnitude() {
        val f = FieldDef("/M/N", Kind.NUMBER, scale = 0)   // signed by default → the legacy ±0..999 magnitude
        val ints = sample(f).map { assertNotNull(it).toInt() }
        assertTrue(ints.all { it in -999..999 }, "unconstrained number should stay in the legacy magnitude band")
        assertTrue(ints.toSet().size > 1, "unconstrained number should vary across seeds")
    }

    @Test
    fun stringFitsDeclaredLength() {
        val f = FieldDef("/M/S", Kind.STRING, minLength = 8, maxLength = 10)
        sample(f).forEach { v -> assertNotNull(v); assertTrue(v!!.length in 8..10, "length ${v.length} not in 8..10: '$v'") }
    }

    @Test
    fun satisfiablePatternIsGeneratedAndVerified() {
        val f = FieldDef("/M/Zip", Kind.STRING, pattern = "\\d{5}")
        sample(f).forEach { v -> assertNotNull(v, "a \\d{5} pattern must be satisfiable"); assertTrue(Regex("\\d{5}").matches(v!!)) }
    }

    @Test
    fun charClassPatternIsGenerated() {
        val f = FieldDef("/M/Code", Kind.STRING, pattern = "[A-Z]{3}")
        val v = ValidValues.value(f, listOf(1), Random(0))
        assertNotNull(v); assertTrue(Regex("[A-Z]{3}").matches(v!!))
    }

    @Test
    fun optionalGroupSuffixIsOmitted() {
        // required prefix + an optional repeated group — the sampler emits the prefix and drops the group.
        val f = FieldDef("/M/Path", Kind.STRING, pattern = "[a-z]{2}(-[a-z]{2})*")
        val v = ValidValues.value(f, listOf(1), Random(0))
        assertNotNull(v); assertTrue(Regex("[a-z]{2}(-[a-z]{2})*").matches(v!!))
    }

    @Test
    fun unsupportedPatternLeavesFieldEmpty() {
        // a required (non-zero) group is beyond the cheap sampler → leave-empty fallback (no wrong value emitted).
        val f = FieldDef("/M/X", Kind.STRING, pattern = "(ab|cd)+")
        assertNull(ValidValues.value(f, listOf(1), Random(0)), "an unsupported pattern must leave the field empty")
    }

    @Test
    fun noValueValidationSkipsPatternAndFillsString() {
        // noValueValidation waives ALL string checks, so a patterned field is filled as a plain string.
        val f = FieldDef("/M/X", Kind.STRING, pattern = "\\d{5}", noValueValidation = true)
        assertNotNull(ValidValues.value(f, listOf(1), Random(0)))
    }
}
