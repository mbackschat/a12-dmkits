package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * That each fact in the transcript's alphabet is keyed on the model member it names, and on nothing else.
 *
 * **This is the store's own contract, and it is tested here rather than through a family for a measured
 * reason.** A wrong key in either direction is a defect — too wide only loses reuse, too narrow reuses a
 * verdict the model no longer supports — but neither is REACHABLE through the only consumer today:
 * `ConsistencySnapshot.draft` carries cells across a DRAFT, and a draft changes one text member of one
 * declaration, so no model-wide member and no variant filter can move under it. A mutant zeroing the
 * resolvable half of the shape value duly survived every end-to-end row (2026-09-18) until the control in
 * `FamilyCellReuseTest` was written for it. The grid below closes the rest of the alphabet the same way,
 * at the level where the claim is made instead of waiting for a consumer that can reach it.
 *
 * The isolation is asserted rather than assumed: each pair must move the fact it is for and leave the other
 * six alone, so a fixture that quietly changed two members could not pass as a test of one.
 */
class CheckCellKeyTest {

    private fun prepared(
        locales: String = """[{"code":"en_US"}]""",
        modelConfig: String = "",
        modelInfo: String = "",
        typeDefinitions: String = "",
        codeType: String = "StringType",
        second: String = "",
        libraryInfo: String? = null,
    ): PreparedModel {
        // A reached type-definition library, written only when [libraryInfo] is, so the baseline reaches nothing.
        val references = if (libraryInfo == null) "" else
            ""","modelReferences":[{"alias":"lib","modelType":"document","purpose":"typeDefinitions","reference":"lib"}]"""
        val source = ModelSource(
            """
            {"header":{"id":"keys","locales":$locales$references},
             "content":{$modelConfig$modelInfo$typeDefinitions
              "modelRoot":{"rootGroups":[
               {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                 {"type":"Field","id":"f0","name":"Code","Field":{
                   "fieldType":{"type":"$codeType"}}}$second
               ]}}
              ]}}}
            """.trimIndent(),
        )
        val admitted = admit(source)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible: ${source.json}")
        val library = libraryInfo?.let {
            admit(ModelSource("""{"header":{"id":"lib","locales":[{"code":"en_US"}]},"content":{$it"modelRoot":{"rootGroups":[]}}}"""))
        }
        return prepare(admitted, listOfNotNull(library as? Admission.Admitted).associateBy { it.modelId })
    }

    /** A field declared under [name] with [type], appended after `Code`. */
    private fun field(name: String, type: String) =
        """,{"type":"Field","id":"f1","name":"$name","Field":{"fieldType":{"type":"$type"}}}"""

    /** One member moved, against the baseline every row shares. */
    private val moved = mapOf(
        ModelFact.CONDITION_LANGUAGE to
            prepared(modelConfig = """"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"""),
        ModelFact.DECLARED_LOCALES to prepared(locales = """[{"code":"en_US"},{"code":"de_DE"}]"""),
        ModelFact.FIELD_REF_BY_SHORT_NAME_ALLOWED to
            prepared(modelConfig = """"modelConfig":{"fieldRefByShortNameAllowed":true},"""),
        ModelFact.BASE_YEAR to prepared(modelInfo = """"modelInfo":{"baseYear":2024},"""),
        ModelFact.SUPPORTED_CHARACTERS to
            prepared(modelConfig = """"modelConfig":{"supportedCharacters":["A-Z"]},"""),
        // A code A12's own reader yields no locale KEY for. It moves the unreadable list and leaves
        // `declaredLocales` alone, which is what the isolation assertion below then confirms rather than
        // takes on trust.
        ModelFact.UNREADABLE_LOCALE_CODES to prepared(locales = """[{"code":"en_US"},{"code":"!!"}]"""),
        // A header flag A12's reader refuses. It moves no other fact: the member is read for nothing else.
        ModelFact.UNBINDABLE_BOOLEANS to prepared(modelInfo = """"modelInfo":{"immutable":"tRUE"},"""),
        // The same flag, in a library the model reaches rather than in the model itself.
        ModelFact.UNBINDABLE_REACHED_MEMBERS to prepared(libraryInfo = """"modelInfo":{"immutable":"tRUE"},"""),
        ModelFact.TYPE_DEFINITIONS to prepared(
            typeDefinitions =
                """"typeDefinitions":[{"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}],""",
        ),
        // A repeatable group where the baseline has none. It is the body member `repeatability` that moves,
        // which is exactly why this fact exists rather than riding the set's shape: the shape carries path,
        // name, kind, id and origin, so it cannot see this edit at all.
        ModelFact.REPEATABLE_GROUP_SET to prepared(second = repeatableGroup()),
        // A group counted 0 repeats at most once, so the repeatable set stays put while this fact moves.
        ModelFact.NON_POSITIVE_COUNT_GROUPS to prepared(second = repeatableGroup(count = 0)),
    )

