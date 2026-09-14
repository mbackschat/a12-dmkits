package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's `.*` path-suffix gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ReferenceSegmentGateOrderLawsTest`. The two SILENT rows are the load-bearing ones:
 * the suffix's two characters also occur inside a starred UP-STEP, which is a different construct with a
 * different class, and a suffix on a name nothing declares belongs to the reference family instead.
 */
class HModelPathSuffixTest {

    private val suffix = "MVK_POINT_IN_A12_MODEL"

    /** A policy model whose one rule carries [condition]; `Insured` is the only group that does not repeat. */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy-paths","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"InsuredName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f2","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun path(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")

    @Test
    fun whichPathsCarryTheSuffix() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain star — the control"] = path("Sum(Claims*/Amount) > 0")
        actual["the suffix on a repeatable group"] = path("Sum(Claims.*/Amount) > 0")
        actual["the suffix on a NON-REPEATABLE group"] = path("Sum(Insured.*/InsuredName) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain star — the control"] = emptyList()
        expected["the suffix on a repeatable group"] = listOf(suffix)
        // A12 stops at the suffix gate and never reaches the landing site, so it names this class alone. The
        // checker names both because the second defect SURVIVES fixing the first: `Insured*` is still a star
        // on a group that does not repeat (`MVK_INVALID_WILDCARD`). That is the accumulation this checker
        // exists for, and the line it must not cross is the other one — a "defect" that exists only because
        // of the first.
        expected["the suffix on a NON-REPEATABLE group"] = listOf("MVK_INVALID_WILDCARD", suffix)

        assertEquals(expected, actual)
    }

    /**
     * The shapes it leaves alone, and why each one would be a wrong class rather than a harmless extra.
     *
     * A starred up-step spells the same two characters and A12 answers it with `MVK_INVALID_WILDCARD_REL`;
     * an undeclared segment answers `MVK_INVALID_ENTITY`, whichever suffix rides it, because resolution runs
     * first within the segment. Both rows went red against a first version that searched for the substring.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, Boolean>()
        actual["a starred up-step"] = suffix in path("Sum(..*/Amount) > 0")
        actual["a mid-path starred up-step"] = suffix in path("Sum(Claims/..*/Amount) > 0")
        actual["the suffix on an UNRESOLVABLE segment"] = suffix in path("Sum(NoSuchGroup.*/Amount) > 0")

        assertEquals(mapOf("a starred up-step" to false, "a mid-path starred up-step" to false,
            "the suffix on an UNRESOLVABLE segment" to false), actual)
    }
}
