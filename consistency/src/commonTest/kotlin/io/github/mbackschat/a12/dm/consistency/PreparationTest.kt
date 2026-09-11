package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * B3's minimum for references: the declared element set and the one model-config flag reference resolution
 * needs, both read from the model rather than reconstructed — plus B2's two retention cases.
 *
 * A12 declares an element's kind twice, as the `type` discriminator and as the key of the envelope holding
 * the kind's payload; the Kernel's `ElementDeserializer` reads `type` first and looks the envelope up BY its
 * value, so `type` is the discriminator and envelope-by-elimination is not the mechanism.
 */
class PreparationTest {

    private fun prepared(json: String): PreparedModel =
        prepare(assertIs<Admission.Admitted>(admit(ModelSource("m", json))))

    /** Prepare a model whose `content.modelRoot.rootGroups` is exactly [rootGroups]. */
    private fun preparedRoots(rootGroups: String): PreparedModel = prepared(model(rootGroups))

    private fun model(rootGroups: String, modelConfig: String = "") =
        """{"header":{"id":"m"},"content":{$modelConfig"modelRoot":{"rootGroups":[$rootGroups]}}}"""

    private val twoLevel = model(
        """
        {"type":"Group","id":"/Order","name":"Order","Group":{"repeatability":1,"elements":[
          {"type":"Field","id":"/Order/Code","name":"Code","Field":{}},
          {"type":"Group","id":"/Order/Lines","name":"Lines","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"/Order/Lines/Qty","name":"Qty","Field":{}}
          ]}}
        ]}}
        """,
    )

    @Test
    fun preparationCollectsEveryDeclaredElementWithItsPathAndAuthoredKind() {
        val prepared = prepared(twoLevel)

        assertEquals(
            listOf("/Order", "/Order/Code", "/Order/Lines", "/Order/Lines/Qty"),
            prepared.elements.map { it.path },
        )
        assertEquals(listOf("Group", "Field", "Group", "Field"), prepared.elements.map { it.kind })
        assertEquals(emptyList(), prepared.diagnostics)
    }

    /**
     * The discriminating case, and it runs the opposite way from the obvious reading: `id` is an OPAQUE
     * identifier and the canonical path is the join of `name` segments — the Kernel's own `getFullName()`
     * (`DocumentModelVisitorWithFullNames:56` appends `getName()`), which is what a condition references and
     * what `ModelIndex` keys on. Real models carry hash ids; reading `id` as a path resolved ~0% of a foreign
     * model's references before `ModelIndex` was re-keyed by the name-path (KERNEL-INTEGRATION-NOTES,
     * "`getId()` is an *opaque identifier*, NOT the path"). This fixture reuses the shape of the committed
     * counterexample `src/test/resources/models/idmismatch.dm.json`, whose absence from a fixture sweep is
     * exactly what once made the two readings indistinguishable.
     */
    @Test
    fun theDeclaredPathIsTheJoinOfNamesAndNotTheOpaqueId() {
        val prepared = prepared(
            model(
                """
                {"type":"Group","id":"3b16e188fc65d64190ea4482a0336341","name":"Catalog","Group":{"elements":[
                  {"type":"Field","id":"b4c8890281890d66c079e5ccb2ae0b7a","name":"Sku","Field":{}},
                  {"type":"Group","id":"914eee50e04ba99a659a9ffa3661433d","name":"Warehouse","Group":{"elements":[
                    {"type":"Field","id":"5f0d1c2b3a49586778695a4b3c2d1e0f","name":"OnHand","Field":{}}
                  ]}}
                ]}}
                """,
            ),
        )

        assertEquals(
            listOf("/Catalog", "/Catalog/Sku", "/Catalog/Warehouse", "/Catalog/Warehouse/OnHand"),
            prepared.elements.map { it.path },
        )
        assertEquals("b4c8890281890d66c079e5ccb2ae0b7a", prepared.elements[1].id, "the id is kept, not discarded")
    }

    /** Two root groups are legal, and preparing only the first would silently empty half the fact base. */
    @Test
    fun everyRootGroupIsPrepared() {
        val prepared = preparedRoots(
            """
            {"type":"Group","id":"/Header","name":"Header","Group":{"elements":[
              {"type":"Field","id":"/Header/Ref","name":"Ref","Field":{}}
            ]}},
            {"type":"Group","id":"/Body","name":"Body","Group":{"elements":[
              {"type":"Field","id":"/Body/Total","name":"Total","Field":{}}
            ]}}
            """,
        )

        assertEquals(listOf("/Header", "/Header/Ref", "/Body", "/Body/Total"), prepared.elements.map { it.path })
    }

