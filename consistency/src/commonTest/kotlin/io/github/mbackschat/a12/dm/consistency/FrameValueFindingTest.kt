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
            "name lengthened by lower-casing" to frame(info = """{"name":"XML\u0130"}"""),
            // Kernel 31.1.1 cuts the branch off by a case-blind suffix match, so a name whose lower-casing grows is cut
            // where it was written; 30.8.1 cut it at the lower-cased index, past its end, and could not build it.
            "name lengthened before its branch" to frame(info = """{"name":"\u0130\u0130\u0130_b","branch":"b"}"""),
            "branch beside no name" to frame(info = """{"branch":"v1"}"""),
            "zero offset" to frame(config = """{"timeZone":"GMT+0"}"""),
            "offset past 23 hours" to frame(config = """{"timeZone":"GMT+24"}"""),
            "zone in the wrong case" to frame(config = """{"timeZone":"europe/berlin"}"""),
            "older minor and patch" to frame(version = "29.3.99"),
            "newer patch" to frame(version = "29.4.1"),
            "two parts" to frame(version = "29.5"),
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
                "name lengthened by lower-casing" to listOf("MVK_INVALID_NAME_4_GENERATED_CODE"), "name lengthened before its branch" to emptyList(),
                "zero offset" to emptyList(), "offset past 23 hours" to listOf("MVK_INVALID_TIME_ZONE"),
                "zone in the wrong case" to listOf("MVK_INVALID_TIME_ZONE"), "older minor and patch" to emptyList(),
                "newer patch" to listOf(RK_MODEL_VERSION_INCOMPATIBLE), "two parts" to both, "major past an int" to unbuilt,
                "two plain characters" to unbuilt, "negative zero" to emptyList(), "downward range" to unbuilt,
                "a combined grapheme" to emptyList(),
            ),
            rows.mapValues { (row, json) ->
                // A12's check cannot run on these, so the checker completes no family and `checkRead` would refuse them.
                val report = if (row in PRE_EMPTED) check(ModelSource(json)) else checkRead(ModelSource(json))
                report.findings.filter { it.justifiesRejection }.map { it.code }
            },
        )
    }

    private companion object {
        val PRE_EMPTED = setOf("branch beside no name", "major past an int", "two plain characters",
            "downward range")
    }

    private fun frame(info: String = """{"name":"policy"}""", config: String = "{}", version: String = "29.4.0") =
        """{"header":{"id":"policy","modelVersion":"$version","locales":[{"code":"en_US"}]},
           "content":{"modelInfo":$info,"modelConfig":$config,"modelRoot":{"rootGroups":[{"type":"Group","id":"/Policy",
            "name":"Policy","Group":{"elements":[{"type":"Field","id":"/Policy/Ref","name":"Ref",
            "Field":{"fieldType":{"type":"StringType"}}}]}}]}}}"""
}
