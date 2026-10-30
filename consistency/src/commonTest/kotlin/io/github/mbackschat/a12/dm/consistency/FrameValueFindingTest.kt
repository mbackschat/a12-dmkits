package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame values A12 judges by their text, on both runtimes (KERNEL-FINDINGS KF650). The Kernel-gate grid is
 * `adapter.laws.CheckerFrameValueAgreementTest`; this holds the JS checker to the same rows, one per law and arm, the
 * untouched frame being the control.
 */
class FrameValueFindingTest {

    @Test
    fun eachValueIsJudgedAsA12JudgesItsText() {
        val rows = linkedMapOf(
            "control" to frame(),
            "name begins with xml" to frame(info = """{"name":"XmlPolicy"}"""),
            "name without its branch is empty" to frame(info = """{"name":"_","branch":""}"""),
            "branch beside no name" to frame(info = """{"branch":"v1"}"""),
            "zero offset" to frame(config = """{"timeZone":"GMT+0"}"""),
            "offset past 23 hours" to frame(config = """{"timeZone":"GMT+24"}"""),
            "zone in the wrong case" to frame(config = """{"timeZone":"europe/berlin"}"""),
            "older minor and patch" to frame(version = "28.3.99"),
            "newer patch" to frame(version = "28.4.1"),
            "two parts" to frame(version = "28.5"),
            "major past an int" to frame(version = "99999999999.0.0"),
            "two plain characters" to frame(config = """{"supportedCharacters":["ab"]}"""),
            "negative zero" to frame(config = """{"supportedCharacters":[-0]}"""),
            "downward range" to frame(config = """{"supportedCharacters":["z-a"]}"""),
            "a combined grapheme" to frame(config = """{"supportedCharacters":["é"]}"""),
        )
        val unbuilt = listOf(CK_MODEL_NOT_BUILDABLE)
        val both = listOf(RK_MODEL_VERSION_PATTERN, RK_MODEL_VERSION_INCOMPATIBLE)
        assertEquals(
            mapOf(
                "control" to emptyList(), "name begins with xml" to listOf("MVK_INVALID_NAME_4_GENERATED_CODE"),
                "name without its branch is empty" to listOf("MVK_INVALID_NAME_4_GENERATED_CODE"), "branch beside no name" to unbuilt,
                "zero offset" to emptyList(), "offset past 23 hours" to listOf("MVK_INVALID_TIME_ZONE"),
                "zone in the wrong case" to listOf("MVK_INVALID_TIME_ZONE"), "older minor and patch" to emptyList(),
                "newer patch" to listOf(RK_MODEL_VERSION_INCOMPATIBLE), "two parts" to both, "major past an int" to unbuilt,
                "two plain characters" to unbuilt, "negative zero" to emptyList(), "downward range" to unbuilt,
                "a combined grapheme" to emptyList(),
            ),
            rows.mapValues { (row, json) ->
                // A12's check cannot run on these two, so the checker completes no family and `checkRead` would refuse them.
                val report = if (row in PRE_EMPTED) check(ModelSource(json)) else checkRead(ModelSource(json))
                report.findings.filter { it.justifiesRejection }.map { it.code }
            },
        )
    }

    private companion object {
        val PRE_EMPTED = setOf("branch beside no name", "major past an int")
    }

    private fun frame(info: String = """{"name":"policy"}""", config: String = "{}", version: String = "28.4.0") =
        """{"header":{"id":"policy","modelVersion":"$version","locales":[{"code":"en_US"}]},
           "content":{"modelInfo":$info,"modelConfig":$config,"modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy",
            "name":"Policy","Group":{"elements":[{"type":"Field","id":"/Policy/Ref","name":"Ref",
            "Field":{"fieldType":{"type":"StringType"}}}]}}]}}}"""
}