    /** A group appended after `Code`, repeating [count] times and carrying one field so it is worth declaring. */
    private fun repeatableGroup(count: Int = 4) =
        """,{"type":"Group","id":"g9","name":"Lines","Group":{"repeatability":$count,"elements":[
             {"type":"Field","id":"f9","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
           ]}}"""

    @Test
    fun everyModelMemberIsKeyedOnItsOwnMemberAndOnNoOther() {
        val baseline = prepared()
        val wrong = ModelFact.entries.mapNotNull { fact ->
            val other = moved.getValue(fact)
            val differing = ModelFact.entries.filter { candidate ->
                baseline.valueOf(FactRead.ModelMember(candidate)) !=
                    other.valueOf(FactRead.ModelMember(candidate))
            }
            // The whole grid in one comparison: the fact the pair moved must differ, and it must be the ONLY
            // one that differs. A mapping that read another member fails the first half; a fixture that
            // moved two members fails the second, so the row cannot pass by being imprecise.
            if (differing == listOf(fact)) null else "$fact: moved $differing"
        }

        assertEquals(emptyList(), wrong,
            "each fact must be keyed on the member it names, and the fixture must move that member alone")
        assertEquals(ModelFact.entries.size, moved.size,
            "a new ModelFact needs a row in `moved`, or its key is unmeasured")
    }

    /** The base-year fact carries the unbindable text too: two refusals that bind alike must still key apart. */
    @Test
    fun anUnbindableBaseYearsTextMovesItsFactWithoutAPresenceChange() {
        val upper = prepared(modelInfo = """"modelInfo":{"baseYear":"NULL"},""")
        val mixed = prepared(modelInfo = """"modelInfo":{"baseYear":"Null"},""")
        assertEquals(upper.hasBaseYear, mixed.hasBaseYear)
        assertTrue(upper.valueOf(FactRead.ModelMember(ModelFact.BASE_YEAR)) !=
            mixed.valueOf(FactRead.ModelMember(ModelFact.BASE_YEAR)))
    }

    @Test
    fun typeDefinitionBodyEditInvalidatesItsFactWithoutAnIdChange() {
        fun withMaximum(maximum: Int) = prepared(typeDefinitions =
            """"typeDefinitions":[{"id":"Code","name":"Code","fieldType":{"type":"StringType","StringType":{"maxLength":$maximum}}}],""",
        )
        val before = withMaximum(2)
        val after = withMaximum(3)
        assertEquals(before.declaredTypeDefinitionIds, after.declaredTypeDefinitionIds)
        assertTrue(before.valueOf(FactRead.ModelMember(ModelFact.TYPE_DEFINITIONS)) !=
            after.valueOf(FactRead.ModelMember(ModelFact.TYPE_DEFINITIONS)))
    }

    @Test
    fun theContentValueCoversEveryDeclarationAtAPath() {
        // A12 refuses a model that declares two elements at one path, and this checker REPORTS on it rather
        // than failing — so a path is not a unique key here, and a value covering one of the two bodies
        // would make an edit to the other look like no change at all.
        val twins = prepared(second = field("Code", "NumberType"))
        val content = FactRead.DeclarationContent("/Order/Code")

        assertEquals(2, twins.elements.count { it.path == "/Order/Code" },
            "the fixture must really declare the path twice, or this row is about one body")
        // BOTH edits, because one alone cannot discriminate: a value keeping only the LAST body moves when
        // the second is edited, and one keeping only the first moves when the first is. The second-edit row
        // alone let a `associate`-shaped mutant survive (2026-09-18), which is how this pair got here.
        assertEquals(
            emptyList(),
            listOf(
                "the first" to prepared(codeType = "ConfirmType", second = field("Code", "NumberType")),
                "the second" to prepared(second = field("Code", "ConfirmType")),
            ).filter { (_, edited) -> edited.valueOf(content) == twins.valueOf(content) }.map { it.first },
            "an edit to either declaration at a path must change that path's content value",
        )
    }

