package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the read barrier records, which is the property per-declaration reuse rests on.
 *
 * **The case that matters is [oneDeclarationsBodyIsNotAnotherDeclarationsDependency].** A barrier that
 * recorded "iterated the declarations" as a dependency on those declarations' content would be correct — a
 * transcript naming everything can never reuse a stale cell — and worthless, because one keystroke would fell
 * every family that loops. Every equivalence test would stay green while the slice delivered nothing, so the
 * granularity needs its own oracle rather than riding the correctness suite.
 */
class ReadTranscriptTest {

    /** Two fields in one group, so a transcript can name one declaration and not the other. */
    private fun twoFieldModel(secondFieldBody: String) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"}]}}}},
            {"type":"Field","id":"f1","name":"Band","Field":{$secondFieldBody}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun preparedOf(source: ModelSource): PreparedModel {
        val admitted = admit(source)
        assertTrue(admitted is Admission.Admitted, "the fixture must be admissible, not $admitted")
        return prepare(admitted)
    }

    private fun transcriptOf(source: ModelSource, read: TrackedFactBase.() -> Unit): Set<FactRead> {
        val transcript = ReadTranscript()
        TrackedFactBase(preparedOf(source), transcript).read()
        return transcript.observed()
    }

    @Test
    fun iteratingTheDeclarationsRecordsTheSetShapeAndNoBody() {
        val observed = transcriptOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")) {
            declarations().map { it.path }
        }

        assertEquals(setOf<FactRead>(FactRead.DeclarationSetShape), observed,
            "walking the declarations for their paths depends on the set's shape and on no declaration's body")
    }

    @Test
    fun readingOneDeclarationsBodyChargesThatPathAlone() {
        val observed = transcriptOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")) {
            declarations().first { it.name == "Tier" }.body
        }

        assertEquals(
            setOf(FactRead.DeclarationSetShape, FactRead.DeclarationContent("/Catalog/Tier")),
            observed,
            "reading one body charges that path and leaves the sibling's out of the transcript",
        )
    }

    @Test
    fun aModelWideMemberIsChargedAsItself() {
        val observed = transcriptOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")) {
            conditionLanguage
        }

        assertEquals(setOf(FactRead.ModelMember(ModelFact.CONDITION_LANGUAGE)), observed,
            "a model-wide member is one fact, not a dependency on the declarations")
    }

    /**
     * A body-scoped family charges bodies and no model-wide member, which is what makes it independent of one.
     *
     * The enum family's verdict reads an enumeration's own declaration and nothing about the model, so no
     * edit to a locale set, a condition language or a supported-character list can reach it. That is a claim
     * about the transcript's ALPHABET, and it is the half of granularity a whole-model family can carry: the
     * per-declaration half needs per-declaration cells, whose oracle arrives with them (L-b3 in
     * `docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md`).
     */
    @Test
    fun aBodyScopedFamilyChargesNoModelWideMember() {
        val observed = transcriptOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")) {
            enumSpecialValueFindings()
        }

        assertEquals(
            emptySet(),
            observed.filterIsInstance<FactRead.ModelMember>().toSet(),
            "the enum family read a model-wide member, so a model-wide edit would fell its cells: $observed",
        )
        assertTrue(
            FactRead.DeclarationContent("/Catalog/Tier") in observed,
            "the positive control: the family must really have read the enumeration it judges, or this test " +
                "passes on a family that read nothing at all — $observed",
        )
    }
}