    /**
     * The kind envelope is optional: `ElementDeserializer` default-constructs the element when the envelope
     * keyed by `type` is absent, so a declaration carrying only its discriminator and identity is one the
     * Kernel accepts and the checker must therefore see too.
     */
    @Test
    fun anElementWithoutItsKindEnvelopeIsStillADeclaredElement() {
        val prepared = preparedRoots(
            """{"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Field","id":"/Order/Code","name":"Code"}
            ]}}""",
        )

        assertEquals(listOf("/Order", "/Order/Code"), prepared.elements.map { it.path })
        assertEquals(emptyList(), prepared.diagnostics)
    }

    /**
     * B2, "a parseable invalid declaration remains inspectable": the whole authored node is retained, so a
     * condition the grammar will reject is still there to be diagnosed and located — and so are the members
     * a per-kind projection would have dropped, like element-level `annotations`, which carry real A12 facts.
     */
    @Test
    fun anInvalidDeclarationIsRetainedWholeAndExactlyAsAuthored() {
        val rule = preparedRoots(
            """{"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Rule","id":"/Order/Bad","name":"Bad",
               "annotations":[{"name":"owner","value":"pricing"}],
               "Rule":{"errorCode":"BAD","errorEntityRelPath":"../Code",
                       "errorCondition":"[NoSuchField] > ("}}
            ]}}""",
        ).elements.single { it.kind == "Rule" }

        assertEquals(
            "[NoSuchField] > (",
            rule.declaration["Rule"]?.jsonObject?.get("errorCondition")?.jsonPrimitive?.content,
            "the unparseable condition must survive preparation to be diagnosable",
        )
        assertEquals(
            "pricing",
            rule.declaration["annotations"]?.jsonArray()?.get(0)?.jsonObject?.get("value")?.jsonPrimitive?.content,
            "an element-level annotation is authored content, not envelope content",
        )
    }