    @Test
    fun aCellFallsWhenItsDeclarationChangesKindUnderAnIdenticalBody() {
        // One declaration, declared as a Rule in one model and a Computation in the other, carrying the SAME
        // body object. Every family that filters by kind — which is most of them — answers differently for
        // the two, and nothing in a per-declaration cell's own reads would say so: the cursor's shape
        // members are free, and the cell key carries only the path.
        fun model(kind: String) = ModelSource(
            """
            {"header":{"id":"kinds","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"$kind","id":"r0","name":"Check","$kind":{
                  "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code"}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        fun prepared(kind: String): PreparedModel {
            val admitted = admit(model(kind))
            assertIs<Admission.Admitted>(admitted, "the $kind fixture must be admissible")
            return prepare(admitted)
        }

        val asRule = prepared("Rule")
        val asComputation = prepared("Computation")
        val content = FactRead.DeclarationContent(CHECK)
        val row = FactRead.DeclarationRow(CHECK, 0)

        // The control that gives this row its meaning, and the reason the pair was built this way: on the
        // CONTENT the two models are indistinguishable, so only the shape row can tell them apart.
        assertEquals(asRule.valueOf(content), asComputation.valueOf(content),
            "the two bodies must be identical, or the content value would do this row's work")
        assertTrue(asRule.valueOf(row) != asComputation.valueOf(row),
            "the declaration's kind must move its row, or there is nothing here to fell a cell")

        val tracked = TrackedFactBase(asRule, ReadTranscript())
        val scheduled = schedule(tracked)
        val store = CheckCells(scheduled.associate { it.key to tracked.cellFor(it.key, asRule, it.produce) })
        val atCheck = scheduled.map { it.key }
            .filter { it.producer is CheckProducer.PerDeclaration && it.at?.path == CHECK }

        assertTrue(atCheck.isNotEmpty(), "no family is keyed per declaration there, so this row is vacuous")
        assertEquals(
            emptyList(),
            atCheck.filter { store.validIn(asComputation, it) != null }.map { it.producer.completes },
            "a per-declaration cell must fall when its declaration is no longer the same declaration",
        )
    }

    @Test
    fun twoDeclarationsAtOnePathGetTwoCellsAndBothVerdictsReachTheReport() {
        val source = ModelSource(
            """
            {"header":{"id":"twins","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"Twin","Rule":{
                  "errorCondition":"FieldFilled(Code) And","errorEntityRelPath":"../Code",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}},
                {"type":"Rule","id":"r1","name":"Twin","Rule":{
                  "errorCondition":"FieldFilled(Code))","errorEntityRelPath":"../Code",
                  "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        val admitted = admit(source)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        val tracked = TrackedFactBase(prepare(admitted), ReadTranscript())

        // The key's ORDINAL half, which the row above does not reach: a path is not unique, so a
        // per-declaration key made of the path alone would collide — and the dispatcher keys its cells in a
        // MAP, so a collision does not merely lose reuse, it drops one of the two declarations from the
        // report entirely. Two cells at the twinned path is the precondition; the codes below are the
        // consequence a reader cares about.
        val atTwin = schedule(tracked)
            .filter { it.key.producer is CheckProducer.PerDeclaration && it.key.at?.path == TWIN }
            .groupBy { it.key.producer }
        assertTrue(atTwin.isNotEmpty(), "no family is keyed per declaration, so this row is vacuous")
        assertEquals(
            emptyList(),
            atTwin.filterValues { cells -> cells.map { it.key }.toSet().size != 2 }
                .map { (_, cells) -> cells.first().key.producer.completes },
            "two declarations at one path must key two distinct cells for each per-declaration family",
        )

        // Each twin carries a defect of a DIFFERENT family, so the pair cannot pass by reporting one
        // declaration twice: a collapsed key would drop whichever code the surviving cell does not make.
        val reported = checkRead(source).findings.filter { it.at == TWIN }.map { it.code }.toSet()
        assertEquals(
            emptyList(),
            listOf("MVK_INCOMPLETE_INPUT", "MVK_UNEXPECTED_TOKEN").filterNot { it in reported },
            "both twins' condition verdicts must reach the report, which carried $reported",
        )
    }

    @Test
    fun theShapeValueIgnoresBodiesEntirely() {
        val shape = FactRead.DeclarationSetShape

        // The other half of the alphabet's contract, and the failure it exists to prevent: a shape value
        // that compared `elements` would carry every body, so one keystroke would fell every cell that
        // merely iterated — collapsing reuse back to the per-source level L-a already reached.
        assertEquals(
            prepared(codeType = "StringType").valueOf(shape),
            prepared(codeType = "NumberType").valueOf(shape),
            "two models differing only in a body must share one shape value",
        )
        assertTrue(
            prepared().valueOf(shape) != prepared(second = field("Extra", "StringType")).valueOf(shape),
            "and a DECLARATION added must move it, or the row above passes on a constant",
        )
    }

    private companion object {
        const val TWIN = "/Order/Twin"
        const val CHECK = "/Order/Check"
    }
}
