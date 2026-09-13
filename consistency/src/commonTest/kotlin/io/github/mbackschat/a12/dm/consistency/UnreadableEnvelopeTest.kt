package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A declaration whose envelope A12's reader cannot bind draws EXACTLY ONE finding, from ONE family.
 *
 * Eight families read `DeclaredElement.body`, and every one of them has to decide what a `null` means. Six
 * answer with silence and two report [unreadableEnvelope], which reads from any single call site as an
 * inconsistency and is the convention this test exists to pin: the tier is a property of the ELEMENT, not of
 * each member, so one reporter per element kind is the whole correct answer and the other six staying quiet
 * is what keeps a single defect from drawing six codes at one path.
 *
 * Without this, the convention lived only in whichever family a reader happened to open first. A count is
 * what states it, because it fails in both directions — a silent family that starts reporting, and the one
 * reporter falling silent.
 */
class UnreadableEnvelopeTest {

    /** Every envelope below is present and is a STRING, the shape A12's reader refuses outright. */
    private val source = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"BadRule","Rule":"not an object"},
            {"type":"Computation","id":"c0","name":"BadCalc","Computation":"not an object"},
            {"type":"Group","id":"g1","name":"BadGroup","Group":"not an object"}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun eachUnbindableDeclarationDrawsOneFindingAndNoMore() {
        val report = check(source)
        val byPath = report.findings.groupBy { it.at }.mapValues { (_, found) -> found.map { it.code } }

        val expected = mapOf<String?, List<String>>(
            "/Catalog/BadRule" to listOf(CK_MODEL_NOT_BUILDABLE),
            "/Catalog/BadCalc" to listOf(CK_MODEL_NOT_BUILDABLE),
        )
        assertEquals(
            expected,
            byPath,
            "an unbindable envelope is one finding per declaration — six families read the same null and " +
                "report nothing, so that a single defect does not arrive as six codes at one path",
        )
    }

    /**
     * A GROUP's unbindable envelope is reported by nobody, and that is the measured state rather than a
     * decision: no family owns the group carrier at this tier. It is silence, which is the safe direction —
     * the model is genuinely unloadable, so the omission understates rather than misreports — and the report
     * says so through the same gap that covers every unjudged container member.
     */
    @Test
    fun aGroupsUnbindableEnvelopeIsNotYetReported() {
        val report = check(source)
        assertEquals(emptyList(), report.findings.filter { it.at == "/Catalog/BadGroup" }.map { it.code })
    }

    /** The control: the same model with every envelope an object draws none of these findings. */
    @Test
    fun aBindableEnvelopeDrawsNothingFromThisTier() {
        val sound = ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"GoodRule","Rule":{
                  "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
                  "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        assertIs<Admission.Admitted>(admit(sound))
        assertEquals(emptyList(), check(sound).findings.map { it.code })
    }
}
