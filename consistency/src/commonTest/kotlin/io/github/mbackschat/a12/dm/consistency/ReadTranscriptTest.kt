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
    fun askingWhetherAPathResolvesChargesTheSetShapeAlone() {
        val observed = transcriptOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")) {
            isResolvable("/Catalog/Tier")
        }

        // Positive control on the answer, not only on the charge: a member that answered false for every
        // path would record the same shape read and make the resolvable set useless.
        assertTrue(
            TrackedFactBase(preparedOf(twoFieldModel(""""fieldType":{"type":"StringType"}""")), ReadTranscript())
                .isResolvable("/Catalog/Tier"),
            "the fixture's field must really be resolvable, or the charge below is measured on a null answer",
        )
        assertEquals(
            setOf<FactRead>(FactRead.DeclarationSetShape),
            observed,
            "the resolvable set is the declarations minus what the default variant deletes, which IS the " +
                "set's shape — charging it as content would give every reference reader every body",
        )
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
     * Resolving a reference charges the SHAPE and the short-name flag, and no declaration's body.
     *
     * This is what makes the resolver — reached by 37 files — harmless to reuse granularity rather than the
     * thing that destroys it. Resolution consults the by-path index and the resolvable set, and reads
     * `fieldRefByShortNameAllowed` for the second tier; it never opens a body. So a cell that resolved a
     * reference survives every edit that leaves the declarations where they are, which is every keystroke in
     * a condition or a message.
     *
     * A body charge appearing here would mean the resolver had started reading declarations, and every
     * reference-reading family would silently depend on the whole model again.
     */
    @Test
    fun resolvingAReferenceChargesTheShapeAndTheFlagAlone() {
        val source = twoFieldModel(""""fieldType":{"type":"StringType"}""")
        var resolved: Reference? = null
        val observed = transcriptOf(source) { resolved = resolveEntity("/Catalog", "Tier") }

        assertEquals(Reference.Resolved("/Catalog/Tier"), resolved,
            "the positive control: resolution must really have happened, or the charge is for nothing")
        assertEquals(
            setOf(
                FactRead.DeclarationSetShape,
                FactRead.ModelMember(ModelFact.FIELD_REF_BY_SHORT_NAME_ALLOWED),
            ),
            observed,
            "resolution must charge no body, or every reference-reading family depends on the whole model",
        )
    }

    /**
     * A DERIVED query is charged per declaration, which is the case the design record singles out.
     *
     * `declaredReferences()` on the fact base hands back every rule's and computation's reference at once,
     * so a family reading it depends on all of their bodies; eighteen families do. Asked through one
     * declaration's cursor, the charge must be that declaration's own content plus the set's shape — the
     * shape because resolving the relative path consults the by-path index, and nothing more, because no
     * other declaration's body can change this answer.
     *
     * The rule's SIBLING is the discriminator: a model carrying two rules must not charge the second's body
     * when only the first is asked.
     */
    @Test
    fun aDerivedReferenceIsChargedAgainstItsOwnDeclarationAlone() {
        val twoRules = ModelSource(
            """
            {"header":{"id":"orders","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"First","Rule":{
                  "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Code)"}},
                {"type":"Rule","id":"r1","name":"Second","Rule":{
                  "errorEntityRelPath":"../Code","errorCondition":"FieldFilled(Code)"}}
              ]}}
             ]}}}
            """.trimIndent(),
        )

        var resolved: Reference? = null
        val observed = transcriptOf(twoRules) {
            resolved = declarations().first { it.name == "First" }.declaredReference()?.target
        }

        // The positive control, and it is not optional: the charge is recorded BEFORE the lookup, so this
        // assertion would pass on an implementation that found no reference at all and charged anyway.
        assertEquals(Reference.Resolved("/Order/Code"), resolved,
            "the fixture's rule must really carry a resolvable error target, or the charge below is a charge " +
                "for work that did not happen")
        assertEquals(
            setOf(FactRead.DeclarationSetShape, FactRead.DeclarationContent("/Order/First")),
            observed,
            "asking one declaration for its reference must not charge its sibling's body",
        )
    }

    /**
     * Each model-wide accessor charges ITS OWN fact, which nothing else in the suite can catch.
     *
     * The 643 verdict tests cannot: an accessor recording a neighbour's [ModelFact] returns the right value
     * and every verdict stays correct, while the cell it keys is keyed on a fact the family never read — a
     * silent wrong-key bug, and the one class that makes reuse unsound rather than merely coarse. So an
     * accessor arrives with a row here, and the assertion is on the fact recorded rather than on the value
     * returned.
     */
    @Test
    fun eachModelWideAccessorChargesItsOwnFact() {
        val source = twoFieldModel(""""fieldType":{"type":"StringType"}""")
        val actual = mapOf(
            "conditionLanguage" to transcriptOf(source) { conditionLanguage }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
            "declaredLocales" to transcriptOf(source) { declaredLocales }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
            "hasBaseYear" to transcriptOf(source) { hasBaseYear }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
            "supportedCharacters" to transcriptOf(source) { supportedCharacters }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
            "unreadableLocaleCodes" to transcriptOf(source) { unreadableLocaleCodes }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
            "declaredTypeDefinitionIds" to transcriptOf(source) { declaredTypeDefinitionIds }
                .filterIsInstance<FactRead.ModelMember>().toSet(),
        )

        assertEquals(
            mapOf(
                "conditionLanguage" to setOf(FactRead.ModelMember(ModelFact.CONDITION_LANGUAGE)),
                "declaredLocales" to setOf(FactRead.ModelMember(ModelFact.DECLARED_LOCALES)),
                "hasBaseYear" to setOf(FactRead.ModelMember(ModelFact.HAS_BASE_YEAR)),
                "supportedCharacters" to setOf(FactRead.ModelMember(ModelFact.SUPPORTED_CHARACTERS)),
                "unreadableLocaleCodes" to setOf(FactRead.ModelMember(ModelFact.UNREADABLE_LOCALE_CODES)),
                "declaredTypeDefinitionIds" to
                    setOf(FactRead.ModelMember(ModelFact.DECLARED_TYPE_DEFINITION_IDS)),
            ),
            actual,
            "every accessor must charge the fact it names, or a cell is keyed on a fact nobody read",
        )
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

    @Test
    fun aScopedFactBaseNarrowsTheWALKAndNothingElse() {
        // A rule, so `declaredReferences()` has something to find: it is the accessor that had to be moved
        // off the public walk when the scope arrived, and an empty answer would pass either way.
        val prepared = preparedOf(
            ModelSource(
                """
                {"header":{"id":"scoped","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Rule","id":"r0","name":"PriceRequired","Rule":{
                      "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        )
        val base = TrackedFactBase(prepared, ReadTranscript())
        val first = base.declarations().first()
        val at = DeclarationKey(first.path, 0)
        val scoped = base.scopedTo(at, first)

        // Read through BUCKETS, because a scoped base shares its parent's transcript — which is the
        // production arrangement rather than a test convenience: the dispatcher's own walk charges the
        // composition, and each cell records only what it read. Two arbitrary registrations serve as keys;
        // nothing here asks what they produce.
        val walk = CellKey(CHECK_PRODUCERS.first(), at)
        val setQuestion = CellKey(CHECK_PRODUCERS.last(), at)
        val walked = base.whileProducing(walk) { scoped.declarations() }
        val references = base.whileProducing(setQuestion) { scoped.declaredReferences() }

        // The narrowing and its charge: the family's loop runs once, and the cell is keyed on that
        // declaration's row rather than on the set — which is the whole reason the scope exists.
        assertEquals(listOf(first.path), walked.map { it.path }, "a scoped base hands back its one declaration")
        assertEquals(setOf(FactRead.DeclarationRow(first.path, 0)), base.observedWhileProducing(walk),
            "and charges that declaration's row, and nothing else")

        // …and the half that keeps the migration gate mechanical: a SET question is still answered about the
        // whole model and still charges the shape, so a family asking one is refused by the charge-profile
        // invariant instead of quietly receiving one declaration's worth of answer.
        assertEquals(listOf("/Catalog/PriceRequired"), references.map { it.declaredAt },
            "declaredReferences answers for the whole model, whatever the base is scoped to")
        assertTrue(FactRead.DeclarationSetShape in base.observedWhileProducing(setQuestion),
            "and charges the set shape, or the invariant that refuses a set-reading family cannot see it")

        // The group counts as a declaration, so the denominator is derived rather than written: `first` above
        // is `/Catalog` itself, which is also why the row assertion is about a group's row.
        assertEquals(prepared.elements.size, base.declarations().size,
            "the unscoped base still sees every declaration")
    }
}
