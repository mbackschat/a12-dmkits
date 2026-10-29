package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's reading of `content.modelInfo.baseYear`, against the rows A12 gave
 * `adapter.laws.ModelTextReadingDiffTest` (KF591, KF593): a value A12's reader binds is a base year, the empty
 * text and the text `null` are none, and anything else leaves no model, which the checker reports as
 * [CK_MODEL_NOT_BUILDABLE]. Here so Node reads the spellings as the JVM does; the differential holds A12's side.
 */
class BaseYearBindingTest {

    @Test
    fun anUnbindableBaseYearIsCarriedAndEveryOtherSpellingIsNot() {
        val spellings = listOf("\"2024\"", "\" 2024 \"", "2024.7", "\"\"", "\"null\"", "null",
            "\"NULL\"", "\"2024.0\"", "\"\u00A02024\"", "2147483647.5", "{}", "[]", "true")
        val actual = spellings.associateWith { prepared(it).unbindableBaseYear }
        assertEquals(
            mapOf("\"2024\"" to null, "\" 2024 \"" to null, "2024.7" to null, "\"\"" to null, "\"null\"" to null,
                "null" to null, "\"NULL\"" to "\"NULL\"", "\"2024.0\"" to "\"2024.0\"",
                "\"\u00A02024\"" to "\"\u00A02024\"", "2147483647.5" to "2147483647.5", "{}" to "{}", "[]" to "[]",
                "true" to "true"),
            actual,
        )
    }

    @Test
    fun anUnbindableBaseYearIsReportedAsTheSourceRefusalItIs() {
        val findings = checkRead(source("\"NULL\"")).findings.filter { it.code == CK_MODEL_NOT_BUILDABLE }
        assertEquals(1, findings.size, "one model-level refusal: $findings")
        assertEquals(true, findings.single().explain.startsWith("`content.modelInfo.baseYear` holds `\"NULL\"`"),
            findings.single().explain)
        assertEquals(emptyList(), checkRead(source("\"2024\"")).findings.filter { it.code == CK_MODEL_NOT_BUILDABLE })
    }

    private fun prepared(spelling: String) = prepare(admit(source(spelling)) as Admission.Admitted)

    private fun source(spelling: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"cover","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"baseYear":$spelling},
          "modelRoot":{"rootGroups":[
           {"type":"Group","id":"g0","name":"Cover","Group":{"elements":[]}}
          ]}}}
        """.trimIndent(),
    )
}
