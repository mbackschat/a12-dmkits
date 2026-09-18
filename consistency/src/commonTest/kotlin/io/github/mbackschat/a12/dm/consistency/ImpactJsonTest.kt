package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The impact report's JSON wire shape — slice J across the boundary.
 *
 * **A wire shape needs a lock precisely because nothing else fails when it changes**, which is
 * [ReportJsonTest]'s reason and holds identically here: renaming a key or dropping an empty array leaves
 * every Kotlin test green and breaks every consumer. So this pins the key sets and the shapes a reader would
 * have to infer, rather than whole documents — those fail on every new finding and get relaxed.
 *
 * **The one shape with a real decision in it is `on`.** `DependencyOn` is a sealed interface whose arms carry
 * differently-named members (a path, an id, a name), so a per-arm key set would make a consumer switch on the
 * kind before it could even read the value. `{kind, value}` keeps the key set fixed across arms and puts the
 * type in `kind`, so a consumer that only displays the dependency needs no switch and one that acts on it
 * still has the discriminator.
 */
class ImpactJsonTest {

    private fun parsed(edited: String): JsonObject =
        Json.parseToJsonElement(impactOf(ModelSource(BASE), ModelSource(edited)).toJson()).jsonObject

    private fun impacts(edited: String): List<JsonObject> =
        parsed(edited)["impacts"]!!.jsonArray.map { it.jsonObject }

