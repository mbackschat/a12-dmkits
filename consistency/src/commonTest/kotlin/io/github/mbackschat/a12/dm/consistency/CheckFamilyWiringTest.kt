package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That every [CheckFamily] the report ADVERTISES actually reached the report.
 *
 * Each family already has its own test, and each of those calls the family's function directly — so the wire
 * from that function into [check] was held by nothing. Deleting any of three call sites left the whole module
 * green while `checksCompleted` went on naming the family as completed: the "parsed but never consumed" class
 * one layer up, an advertised invariant with no enforcement locus.
 *
 * **The model breaks one declaration per family, each at its own path**, because codes are not unique to a
 * family — `CK_MODEL_NOT_BUILDABLE` is reached by four of them and `MVK_INCOMPLETE_INPUT` by three, so a
 * code-only assertion would let one family's wire stand in for another's. The (code, path) pair is what
 * attributes a finding to the family that made it.
 *
 * The denominator is asserted too, which is the half a per-family test cannot supply: a constant added to
 * [CheckFamily] fails here until it is both wired into [check] and given a declaration that triggers it.
 */
class CheckFamilyWiringTest {

    private val source = ModelSource(
        """
        {"header":{"id":"wiring","locales":[{"code":"en_US"}]},
         "content":{"modelConfig":{"supportedCharacters":["A-Z"]},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Unbindable","Field":{
              "fieldType":{"type":"NotAType","NotAType":{}}}},
            {"type":"Field","id":"f2","name":"MisconfiguredDate","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"MM","youngerThan1900Check":true}}}},
            {"type":"Field","id":"f3","name":"Bad-Name","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f5","name":"MiscategorisedTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}],
              "categories":[{"name":"Band","values":["STANDARD"]}]}}}},
            {"type":"Field","id":"f6","name":"LowercaseTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"q"}]}}}},
            {"type":"Field","id":"f7","name":"Reference","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"a++","errorMessage":[{"locale":"en_US","text":"x"}]}}}},
            {"type":"Field","id":"f8","name":"Unexplained","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"[A-Z]+"}}}},
            {"type":"Rule","id":"r1","name":"BadTarget","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../NoSuchField",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r2","name":"BadEntity","Rule":{
              "errorCondition":"FieldFilled(NoSuchEntity)","errorEntityRelPath":"../Price",
              "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r3","name":"NoCode","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r4","name":"NoCondition","Rule":{
              "errorEntityRelPath":"../Price",
              "errorCode":"C4","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r5","name":"Unlexable","Rule":{
              "errorCondition":"FieldFilled(\"Price)","errorEntityRelPath":"../Price",
              "errorCode":"C5","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r6","name":"EndsEarly","Rule":{
              "errorCondition":"FieldFilled(Price) And","errorEntityRelPath":"../Price",
              "errorCode":"C6","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r7","name":"MixedConnectives","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldFilled(Price) Or FieldFilled(Price)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C8","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r11","name":"UnbindableSlot","Rule":{
              "errorCondition":{"value":"FieldFilled(Price)"},"errorEntityRelPath":"../Price",
              "errorCode":"C11","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r10","name":"UnbindablePatterns","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C10","errorMessage":[{"locale":"en_US","text":"x"}],
              "guiPatterns":{"dynamicEnumeration":{"nested":"true"}}}},
            {"type":"Rule","id":"r9","name":"Ungrammatical","Rule":{
              "errorCondition":"FieldFilled(Price))","errorEntityRelPath":"../Price",
              "errorCode":"C9","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r8","name":"Filtered","Rule":{
              "errorCondition":"FieldFilled(NoSuchEntity)","errorEntityRelPath":"../Price",
              "errorCode":"C7","errorMessage":[{"locale":"en_US","text":"x"}],
              "variantFilter":{"filterType":"ONLY","variants":["premium"]}}},
            {"type":"Computation","id":"c1","name":"NoWork","Computation":{
              "computationAlternatives":[]}},
            {"type":"Computation","id":"c2","name":"BadRounding","Computation":{
              "computationAlternatives":[{"operation":"1.00"}],"roundingMode":"SOMETIMES"}},
            {"type":"Computation","id":"c3","name":"BadSuppression","Computation":{
              "computationAlternatives":[{"operation":"1.00"}],
              "errorCodesToSuppress":["NOT A CODE"]}},
            {"type":"Field","id":"f1","name":"Twin","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Twin","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Ordered","Group":{"repeatable":true,
              "indexFieldName":"NoSuchIndex","elements":[
                {"type":"Field","id":"f3","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
              ]}},
            {"type":"Group","id":"g2","name":"BadKey","Group":{"repeatable":true,
              "indexFieldName":"Key","elements":[
                {"type":"Field","id":"f4","name":"Key","Field":{
                  "fieldType":{"StringType":{"noValueValidation":true}}}}
              ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /** The finding each family must contribute to the report, keyed so no two families share an entry. */
    private val evidence = mapOf(
        CheckFamily.DECLARED_REFERENCES to ("MVK_ERROR_FIELD_MISSING" to "/Catalog/BadTarget"),
        CheckFamily.CONDITION_REFERENCES to ("MVK_INVALID_ENTITY" to "/Catalog/BadEntity"),
        CheckFamily.DECLARATION_SLOTS to ("MVK_ERROR_CODE_MISSING" to "/Catalog/NoCode"),
        CheckFamily.COMPUTATION_ALTERNATIVES to ("MVK_CONDITION_MISSING" to "/Catalog/NoWork"),
        CheckFamily.RULE_CONDITION_AND_TARGET to (CK_MODEL_NOT_BUILDABLE to "/Catalog/NoCondition"),
        CheckFamily.ELEMENT_NAME_UNIQUENESS to (CK_MODEL_NOT_BUILDABLE to "/Catalog/Twin"),
        CheckFamily.GROUP_ORDERING_REFERENCES to (CK_MODEL_NOT_BUILDABLE to "/Catalog/Ordered"),
        CheckFamily.GROUP_ORDERING_FIELD_TYPE to
            ("MVK_INDEX_FIELD_NO_VALUE_VALIDATION" to "/Catalog/BadKey/Key"),
        CheckFamily.COMPUTATION_ROUNDING to (CK_MODEL_NOT_BUILDABLE to "/Catalog/BadRounding"),
        CheckFamily.SUPPRESSED_WARNINGS to ("MVK_UNEXPECTED_TOKEN" to "/Catalog/BadSuppression"),
        CheckFamily.CONDITION_LEXICALITY to ("MVK_LEXER_STANDARD_ERROR" to "/Catalog/Unlexable"),
        CheckFamily.CONDITION_COMPLETENESS to ("MVK_INCOMPLETE_INPUT" to "/Catalog/EndsEarly"),
        CheckFamily.CONNECTIVE_AGREEMENT to ("MVK_BRACKET_MISSING" to "/Catalog/MixedConnectives"),
        CheckFamily.CONDITION_GRAMMAR to ("MVK_UNEXPECTED_TOKEN" to "/Catalog/Ungrammatical"),
        CheckFamily.RULE_PRESENTATION_MEMBERS to (CK_MODEL_NOT_BUILDABLE to "/Catalog/UnbindablePatterns"),
        CheckFamily.STRING_SLOT_BINDING to (CK_MODEL_NOT_BUILDABLE to "/Catalog/UnbindableSlot"),
        CheckFamily.FIELD_TYPE_BINDING to (CK_MODEL_NOT_BUILDABLE to "/Catalog/Unbindable"),
        CheckFamily.TEMPORAL_CONFIG to ("MVK_ADDITIONAL_CHECK_INVALID" to "/Catalog/MisconfiguredDate"),
        CheckFamily.ELEMENT_NAME_ALPHABET to ("MVK_PATH_INVALID" to "/Catalog/Bad-Name"),
        CheckFamily.ENUM_CATEGORY_ALIGNMENT to
            ("MVK_MISSING_CATEGORY_VALUE" to "/Catalog/MiscategorisedTier"),
        CheckFamily.ENUM_VALUE_CHARACTERS to
            ("MVK_ENUM_VALUE_WITH_INVALID_CHARACTER" to "/Catalog/LowercaseTier"),
        CheckFamily.STRING_PATTERN_ADMISSION to ("MVK_PATTERN_INVALID" to "/Catalog/Reference"),
        CheckFamily.STRING_PATTERN_MESSAGE to
            ("MVK_ERROR_MESSAGE_AND_PATTERN_INVALID" to "/Catalog/Unexplained"),
    )

    @Test
    fun everyAdvertisedFamilyReachesTheReport() {
        val report = check(source)
        val present = report.findings.map { it.code to it.at }.toSet()
        val actual = evidence.mapValues { (_, row) -> row in present }
        assertEquals(evidence.mapValues { true }, actual,
            "a family whose wire into check() is missing reports nothing while checksCompleted still names it")
    }

    /**
     * The variant family is the exception by design: its output is a COVERAGE GAP, because A12 accepts the
     * declaration it concerns. Its wire therefore has to be checked on the gap channel, not the finding one.
     */
    @Test
    fun theVariantFamilyReachesTheReportThroughItsGap() {
        assertTrue(check(source).gaps.any { it.at == "/Catalog/Filtered" && it.reason.contains("ONLY") },
            "the ONLY-filtered declaration must be disclosed as unexamined")
    }

    /** Every family is triggered by something above — the half a per-family test cannot supply. */
    @Test
    fun everyFamilyIsAccountedFor() {
        assertEquals(CheckFamily.entries.toSet(), evidence.keys + CheckFamily.VARIANT_FILTER,
            "a new CheckFamily constant needs a declaration here that triggers it, not only a call site")
    }

    /** …and `checksCompleted` names exactly the families this model proves ran. */
    @Test
    fun checksCompletedNamesTheFamiliesThatRan() {
        assertEquals(CheckFamily.entries.toSet(), check(source).checksCompleted,
            "checksCompleted is evidence, so it may not name a family the report never carried")
    }
}
