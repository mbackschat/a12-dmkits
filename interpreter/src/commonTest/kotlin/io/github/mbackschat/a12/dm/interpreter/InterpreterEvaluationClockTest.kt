package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The published interpreter reads `Today` from an evaluation instant, as A12 reads it from `currentDateForTest` or,
 * when that is unset, from the current instant sampled per call (`RuntimeController`'s `new GregorianCalendar`
 * in the model's zone). Both are read as wall time in the model's zone, so 23:30 UTC on 3 February is already
 * the 4th in Berlin; the UTC model is the control that the zone, not the instant alone, decides the date. The pin is
 * years away from the run date, so a pin that leaked into the default would show.
 */
class InterpreterEvaluationClockTest {

    @Test
    fun aPinnedInstantIsReadInTheModelsZone() {
        val lateEvening = 981_243_000_000L // 2001-02-03T23:30:00Z
        assertEquals(
            mapOf("Europe/Berlin" to listOf("DUE_TODAY"), "UTC" to emptyList()),
            listOf("Europe/Berlin", "UTC").associateWith { zone ->
                val prepared = PreparedModel.loadExpandedJson(renewal(zone))
                val interpreter = prepared.createInterpreter(
                    InterpreterConfiguration.builder().evaluationInstant(lateEvening).build())
                interpreter.validateFull(due(prepared, "2001-02-04")).findings.map { it.code }
            },
        )
    }

    /** The positive control for the default: `Today` without a pin is the run's own date, and fires on it. */
    @Test
    fun theDefaultReadsTheCurrentInstant() {
        val prepared = PreparedModel.loadExpandedJson(renewal("UTC"))
        val interpreter = prepared.createInterpreter()
        assertEquals(emptyList(), interpreter.supportReport().unsupported.map { it.reason })
        // Read the date on both sides of the call, so a run across midnight cannot flake.
        val before = EvalClock.at(currentEpochMillis(), ModelTimeZone.UTC).today
        val fired = interpreter.validateFull(due(prepared, before)).findings.map { it.code }
        val after = EvalClock.at(currentEpochMillis(), ModelTimeZone.UTC).today
        if (before == after) assertEquals(listOf("DUE_TODAY"), fired)
    }

    /** A12 samples the current instant on every call, so one interpreter sees the date move between calls. */
    @Test
    fun theCurrentInstantIsSampledPerCall() {
        var now = 981_243_000_000L // 2001-02-03T23:30:00Z
        val prepared = PreparedModel.loadExpandedJson(renewal("UTC"))
        val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().instantSource { now }.build())
        val document = due(prepared, "2001-02-04")
        val first = interpreter.validateFull(document).findings.map { it.code }
        now += 3_600_000L
        assertEquals(listOf(emptyList(), listOf("DUE_TODAY")),
            listOf(first, interpreter.validateFull(document).findings.map { it.code }))
    }

    private fun due(prepared: PreparedModel, date: String) =
        prepared.documents.readDocument("""{"Renewal":{"DueOn":"$date"}}""")

    private fun renewal(zone: String) = """
        {
          "header":{"id":"renewal","modelVersion":"28.4.0","locales":[{"code":"en_US"}]},
          "content":{"modelInfo":{"name":"renewal"},
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
