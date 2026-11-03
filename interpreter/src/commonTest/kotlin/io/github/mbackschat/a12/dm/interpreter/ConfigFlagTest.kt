package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

/**
 * The interpreter's reading of a `boolean` model-config member, against the table the kernel gave
 * `rulekit.validate.laws.ShortNameFlagSpellingLawsTest` (kernel 30.8.1, 2026-09-12, KERNEL-FINDINGS KF289).
 *
 * **Every row is the kernel's answer, not a design choice**, which is what makes it a #1-rule (A) lock rather
 * than a formatting preference. Two families diverge from the obvious `asBoolean` reading and both change
 * evaluation: a capitalized `"True"` is ON to the kernel and OFF to `asBoolean`, so every bare cross-group
 * reference the kernel resolves through the model-wide tier would go unresolved here; and a fractional number
 * makes the model UNREADABLE to the kernel where `asBoolean` truncates and answers — `1.5` affirmatively.
 */
class ConfigFlagTest {

    @Test
    fun everySpellingIsReadTheWayTheKernelReadsIt() {
        val actual = mutableMapOf<String, ConfigFlag>()
        for (spelling in SPELLINGS) {
            actual[spelling] = configFlag(Json.parseToJsonElement(spelling))
        }
        actual["absent"] = configFlag(null)

        val expected = mapOf(
            "true" to ConfigFlag.ON,
            "false" to ConfigFlag.OFF,
            "\"true\"" to ConfigFlag.ON,
            "\"True\"" to ConfigFlag.ON,
            "\"TRUE\"" to ConfigFlag.ON,
            "\"false\"" to ConfigFlag.OFF,
            "\"False\"" to ConfigFlag.OFF,
            "\"FALSE\"" to ConfigFlag.OFF,
            "\"yes\"" to ConfigFlag.UNREADABLE,
            "\"1\"" to ConfigFlag.UNREADABLE,
            "\"\"" to ConfigFlag.UNREADABLE,
            "1" to ConfigFlag.ON,
            "2" to ConfigFlag.ON,
            "-1" to ConfigFlag.ON,
            "0" to ConfigFlag.OFF,
            // The kernel normalizes `-0` to the same node as `0`, so it cannot distinguish the two; this row
            // is the interpreter's own concern, because it decides on the literal's text where the sign
            // survives and a rule reading "every character is '0'" would answer ON.
            "-0" to ConfigFlag.OFF,
            "0.5" to ConfigFlag.UNREADABLE,
            "1.5" to ConfigFlag.UNREADABLE,
            "{}" to ConfigFlag.UNREADABLE,
            "[]" to ConfigFlag.UNREADABLE,
            "null" to ConfigFlag.UNREADABLE,
            // A string is trimmed as Java trims (KF569, both carriers); the empty, blank and `null` texts refuse
            // the reader on kernel 31.1.1, as does a JSON `null`.
            "\" true\"" to ConfigFlag.ON,
            "\"\\ttrue\"" to ConfigFlag.ON,
            "\"\\u00A0true\"" to ConfigFlag.UNREADABLE,
            "\" \"" to ConfigFlag.UNREADABLE,
            "\"null\"" to ConfigFlag.UNREADABLE,
            "\" null\"" to ConfigFlag.UNREADABLE,
            "\"NULL\"" to ConfigFlag.UNREADABLE,
            "1e0" to ConfigFlag.UNREADABLE,
            "absent" to ConfigFlag.OFF,
        )
        assertEquals(expected, actual, "how the interpreter reads each spelling of a boolean model-config member")
    }

    /**
     * An integer wider than any machine type still answers, because the rule reads the literal's TEXT.
     *
     * A reading that converted first would fall through to its unreadable branch on overflow and disable the
     * short-name tier for a model the kernel loads — unreachable from the table above, where every row fits.
     */
    @Test
    fun anIntegerTooLargeForAnyWidthIsStillAnInteger() {
        assertEquals(ConfigFlag.ON, configFlag(Json.parseToJsonElement("99999999999999999999999999")))
        assertEquals(ConfigFlag.OFF, configFlag(Json.parseToJsonElement("00000000000000000000000000")))
    }

    /**
     * The loaded model goes through that reading, so the table above is not a claim about an unreached
     * function. `"True"` is the row that was wrong before: the loader read it as off. `1.5` was wrong after that:
     * read as off, it evaluated a model A12 does not load, which now refuses as A12 does (KF577).
     */
    @Test
    fun theLoaderReadsTheShortNameFlagThroughIt() {
        assertEquals(true, ModelLoader.load(modelWithFlag("\"True\"")).fieldRefByShortNameAllowed)
        assertEquals(true, ModelLoader.load(modelWithFlag("2")).fieldRefByShortNameAllowed)
        assertEquals(false, ModelLoader.load(modelWithFlag("0")).fieldRefByShortNameAllowed)
        assertFailsWith<IllegalArgumentException> { ModelLoader.load(modelWithFlag("1.5")) }
    }

    private fun modelWithFlag(spelling: String) =
        """
        {"header":{"id":"catalog"},
         "content":{"modelConfig":{"fieldRefByShortNameAllowed":$spelling},
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"/Catalog","name":"Catalog","Group":{"elements":[
             {"type":"Field","id":"/Catalog/Note","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
           ]}}
          ]}}}
        """.trimIndent()

    private companion object {
        val SPELLINGS = listOf(
            "true", "false", "\"true\"", "\"True\"", "\"TRUE\"", "\"false\"", "\"False\"", "\"FALSE\"",
            "\"yes\"", "\"1\"", "\"\"", "1", "2", "-1", "0", "-0", "0.5", "1.5", "{}", "[]", "null",
            "\" true\"", "\"\\ttrue\"", "\"\\u00A0true\"", "\" \"", "\"null\"", "\" null\"", "\"NULL\"", "1e0",
        )
    }
}
