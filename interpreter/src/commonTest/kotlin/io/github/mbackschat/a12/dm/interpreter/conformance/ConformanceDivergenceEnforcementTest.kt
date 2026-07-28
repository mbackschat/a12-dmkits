package io.github.mbackschat.a12.dm.interpreter.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IF136 — the ratified oracle lock (owner-decided 2026-07-19, graduated from IG76). When the kernel's two
 * evaluation strategies diverge on a legal input the normative anchor is **Groovy-dynamic**; a **static-Java**
 * codegen split is recorded in the case's `divergences` (CONFORMANCE-CORPUS-SPEC §11) and **never adopted**.
 * This pins the enforcement, not just the serialization (that is [CaseJsonTest]): the interpreter always asserts
 * the top-level anchor, and [overrideFor] — the pure selection every runner uses — hands a static-Java replay its
 * override while the anchor stays untouched. Multiplatform (JVM + JS): the same enforcement holds on both targets.
 *
 * No committed corpus case exercises the `divergences` path today (the tri-engine estate agrees everywhere — 0
 * java-static splits), so this is a machinery lock with a synthetic split; the first real captured divergence
 * inherits the same enforcement.
 */
class ConformanceDivergenceEnforcementTest {

    // A minimal own-domain model: a non-repeatable Order with one NUMBER Quantity and a rule that fires when it is 5.
    private val modelJson = """
        {
          "header": { "id": "ig76-oracle", "modelType": "document" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Order", "name": "Order",
            "Group": { "repeatability": 1, "elements": [
              { "type": "Field", "id": "/Order/Quantity", "name": "Quantity",
                "Field": { "fieldType": { "type": "NumberType", "NumberType": { "maxFractionalDigits": 0 } } } },
              { "type": "Rule", "id": "/Order/QtyIs5", "name": "QtyIs5",
                "Rule": { "errorCondition": "[Quantity] == 5", "errorCode": "QTY_IS_5",
                  "errorEntityRelPath": "../Quantity", "severity": "ERROR",
                  "errorMessage": [ {"locale":"en_US","text":"Quantity is 5."} ] } }
            ] } } ] } } }
    """.trimIndent()

    private val documentJson = """{"Order":{"Quantity":5}}"""

    private fun caseWith(expected: List<String>, staticJavaSplit: List<String>?) = ConformanceCase(
        meta = CaseMeta("ig76-oracle-enforce", "30.8.1", CaseSource.CURATED, listOf("§1")),
        modelRef = "models/ig76-oracle.json",   // unused: replay(case, modelJson) takes the resolved model text
        document = documentJson,
        op = Op.ValidateFull,
        expected = expected,
        divergences = staticJavaSplit?.let { listOf(Divergence("kernel-java-static", expected = it)) } ?: emptyList(),
    )

    @Test
    fun interpreterAssertsTheGroovyDynamicAnchorAndNeverAdoptsAStaticJavaDivergence() {
        // The interpreter's real output IS the dynamic anchor (it is the engine the corpus captures against).
        // `run` ignores the anchor fields, so a probe case supplies the document without pre-deciding expected.
        val anchor = InterpreterReplay.run(caseWith(emptyList(), null), modelJson).signatures
        assertTrue(anchor.isNotEmpty(), "sanity: [Quantity] == 5 fires, so there is an anchor to defend")

        // A recorded static-Java split that says something ELSE must not change the interpreter's verdict.
        val withSplit = caseWith(expected = anchor, staticJavaSplit = listOf("STATIC_ONLY|VALUE_ERROR|/Order[1]/Quantity"))
        assertEquals(
            InterpreterReplay.Verdict.PASS, InterpreterReplay.replay(withSplit, modelJson).verdict,
            "the interpreter matches the Groovy-dynamic anchor even with a static-Java divergence recorded — recorded, never adopted",
        )

        // And it genuinely asserts the anchor: the true output living ONLY in `divergences` cannot rescue a wrong anchor.
        val anchorWrong = caseWith(expected = listOf("STATIC_ONLY|VALUE_ERROR|/Order[1]/Quantity"), staticJavaSplit = anchor)
        assertEquals(
            InterpreterReplay.Verdict.FAIL, InterpreterReplay.replay(anchorWrong, modelJson).verdict,
            "a divergence override never becomes the interpreter's expectation — the interpreter only ever asserts the anchor",
        )
    }

    @Test
    fun overrideForSelectsTheStaticJavaSplitAndNothingElse() {
        val case = caseWith(expected = listOf("A|VALUE_ERROR|/x"), staticJavaSplit = listOf("B|VALUE_ERROR|/x"))
        assertEquals(listOf("B|VALUE_ERROR|/x"), case.overrideFor("kernel-java-static"), "the static-Java replay gets its override")
        assertNull(case.overrideFor("kernel-groovy-dynamic"), "the Groovy-dynamic anchor is never overridden")
        assertNull(case.overrideFor("interpreter"), "the interpreter never consults a divergence")
    }
}
