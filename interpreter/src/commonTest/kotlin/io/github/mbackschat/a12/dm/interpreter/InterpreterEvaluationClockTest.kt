package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The published interpreter reads `Today` from an evaluation instant, as A12 reads it from `currentDateForTest` or,
 * when that is unset, from the current instant sampled per call (`RuntimeController`'s `new GregorianCalendar`
 * in the model's zone). Both are read as wall time in the model's zone, so 22:30 UTC on 20 October is already
 * the 21st in Berlin; the UTC model is the control that the zone, not the instant alone, decides the date.
 */
class InterpreterEvaluationClockTest {

    @Test
    fun aPinnedInstantIsReadInTheModelsZone() {
        val lateEvening = 1_792_535_400_000L // 2026-10-20T22:30:00Z
        assertEquals(
            mapOf("Europe/Berlin" to listOf("DUE_TODAY"), "UTC" to emptyList()),
            listOf("Europe/Berlin", "UTC").associateWith { zone ->
                val prepared = PreparedModel.loadExpandedJson(renewal(zone))
                val interpreter = prepared.createInterpreter(
                    InterpreterConfiguration.builder().evaluationInstant(lateEvening).build())
                interpreter.validateFull(prepared.documents.readDocument("""{"Renewal":{"DueOn":"2026-10-21"}}"""))
                    .findings.map { it.code }
            },
        )
    }

    /** Without a pinned instant `Today` is the current date, so a rule using it is supported and evaluates. */
    @Test
    fun theDefaultReadsTheCurrentInstant() {
        val prepared = PreparedModel.loadExpandedJson(renewal("UTC"))
        val interpreter = prepared.createInterpreter()
        assertEquals(emptyList(), interpreter.supportReport().unsupported.map { it.reason })
        assertEquals(emptyList(), interpreter.validateFull(
            prepared.documents.readDocument("""{"Renewal":{"DueOn":"1999-01-01"}}""")).findings.map { it.code })
    }

    private fun renewal(zone: String) = """
        {
          "header":{"id":"renewal"},
          "content":{
            "modelConfig":{"timeZone":"$zone"},
            "modelRoot":{"rootGroups":[{
              "type":"Group","id":"/Renewal","name":"Renewal",
              "Group":{"elements":[{
                "type":"Field","id":"/Renewal/DueOn","name":"DueOn",
                "Field":{"fieldType":{"type":"DateType","DateType":{"format":"yyyy-MM-dd"}}}
              },{
                "type":"Rule","id":"/Renewal/DueToday","name":"DueToday",
                "Rule":{"errorEntityRelPath":"../DueOn","errorCode":"DUE_TODAY",
                  "errorCondition":"FieldFilled(DueOn) And [DueOn] == Today","severity":"ERROR",
                  "errorMessage":[{"locale":"en_US","text":"The policy renews today."}]}
              }]}
            }]}
          }
        }
    """.trimIndent()
}
