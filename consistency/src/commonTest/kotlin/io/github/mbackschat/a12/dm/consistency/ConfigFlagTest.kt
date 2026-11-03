package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The checker's reading of a `boolean` model-config member, against the table the Kernel gave
 * `rulekit.validate.laws.ShortNameFlagSpellingLawsTest` (kernel 30.8.1, 2026-09-12).
 *
 * **Every row is the Kernel's answer, not a design choice**, and the two divergent families are the reason
 * the table exists rather than a `toBooleanStrictOrNull` call. A capitalized `"True"` is ON to A12 and OFF to
 * the obvious reading, which silently disables the model-wide short-name tier and reports
 * `MVK_INVALID_ENTITY` for references A12 resolves. A fractional number makes the model UNREADABLE where the
 * obvious reading truncates and answers — affirmatively for `1.5`.
 */
class ConfigFlagTest {

    @Test
    fun everySpellingIsReadTheWayA12ReadsIt() {
        val actual = mutableMapOf<String, ConfigFlag>()
        for (spelling in SPELLINGS) {
            actual[spelling] = configFlag(parseValue(spelling))
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
            // Jackson normalizes `-0` to the same node as `0`, so the Kernel cannot distinguish the two and
            // this row is the CHECKER's own concern: it decides on the literal's text, where the sign
            // survives, and a rule reading "all characters are '0'" would answer ON for it.
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
        assertEquals(expected, actual, "how the checker reads each spelling of a boolean model-config member")
    }

    /**
     * An integer wider than any machine type still answers, because the rule reads the literal's TEXT.
     *
     * A reading that converted first would fall through to its unreadable branch on overflow and refuse a
     * model A12 loads — the direction no consumer can detect, and unreachable from the table above because
     * every row there fits an `Int`.
     */
    @Test
    fun anIntegerTooLargeForAnyWidthIsStillAnInteger() {
        assertEquals(ConfigFlag.ON, configFlag(parseValue("99999999999999999999999999")))
        assertEquals(ConfigFlag.OFF, configFlag(parseValue("00000000000000000000000000")))
    }

    /** The flag as a model reads it, so the table above is not a claim about an unreached function. */
    @Test
    fun theModelConfigMemberGoesThroughThatReading() {
        assertEquals(true, preparedWithFlag("\"True\"").fieldRefByShortNameAllowed)
        assertEquals(false, preparedWithFlag("0").fieldRefByShortNameAllowed)

        val refused = preparedWithFlag("1.5")
        assertEquals(false, refused.fieldRefByShortNameAllowed)
        assertEquals(
            listOf("`content.modelConfig.fieldRefByShortNameAllowed` holds `1.5`"),
            refused.unbindableBooleans,
            "an unreadable spelling is carried as the source refusal it is rather than silently read as off",
        )
    }

    private fun preparedWithFlag(spelling: String) = prepare(
        admit(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelConfig":{"fieldRefByShortNameAllowed":$spelling},
                  "modelRoot":{"rootGroups":[
                   {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[]}}
                  ]}}}
                """.trimIndent(),
            ),
        ) as Admission.Admitted,
    )

    private companion object {
        /** The spelling as it stands in a model file, parsed rather than hand-built into a node. */
        fun parseValue(json: String) = Json.parseToJsonElement(json)

        val SPELLINGS = listOf(
            "true", "false", "\"true\"", "\"True\"", "\"TRUE\"", "\"false\"", "\"False\"", "\"FALSE\"",
            "\"yes\"", "\"1\"", "\"\"", "1", "2", "-1", "0", "-0", "0.5", "1.5", "{}", "[]", "null",
            "\" true\"", "\"\\ttrue\"", "\"\\u00A0true\"", "\" \"", "\"null\"", "\" null\"", "\"NULL\"", "1e0",
        )
    }
}