    private fun kindOf(impact: JsonObject): String = impact["on"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    /** The one impact matching [select], with every impact in the message when it is not exactly one. */
    private fun one(edited: String, select: (JsonObject) -> Boolean): JsonObject {
        val matching = impacts(edited).filter(select)
        assertEquals(1, matching.size, "expected one matching impact, got ${impacts(edited)}")
        return matching.single()
    }

    @Test
    fun theTopLevelKeySetIsFixed() {
        assertEquals(
            setOf("affected", "delta", "impacts", "edit", "gaps"),
            parsed(RENAMED).keys,
            "the top-level keys are the published contract — adding or renaming one is a consumer break",
        )
    }

    @Test
    fun theDeltaKeySetIsFixed() {
        assertEquals(
            setOf(
                "added", "removed", "modified", "typeDefinitionsAdded", "typeDefinitionsRemoved",
                "shortNamesMoved", "shortNameFallbackChanged",
            ),
            parsed(RENAMED)["delta"]!!.jsonObject.keys,
        )
    }

    @Test
    fun anImpactKeySetIsFixedAndItsDependencyCarriesAKindAndAValue() {
        val impact = one(RENAMED) { it["at"] == JsonPrimitive(RULE) && it["member"] == CONDITION }

        assertEquals(setOf("at", "on", "member", "written", "reason"), impact.keys)
        assertEquals(setOf("kind", "value"), impact["on"]!!.jsonObject.keys)
        assertEquals(JsonPrimitive("ELEMENT"), impact["on"]!!.jsonObject["kind"])
        assertEquals(JsonPrimitive("/Order/Code"), impact["on"]!!.jsonObject["value"])
        assertEquals(JsonPrimitive("REFERENCES_A_CHANGED_ELEMENT"), impact["reason"])
    }

    /**
     * The short-name arm renders its own kind — the one dependency with no path, so a consumer reading `value`
     * as a path would be wrong exactly here.
     */
    @Test
    fun aShortNameDependencyIsDistinguishableFromAnElementOne() {
        val impact = one(RENAMED) { it["at"] == JsonPrimitive(COMPUTATION) && kindOf(it) == "SHORT_NAME" }

        assertEquals(JsonPrimitive("Code"), impact["on"]!!.jsonObject["value"])
        assertEquals(JsonPrimitive("RESOLVES_A_MOVED_SHORT_NAME"), impact["reason"])
    }

    /**
     * One declaration carries one impact PER DEPENDENCY, which is why `affected` has to de-duplicate.
     *
     * The computation reads `Code` by bare name, so the analysis records a dependency on the NAME and one on
     * what the name currently resolves to — two rows at one `at`, with two kinds. A consumer rendering
     * `impacts` as a list of declarations would show it twice; that is the rule `affected` exists to apply,
     * and this row is what makes the de-duplication assertion beside it mean something rather than being
     * trivially true on a one-row-per-declaration report.
     */
    @Test
    fun oneDeclarationCanCarrySeveralDependencies() {
        val atComputation = impacts(RENAMED).filter { it["at"] == JsonPrimitive(COMPUTATION) }

        assertEquals(
            listOf("ELEMENT", "SHORT_NAME"),
            atComputation.map { kindOf(it) }.sorted(),
            "a bare reference depends on the name AND on its current referent: $atComputation",
        )
    }

    @Test
    fun aTypeDefinitionDependencyRendersTheThirdKind() {
        val report = parsed(WITHOUT_TYPE_DEF)

        assertEquals(listOf("Money"), report["delta"]!!.jsonObject["typeDefinitionsRemoved"]!!.jsonArray
            .map { it.jsonPrimitive.content })
        val impact = report["impacts"]!!.jsonArray.map { it.jsonObject }
            .single { it["on"]!!.jsonObject["kind"]!!.jsonPrimitive.content == "TYPE_DEFINITION" }
        assertEquals(JsonPrimitive("Money"), impact["on"]!!.jsonObject["value"])
        assertEquals(JsonPrimitive(FIELD_ON_TYPE_DEF), impact["at"])
        assertEquals(JsonPrimitive("BINDS_A_CHANGED_TYPE_DEFINITION"), impact["reason"])
    }

    /**
     * `affected` is DERIVED and published anyway, because deriving it is a rule a consumer would get wrong.
     *
     * It is `impacts` mapped to `at` and de-duplicated *while keeping the order they were reached in* — a
     * consumer reaching for a set would lose that order, and one reaching for a plain map would show the same
     * declaration once per dependency. The asserted property is the relation to `impacts`, not a literal list,
     * so the row cannot pass on a field that is present and constantly empty.
     */
    @Test
    fun theAffectedListIsPublishedAndIsTheDeduplicatedImpactOrder() {
        val report = parsed(RENAMED)
        val affected = report["affected"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(
            report["impacts"]!!.jsonArray.map { it.jsonObject["at"]!!.jsonPrimitive.content }.distinct(),
            affected,
        )
        assertTrue(RULE in affected && COMPUTATION in affected, "both carriers are reached: $affected")
    }

    /**
     * The nested edit is the WHOLE edit shape, not a summary of it.
     *
     * `impactOf` delegates its verdict half to `checkEdit` rather than re-deriving it, so republishing a
     * reduced version here would invent a second shape for facts that already have one — and a consumer
     * moving between `checkEditJson` and this call would need two readers.
     */
    @Test
    fun theNestedEditIsTheSameShapeCheckEditPublishes() {
        val nested = parsed(RENAMED)["edit"]!!.jsonObject

        assertEquals(
            Json.parseToJsonElement(checkEdit(ModelSource(BASE), ModelSource(RENAMED)).toJson()).jsonObject,
            nested,
            "the edit half must be byte-identical to what checkEdit publishes",
        )
    }

    private companion object {
        const val RULE = "/Order/CodeRequired"
        val CONDITION = JsonPrimitive("errorCondition")
        const val COMPUTATION = "/Order/Lines/LineLabel"
        const val FIELD_ON_TYPE_DEF = "/Order/Tier"

        private val BASE = """
            {"header":{"id":"orders","locales":[{"code":"en_US"}]},
             "content":{"typeDefinitions":[
               {"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}],
              "modelConfig":{"fieldRefByShortNameAllowed":true},
              "modelRoot":{"rootGroups":[
               {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                 {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                 {"type":"Field","id":"f-tier","name":"Tier","Field":{"fieldType":{
                   "type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}}},
                 {"type":"Group","id":"g-lines","name":"Lines","Group":{"repeatability":20,"elements":[
                   {"type":"Field","id":"f-label","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
                   {"type":"Computation","id":"c-label","name":"LineLabel","Computation":{
                     "computedFieldRelPath":"../Label","computationAlternatives":[{"operation":"Code"}],
                     "errorMessage":[{"locale":"en_US","text":"the line label"}]}}
                 ]}},
                 {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                   "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code",
                   "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
               ]}}
              ]}}}
        """.trimIndent()

        /** The field the rule names and the computation reads by bare name, renamed. */
        private val RENAMED = BASE.replace(""""name":"Code"""", """"name":"Ref"""")

        /** The type definition `/Order/Tier` binds, removed. */
        private val WITHOUT_TYPE_DEF = BASE.replace(
            """{"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}""",
            "",
        )
    }
}
