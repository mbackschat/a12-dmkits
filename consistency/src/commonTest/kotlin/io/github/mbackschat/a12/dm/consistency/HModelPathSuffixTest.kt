package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite

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
    private fun model(condition: String): ModelSource = ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-paths","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
        )

    private fun codes(condition: String): List<String> = checkRead(model(condition)).findings.map { it.code }

    private fun path(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")

    @Test
    fun omittedPathGatesDoNotStopSelectedFamilies() {
        for ((path, family, expected) in listOf(
            Triple("Claims.*/Nope", CheckFamily.CONDITION_REFERENCES, "MVK_INVALID_ENTITY"),
            Triple("Insured.*/InsuredName", CheckFamily.REPETITION_WILDCARD_TARGET, "MVK_INVALID_WILDCARD"),
        )) {
            val prepared = prepare(assertIs<Admission.Admitted>(admit(model("FieldFilled(Reference) And Sum($path) > 0"))))
            val facts = prepared.tracked()
            val producers = CHECK_PRODUCERS.filter { family in it.completes }
            assertEquals(listOf(expected), executeChecks(facts, prepared, CheckCells.NONE, producers)
                .reportFindings(facts).map { it.code }, path)
        }
    }

    @Test
    fun pathActionsKeepTheirOwnerAndFullEditorExtent() {
        for ((path, expected) in listOf("Claims*/Amount" to null,
            "Claims.*/Amount" to suffix, "Insured.*/InsuredName" to suffix)) {
            val text = "FieldFilled(Reference) And Sum($path) > 0"
            val facts = prepare(assertIs<Admission.Admitted>(admit(model(text)))).tracked()
            val rule = facts.declarations().single { it.declaredKind == DeclaredKind.RULE }
            val execution = assertNotNull(facts.conditionExecution(rule))
            val local = facts.pathContributions(rule, execution)
            val selected = execution.selected(local.map { it.contribution }).toSet()
            val contributions = local.filter { it.contribution in selected }
            assertEquals(listOfNotNull(expected), contributions.map { it.contribution.finding.code }, path)
            for ((family, contribution) in contributions) {
                assertEquals(CheckFamily.H_MODEL_PATH_SUFFIX, family)
                assertEquals(rule.key, contribution.owner)
                val step = assertIs<PathCheckSite>(contribution.site).step
                assertEquals(SyntaxSite.Kind.PATH_STEP, step.kind)
                assertEquals(true, step in execution.recognition.sites)
                assertEquals(TextSpan("errorCondition", text.indexOf(path), text.indexOf(path) + path.length),
                    contribution.finding.span)
            }
        }
    }

    @Test
    fun whichPathsCarryTheSuffix() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain star — the control"] = path("Sum(Claims*/Amount) > 0")
        actual["the suffix on a repeatable group"] = path("Sum(Claims.*/Amount) > 0")
        actual["the suffix on a NON-REPEATABLE group"] = path("Sum(Insured.*/InsuredName) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain star — the control"] = emptyList()
        expected["the suffix on a repeatable group"] = listOf(suffix)
        // R5-PE confirms that the suffix gate stops before the wildcard landing gate.
        expected["the suffix on a NON-REPEATABLE group"] = listOf(suffix)

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
