package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That A12's own guidance actually reaches a finding, on a real report rather than on the table alone.
 *
 * The table is generated and guarded on the Java side (`CheckerCorrectiveProjectionGuardTest` re-derives it
 * from `StructuralKernelFix` and `RkCode`). What no guard over there can see is the last step — whether a
 * finding this checker produces finds its entry — which is the "parsed but never consumed" class one layer
 * down: a table can be perfectly projected and reach nobody.
 */
class CorrectiveTest {

    /**
     * One model that draws every projected code the checker can actually report.
     *
     * Each declaration is broken in exactly one way and each breakage owns its code, so a code that stops
     * being reachable names the declaration that stopped producing it rather than failing as a total.
     * `fieldRefByShortNameAllowed` is on because ambiguity is only a DIFFERENT rejection from absence when
     * the short-name fallback is live — with it off both `Code` fields are simply unreachable.
     */
    private val reachability = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Season","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"MM","youngerThan1900Check":true}}}},
            {"type":"Field","id":"f2","name":"Delivery","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM","datePrecision":"DAY_OPTIONAL"}}}},
            {"type":"Field","id":"f5","name":"Reference","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"a++"}}}},
            {"type":"Field","id":"f6","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":""}]}]}}}},
            {"type":"Group","id":"g1","name":"Warehouse","Group":{"elements":[
              {"type":"Field","id":"f3","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Store","Group":{"elements":[
              {"type":"Field","id":"f4","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Field","id":"f18","name":"Amount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f19","name":"Tally","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":0}}}},
            {"type":"Field","id":"f7","name":"Blob","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
            {"type":"Group","id":"g3","name":"Shelves","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f8","name":"ShelfCode","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f9","name":"ShelfQty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Crates","Group":{"repeatability":5,
              "indexFieldName":"CrateRef","elements":[
              {"type":"Field","id":"f14","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f15","name":"CrateQty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g5","name":"Totes","Group":{"repeatability":5,
              "indexFieldName":"CrateRef","elements":[
              {"type":"Field","id":"f16","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f17","name":"ToteQty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r18","name":"PatternTheGateRefuses","Rule":{
              "errorCondition":"FieldFilled(Note) And [Note] PatternMatched \"a++\"",
              "errorEntityRelPath":"../Note",
              "errorCode":"C18","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r17","name":"DateAgainstAText","Rule":{
              "errorCondition":"FieldFilled(Note) And [Season] == [Note]",
              "errorEntityRelPath":"../Note",
              "errorCode":"C17","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r16","name":"ScalesThatDisagree","Rule":{
              "errorCondition":"FieldFilled(Note) And [Amount] == [Tally]",
              "errorEntityRelPath":"../Note",
              "errorCode":"C16","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r15","name":"CountOverOneGroup","Rule":{
              "errorCondition":"FieldFilled(Note) And NumberOfFilledGroups(Warehouse) > 0",
              "errorEntityRelPath":"../Note",
              "errorCode":"C15","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r14","name":"KeysOnParallelBranches","Rule":{
              "errorCondition":"RepetitionNotUnique(Crates/CrateQty, Totes/ToteQty)",
              "errorEntityRelPath":"../Crates/CrateQty",
              "errorCode":"C14","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r12","name":"GroupWhereAFieldBelongs","Rule":{
              "errorCondition":"FieldFilled(Note) And FieldFilled(Warehouse)",
              "errorEntityRelPath":"../Note",
              "errorCode":"C12","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r11","name":"TwoUniquenessCalls","Rule":{
              "errorCondition":"RepetitionNotUnique(Shelves/ShelfCode) And RepetitionNotUnique(Shelves/ShelfQty)",
              "errorEntityRelPath":"../Shelves/ShelfCode",
              "errorCode":"C11","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r13","name":"UnstarredRepetitionRead","Rule":{
              "errorCondition":"FieldFilled(Note) And FieldFilled(Shelves/ShelfQty)",
              "errorEntityRelPath":"../Note",
              "errorCode":"C13","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r10","name":"KeyWithNoRepetition","Rule":{
              "errorCondition":"RepetitionNotUnique(Note)","errorEntityRelPath":"../Note",
              "errorCode":"C10","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r19","name":"RawInAValueList","Rule":{
              "errorCondition":"FieldValueIncludedInValueList(Blob, \"x\")","errorEntityRelPath":"../Blob",
              "errorCode":"C19","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r9","name":"RawLength","Rule":{
              "errorCondition":"Length(Blob) >= 1","errorEntityRelPath":"../Blob",
              "errorCode":"C9","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r0","name":"Absent","Rule":{
              "errorCondition":"FieldFilled(NoSuchField)","errorEntityRelPath":"../Note",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r1","name":"Ambiguous","Rule":{
              "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Note",
              "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r2","name":"Ungrammatical","Rule":{
              "errorCondition":"FieldFilled(Note))","errorEntityRelPath":"../Note",
              "errorCode":"C3","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r4","name":"Unreferenced","Rule":{
              "errorCondition":"FieldFilled(Note)","errorEntityRelPath":"../Season",
              "errorCode":"C5","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r3","name":"ForeignLocale","Rule":{
              "errorCondition":"FieldFilled(Note)","errorEntityRelPath":"../Note",
              "errorCode":"C4","errorMessage":[{"locale":"en_US","text":"x"},{"locale":"fr_FR","text":"y"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    @Test
    fun aFindingWhoseCodeItsOwnerExplainsCarriesTheGuidance() {
        val finding = check(reachability).findings.first { it.code == "MVK_INVALID_ENTITY" }
        val corrective = assertNotNull(finding.corrective, "MVK_INVALID_ENTITY is a served code")
        assertTrue(corrective.fix.isNotBlank(), corrective.fix)
        assertTrue(corrective.meaning.isNotBlank(), corrective.meaning)
        // The two texts answer different questions, which is why they are separate fields rather than one.
        assertTrue(corrective.fix != corrective.meaning, "the fix and the meaning must not be one text")
    }

    /**
     * A code no owner explains carries nothing — absence is the ordinary case, not a gap.
     *
     * [CK_MODEL_NOT_BUILDABLE] is this checker's own code rather than A12's, so no A12 owner could have
     * guidance for it. Asserting the null keeps the lookup honest: a table that answered for everything
     * would be inventing guidance.
     */
    @Test
    fun aCodeNoOwnerExplainsCarriesNothing() {
        val finding = Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "anything",
        )
        assertNull(finding.corrective)
    }

    /**
     * Every projected entry is reachable from a real report, save the one the checker never reports.
     *
     * This is the enforcement locus for the projection's own claim, and it cannot live on the Java side: the
     * generator scans source TEXT, and a textual scan cannot tell a code the checker reports from one it
     * holds to compare an authored value against. [SUPPRESSIBLE] is the latter, so it is named here rather
     * than filtered there — no textual rule separates the two without also being false of
     * [CK_MODEL_NOT_BUILDABLE], a module constant the checker does report.
     *
     * Asserting the whole residue as a set, rather than subtracting it, is what makes a NEW dead entry fail:
     * guidance shipped for a code no consumer can receive is bytes on every wire and a promise to nobody.
     */
    @Test
    fun everyProjectedEntryIsReachableExceptTheCodeTheCheckerOnlyComparesAgainst() {
        val reported = check(reachability).findings.map { it.code }.toSet()
        assertEquals(
            emptySet(),
            KERNEL_CORRECTIVES.keys - reported,
            "the corrective table carries guidance no report can deliver — drop the family's emission or " +
                "the entry, or add a declaration above that draws the code",
        )
    }
}
