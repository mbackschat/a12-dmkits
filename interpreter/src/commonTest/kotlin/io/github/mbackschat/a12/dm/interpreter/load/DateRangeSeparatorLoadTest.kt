package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for how a DATE_RANGE declaration's separator loads when the member is EMPTY or ABSENT:
 * as `/`, the separator kernel 31.1.1 reads in both cases, because its expand copy omits an empty member and its range
 * reader defaults a missing one (KERNEL-FINDINGS KF658, measured at runtime by the adapter's
 * `EmptyDeclarationMemberDiffTest`). The field therefore keeps its range config either way.
 *
 * On kernel 30.8.1 the empty separator was a value of its own, split in the middle (IF306), and an absent one left the
 * field without a range config; this test locked both and was requalified on the 31.1.1 upgrade.
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

    @Test fun anEmptySeparatorLoadsAsTheSlash() {
        val def = ModelLoader.load(model(""))
        assertEquals("/", def.fields.single().rangeSeparator, "an empty member reads as the default separator")
        assertEquals("/", def.rangeSeparatorOf(window), "so the field keeps its range config")
        assertEquals("MM", def.dateLikeFormatOf(window), "the format rides the same config entry")
    }

    @Test fun anOrdinarySeparatorLoadsUnchanged() {
        val def = ModelLoader.load(model("/"))
        assertEquals("/", def.rangeSeparatorOf(window))
        assertEquals("MM", def.dateLikeFormatOf(window))
    }

    @Test fun anAbsentSeparatorLoadsAsTheSlash() {
        val def = ModelLoader.load(model(null))
        assertEquals("/", def.fields.single().rangeSeparator, "an absent member reads as the default separator")
        assertEquals("/", def.rangeSeparatorOf(window), "so the field keeps its range config")
    }
}
