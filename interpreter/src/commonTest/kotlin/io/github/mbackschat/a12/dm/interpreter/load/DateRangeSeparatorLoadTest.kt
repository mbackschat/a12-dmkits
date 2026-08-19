package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Multiplatform (JVM + JS) lock for the one DATE_RANGE declaration whose separator is legitimately EMPTY:
 * `MM`+`""`, an allowlisted `(format, separator)` pair (KERNEL-FINDINGS KF81), whose stored value the kernel
 * splits in the MIDDLE. The loader folded `""` into null — "unset" — which dropped the field out of
 * `ModelDef.rangeConfig` and disabled the range's whole config downstream: the formal check short-circuits to
 * "nothing to check" and a stored value never resolves as a range (IF306).
 *
 * An ABSENT key still loads as null. That is the distinction the fix keeps, and the reason a bare
 * `asText().ifEmpty { null }` was wrong rather than merely lossy: it made a legal declaration indistinguishable
 * from a missing one.
 */
class DateRangeSeparatorLoadTest {

    private val window = "/Cover/Window"

    /** The month-pair model with [separator] declared, or with the member ABSENT when it is null. */
    private fun model(separator: String?): String {
        val member = if (separator == null) "" else ", \"rangeSeparator\": \"$separator\""
        return """
            {
              "header": { "id": "cover" },
              "content": { "modelRoot": { "rootGroups": [ {
                "type": "Group", "id": "/Cover", "name": "Cover", "Group": { "elements": [
                  { "type": "Field", "id": "$window", "name": "Window", "Field": { "fieldType": {
                    "type": "DateRangeType",
                    "DateRangeType": { "format": "MM"$member } } } }
                ] } } ] } }
            }
        """.trimIndent()
    }

    @Test fun aDeclaredEmptySeparatorIsAValueNotUnset() {
        val def = ModelLoader.load(model(""))
        assertEquals("", def.fields.single().rangeSeparator, "the declared empty separator survives the load")
        assertEquals("", def.rangeSeparatorOf(window), "so the field keeps its range config")
        assertEquals("MM", def.dateLikeFormatOf(window), "the format rides the same config entry")
    }

    @Test fun anOrdinarySeparatorLoadsUnchanged() {
        val def = ModelLoader.load(model("/"))
        assertEquals("/", def.rangeSeparatorOf(window))
        assertEquals("MM", def.dateLikeFormatOf(window))
    }

    @Test fun anAbsentSeparatorStaysUnset() {
        val def = ModelLoader.load(model(null))
        assertNull(def.fields.single().rangeSeparator, "an absent member is genuinely unset")
        assertNull(def.rangeSeparatorOf(window), "and the field carries no range config")
    }
}