    /**
     * B2, "independent declarations remain analyzable after a local failure". `id` is required by the
     * Kernel's reader, so its absence is a finding — but it no longer *locates* the element, so the
     * declaration and its siblings are still prepared at their correct paths.
     */
    @Test
    fun aDeclarationMissingItsIdIsDiagnosedYetStillPlaced() {
        val prepared = preparedRoots(
            """{"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Field","name":"Nameless","Field":{}},
              {"type":"Field","id":"/Order/Code","name":"Code","Field":{}}
            ]}}""",
        )

        assertEquals(listOf("/Order", "/Order/Nameless", "/Order/Code"), prepared.elements.map { it.path })
        assertEquals(listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED), prepared.diagnostics.map { it.code })
        assertEquals("/Order/Nameless", prepared.diagnostics.single().at)
    }

    /**
     * `type` names the envelope and `name` places the subtree, so a declaration missing either cannot be
     * descended into — the one failure that genuinely hides children, and it says so rather than passing.
     */
    @Test
    fun aDeclarationMissingItsTypeOrNameCannotBeDescendedInto() {
        listOf(
            """{"id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Field","id":"/Order/Code","name":"Code","Field":{}}]}}""",
            """{"type":"Group","id":"/Order","Group":{"elements":[
              {"type":"Field","id":"/Order/Code","name":"Code","Field":{}}]}}""",
        ).forEach { rootGroup ->
            val prepared = preparedRoots(rootGroup)
            assertEquals(emptyList(), prepared.elements.map { it.path }, rootGroup)
            assertEquals(listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED), prepared.diagnostics.map { it.code })
        }
    }

    /** The nearest derived path locates a broken declaration, so a nested failure is not reported at the root. */
    @Test
    fun aBrokenDeclarationIsLocatedAtItsNearestDerivedAncestor() {
        val prepared = preparedRoots(
            """{"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Group","id":"/Order/Lines","name":"Lines","Group":{"elements":[
                {"type":"Field","id":"/Order/Lines/Qty"}
              ]}}
            ]}}""",
        )

        assertEquals("/Order/Lines", prepared.diagnostics.single().at)
    }

    /**
     * The discriminator decides descent, not the presence of an `elements` array: the Kernel's reader
     * switches on `type` and only its `Group` arm has children, so a non-group envelope carrying that key
     * declares nothing. Without this case a checker that descended into any envelope holding `elements`
     * passes every other test here, because no real fixture puts the key anywhere else.
     */
    @Test
    fun onlyAGroupDeclaresChildrenEvenIfAnotherEnvelopeCarriesTheKey() {
        val prepared = preparedRoots(
            """{"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
              {"type":"Field","id":"/Order/Code","name":"Code","Field":{"elements":[
                {"type":"Field","id":"/Order/Code/Nested","name":"Nested","Field":{}}
              ]}}
            ]}}""",
        )

        assertEquals(listOf("/Order", "/Order/Code"), prepared.elements.map { it.path })
        assertEquals(emptyList(), prepared.diagnostics)
    }

    /**
     * The Kernel's `type` switch is case-sensitive and its `default:` arm throws, making the whole source
     * unreadable. Neither a mis-cased nor an unknown kind may pass as an inert declaration here, because
     * that is the invisible direction: input A12 refuses, analysed by us without a word.
     */
    @Test
    fun aKindA12DoesNotDeclareIsReportedRatherThanAccepted() {
        listOf("group", "Section").forEach { type ->
            val prepared = preparedRoots(
                """{"type":"$type","id":"/Order","name":"Order","$type":{"elements":[]}}""",
            )
            assertEquals(
                listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED),
                prepared.diagnostics.map { it.code },
                type,
            )
        }
    }

    /**
     * The Kernel reads `type`/`id`/`name` through Jackson's `asText()`, which coerces a non-string scalar,
     * so a numeric id is a declaration A12 accepts. Refusing it here would be a client-side refusal with no
     * Kernel basis, and on the read path that makes a legal model unreadable rather than merely unauthorable.
     */
    @Test
    fun aNonStringIdentityIsCoercedTheWayTheKernelCoercesIt() {
        val prepared = preparedRoots(
            """{"type":"Group","id":5,"name":"Order","Group":{"elements":[
              {"type":"Field","id":"/Order/Code","name":"Code","Field":{}}
            ]}}""",
        )

        assertEquals(listOf("/Order", "/Order/Code"), prepared.elements.map { it.path })
        assertEquals("5", prepared.elements.first().id)
        assertEquals(emptyList(), prepared.diagnostics)
    }

    @Test
    fun preparationReadsWhetherTheModelAllowsShortNameFieldReferences() {
        fun flagOf(modelConfig: String) = prepared(model("", modelConfig)).fieldRefByShortNameAllowed

        assertTrue(flagOf("\"modelConfig\":{\"fieldRefByShortNameAllowed\":true},"))
        assertFalse(flagOf("\"modelConfig\":{\"fieldRefByShortNameAllowed\":false},"))
        assertFalse(flagOf(""), "an absent modelConfig cannot enable the fallback")
    }

    /**
     * No shape of well-formed JSON may make preparation raise: the module advertises that it reports and
     * skips what it cannot read, and an escaping exception is the one form the proposal admits for neither a
     * verdict nor a coverage gap. Each input reaches a different accessor that would otherwise throw, and
     * each must come back as a located finding rather than as a silence.
     */
    @Test
    fun aMemberOfTheWrongJsonTypeIsDiagnosedRatherThanRaised() {
        listOf(
            """{"header":{},"content":{"modelRoot":{"rootGroups":{}}}}""",
            """{"header":{},"content":{"modelRoot":{"rootGroups":[7]}}}""",
            model("""{"type":"Group","id":"/O","name":"O","Group":{"elements":{}}}"""),
            model("""{"type":"Group","id":"/O","name":"O","Group":7}""", ""),
            model("", "\"modelConfig\":{\"fieldRefByShortNameAllowed\":1},"),
        ).forEach { json ->
            assertEquals(
                listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED),
                prepared(json).diagnostics.map { it.code },
                json,
            )
        }
    }

    /**
     * Well-formed JSON that is not a DocumentModel prepares to an empty fact base without raising. That it
     * is currently indistinguishable from a legitimately empty model is a known gap, owned by the admission
     * contract rather than by this phase; what is locked here is only that it does not throw.
     */
    @Test
    fun jsonThatIsNotADocumentModelPreparesEmptyWithoutRaising() {
        listOf("42", """{"foo":1}""", """{"content":[]}""").forEach { json ->
            val prepared = prepared(json)
            assertEquals(emptyList(), prepared.elements, json)
            assertFalse(prepared.fieldRefByShortNameAllowed, json)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArray() =
        this as? kotlinx.serialization.json.JsonArray
}
