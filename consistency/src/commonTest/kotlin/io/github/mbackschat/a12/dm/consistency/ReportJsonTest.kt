package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The report's JSON wire shape — the contract every non-JVM consumer reads, and the only thing the JS
 * surface exports.
 *
 * **A wire shape needs a lock precisely because nothing else fails when it changes.** Renaming a field or
 * dropping an empty array leaves every Kotlin test green and breaks every consumer, which is the shape of
 * defect a published format invites. So this pins the key set and the shapes a reader would otherwise have
 * to infer, rather than pinning whole documents — those would fail on every new finding and get relaxed.
 */
class ReportJsonTest {

    private fun report(json: String) = check(ModelSource(json))

    private fun parsed(json: String): JsonObject = Json.parseToJsonElement(report(json).toJson()).jsonObject

    private val sound = """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    /** A rule whose condition names an entity the model does not declare — one A12 finding, at a path. */
    private val broken = sound.replace("FieldFilled(Price)", "FieldFilled(NoSuchField)")

    @Test
    fun theTopLevelKeySetIsFixed() {
        assertEquals(
            setOf("modelId", "status", "complete", "findings", "diagnostics", "checksCompleted", "gaps"),
            parsed(sound).keys,
            "the top-level keys are the published contract — adding or renaming one is a consumer break",
        )
    }

    /**
     * Empty is not absent, and the distinction is the whole reason these keys are unconditional.
     *
     * A consumer reading `gaps` as missing-means-none would conclude the checker had nothing left to
     * disclose, which is the opposite of true for a checker that is deliberately incomplete.
     */
    @Test
    fun everyCollectionKeyIsPresentEvenWhenEmpty() {
        val soundReport = parsed(sound)
        assertEquals(emptyList(), soundReport["findings"]!!.jsonArray.toList())
        assertEquals(emptyList(), soundReport["diagnostics"]!!.jsonArray.toList())
        assertTrue(soundReport["checksCompleted"]!!.jsonArray.isNotEmpty(), "families ran, so this is not empty")
        assertTrue(soundReport["gaps"]!!.jsonArray.isNotEmpty(), "an incomplete checker always owes gaps")
    }

    @Test
    fun aFindingCarriesItsFullKeySetAndItsDerivedVerdict() {
        val finding = parsed(broken)["findings"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("code", "category", "severity", "explain", "at", "justifiesRejection", "corrective"),
            finding.keys,
        )
        assertEquals("MVK_INVALID_ENTITY", finding["code"]!!.jsonPrimitive.content)
        assertEquals("A12_CONSTRAINT", finding["category"]!!.jsonPrimitive.content)
        assertEquals("ERROR", finding["severity"]!!.jsonPrimitive.content)
        assertEquals("/Catalog/PriceRule", finding["at"]!!.jsonPrimitive.content)
        // The field a consumer cannot derive from severity alone: an AUTHORING_POLICY finding at ERROR is
        // deliberately not a rejection, so publishing severity without this one invites the wrong reading.
        assertEquals("true", finding["justifiesRejection"]!!.jsonPrimitive.content)
        // A12's own guidance for the code, projected from its Java owners rather than restated here. Its
        // two members answer different questions, which is why the member is an object and not a string.
        val corrective = finding["corrective"]!!.jsonObject
        assertEquals(setOf("fix", "meaning"), corrective.keys)
        assertTrue(corrective["fix"]!!.jsonPrimitive.content.isNotBlank())
    }

    /**
     * A code no A12 owner explains publishes `corrective: null` rather than omitting the member.
     *
     * Omission and absence would look identical to a consumer reading an older report, and the two mean
     * opposite things — "nobody explains this code" against "this producer does not carry guidance at all".
     */
    @Test
    fun aFindingWhoseCodeNoOwnerExplainsPublishesAnExplicitNull() {
        val finding = parsed(unbuildable)["findings"]!!.jsonArray.first().jsonObject
        assertEquals(CK_MODEL_NOT_BUILDABLE, finding["code"]!!.jsonPrimitive.content)
        assertEquals(true, finding["corrective"] is kotlinx.serialization.json.JsonNull, finding.toString())
    }

    /** A field with no `fieldType` at all, which draws this checker's own code rather than one of A12's. */
    private val unbuildable = """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{}}
          ]}}
         ]}}}
    """.trimIndent()

    /** The status is a WORD and never a boolean, because a checker that cannot say VALID has three states. */
    @Test
    fun theStatusIsSpelledOutAndPairedWithItsCompleteness() {
        assertEquals("INVALID", parsed(broken)["status"]!!.jsonPrimitive.content)
        assertEquals("INCONCLUSIVE", parsed(sound)["status"]!!.jsonPrimitive.content)
        // Never true today, and published anyway: a consumer must not read INCONCLUSIVE as "checked and
        // clean", and this is the field that says so.
        assertEquals("false", parsed(sound)["complete"]!!.jsonPrimitive.content)
    }

    /**
     * An absent `at` is JSON `null`, not a dropped key and not `""`.
     *
     * A model-level finding and one attached to the root would otherwise be indistinguishable, and the
     * source-level report below is exactly where that happens.
     */
    @Test
    fun anAbsentPathIsExplicitNull() {
        val unreadable = parsed("not json at all")
        assertEquals(JsonNull, unreadable["modelId"])
        assertTrue(unreadable["diagnostics"]!!.jsonArray.isNotEmpty(), "an unreadable source is a diagnostic")
        assertEquals(JsonNull, unreadable["diagnostics"]!!.jsonArray.first().jsonObject["at"])
    }

    /** An unreadable source yields a report rather than a raised exception — the JS surface promises this. */
    @Test
    fun anUnreadableSourceStillRendersAReport() {
        val unreadable = parsed("not json at all")
        assertEquals(emptyList(), unreadable["findings"]!!.jsonArray.toList())
        assertEquals(emptyList(), unreadable["checksCompleted"]!!.jsonArray.toList())
        assertEquals("INCONCLUSIVE", unreadable["status"]!!.jsonPrimitive.content)
    }
}
