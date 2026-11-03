package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A declaration whose envelope A12's reader cannot bind draws EXACTLY ONE finding, from ONE family.
 *
 * Several families read `DeclaredElement.body`, and every one of them has to decide what a `null` means. One per
 * element kind reports [unreadableEnvelope] (the rule, the computation and the group families) and the rest answer
 * with silence, which reads from any single call site as an inconsistency and is the convention this test exists to
 * pin: the tier is a property of the ELEMENT, not of each member, so one reporter per element kind is the whole
 * correct answer and the others staying quiet is what keeps a single defect from drawing several codes at one path.
 *
 * Without this, the convention lived only in whichever family a reader happened to open first. A count is
 * what states it, because it fails in both directions — a silent family that starts reporting, and the one
 * reporter falling silent.
 */
class UnreadableEnvelopeTest {

    /** Every envelope below is present and is a STRING, the shape A12's reader refuses outright. */
    private val source = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
        val report = checkRead(source)
        val byPath = report.findings.groupBy { it.at }.mapValues { (_, found) -> found.map { it.code } }

        val expected = mapOf<String?, List<String>>(
            "/Catalog/BadRule" to listOf(CK_MODEL_NOT_BUILDABLE),
            "/Catalog/BadCalc" to listOf(CK_MODEL_NOT_BUILDABLE),
            "/Catalog/BadGroup" to listOf(CK_MODEL_NOT_BUILDABLE),
        )
        assertEquals(
            expected,
            byPath,
            "an unbindable envelope is one finding per declaration — the other families read the same null and " +
                "report nothing, so that a single defect does not arrive as several codes at one path",
        )
    }

    /**
     * A GROUP's `null` envelope is not unbindable: kernel 31.1.1 reads it as an empty group, as it reads a missing one
     * ([KERNEL-FINDINGS KF3], `adapter.ElementBodyReadingDiffTest`), so it draws nothing from this tier.
     */
    @Test
    fun aGroupsNullEnvelopeIsAnEmptyGroup() {
        val withNull = ModelSource(source.json.replace(""""Group":"not an object"""", """"Group":null"""))
        assertEquals(emptyList(), checkRead(withNull).findings.filter { it.at == "/Catalog/BadGroup" }.map { it.code })
    }

    /** The control: the same model with every envelope an object draws none of these findings. */
    @Test
    fun aBindableEnvelopeDrawsNothingFromThisTier() {
        val sound = ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
        assertEquals(emptyList(), checkRead(sound).findings.map { it.code })
    }
}
