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
            {"type":"Field","id":"f9","name":"Opaque","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true}}}},
            {"type":"Field","id":"f10","name":"Amount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":3,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f11","name":"RepeatedTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"A"},{"value":"A"}]}}}},
            {"type":"Field","id":"f36","name":"StrandedSpecialValue","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"P"},{"value":"Q"}],
              "sValue":"R"}}}},
            {"type":"Group","id":"g1","name":"Hollow","Group":{"elements":[]}},
            {"type":"Group","id":"g9","name":"Aisle","Group":{"sortFields":[{"name":"AisleCode"}],"elements":[
              {"type":"Field","id":"f34","name":"AisleCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g12","name":"Overstock","Group":{"repeatability":2000000000,"elements":[
              {"type":"Field","id":"f35","name":"OverstockCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g3","name":"Shelf","Group":{"elements":[
              {"type":"Field","id":"f12","name":"ShelfCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
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
            {"type":"Rule","id":"r17","name":"Unreferenced","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Amount",
              "errorCode":"C17","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Group","id":"g6","name":"Pallets","Group":{"repeatability":7,"elements":[
              {"type":"Field","id":"f20","name":"PalletNote","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f21","name":"PalletQty","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Rule","id":"r23","name":"ReachedFromAbove","Rule":{
                "errorCondition":"GroupFilled(/Catalog) And [PalletQty] > 0",
                "errorEntityRelPath":"../PalletNote",
                "errorCode":"C23","errorMessage":[{"locale":"en_US","text":"x"}]}}
            ]}},
            {"type":"Rule","id":"r25","name":"StarInsideAFilter","Rule":{
              "errorCondition":"FieldFilled(Price) And Sum(Pallets*/PalletQty Having [Pallets*/PalletNote] == \"x\") > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C25","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r34","name":"StarredGroupQuantifier","Rule":{
              "errorCondition":"FieldFilled(Price) And AllGroupsFilled(Pallets*, Shelf)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C34","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r35","name":"UnparallelProducts","Rule":{
              "errorCondition":"FieldFilled(Price) And SumOfProducts(Pallets*/PalletQty, Crates*/CrateQty) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C35","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r36","name":"OverBudgetSelector","Rule":{
              "errorCondition":"FieldFilled(Price) And Min([Amount], 1, 2) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C36","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Field","id":"f37","name":"TravelDate","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd"}}}},
            {"type":"Field","id":"f42","name":"ShipMonth","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM"}}}},
            {"type":"Rule","id":"r42","name":"LatestOfTwoComponentSets","Rule":{
              "errorCondition":"FieldFilled(Price) And MaxValue(TravelDate, ShipMonth) == [TravelDate]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C42","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r39","name":"ImpossibleDay","Rule":{
              "errorCondition":"FieldFilled(TravelDate) And [TravelDate] == Date(\"32\", \"02\", \"2024\")",
              "errorEntityRelPath":"../TravelDate",
              "errorCode":"C39","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r41","name":"KeysOnParallelBranches","Rule":{
              "errorCondition":"RepetitionNotUnique(Crates/CrateQty, Totes/ToteQty)",
              "errorEntityRelPath":"../Crates/CrateQty",
              "errorCode":"C41","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r40","name":"FilterOffTheOperandsPath","Rule":{
              "errorCondition":"FieldFilled(Price) And NumberOfFilledFields(Pallets*/PalletNote Having Crates/CrateQty > 0) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C40","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r38","name":"UnstarredRepetitionRead","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldFilled(Crates/CrateQty)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C38","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r37","name":"StarredIndexKey","Rule":{
              "errorCondition":"FieldFilled(Price) And [Crates/CrateQty For Pallets*/PalletNote] > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C37","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Group","id":"g7","name":"Crates","Group":{"repeatability":5,
              "indexFieldName":"CrateRef","elements":[
              {"type":"Field","id":"f30","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f31","name":"CrateQty","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g8","name":"Totes","Group":{"repeatability":5,
              "indexFieldName":"CrateRef","elements":[
              {"type":"Field","id":"f32","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f33","name":"ToteQty","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Group","id":"g13","name":"Labels","Group":{"repeatability":3,"elements":[
                {"type":"Field","id":"f43","name":"LabelNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f44","name":"LabelQty","Field":{"fieldType":{"type":"NumberType"}}}
              ]}}
            ]}},
            {"type":"Rule","id":"r33","name":"ConstantOutsideTheEnum","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldValueIncludedInValueList(LowercaseTier, \"q\", \"NOPE\")",
              "errorEntityRelPath":"../Price",
              "errorCode":"C33","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r32","name":"UndeclaredCategory","Rule":{
              "errorCondition":"FieldFilled(Price) And [MiscategorisedTier -> NoSuchBand] == \"STANDARD\"",
              "errorEntityRelPath":"../Price",
              "errorCode":"C32","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r31","name":"FilteredGroupOperand","Rule":{
              "errorCondition":"FieldFilled(Price) And NumberOfFilledGroups(Pallets* Having [Pallets/PalletNote] == \"x\") > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C31","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r30","name":"GroupWhereAFieldBelongs","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldFilled(Shelf)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C30","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r29","name":"HModelSuffix","Rule":{
              "errorCondition":"FieldFilled(Price) And Sum(Pallets.*/PalletQty) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C29","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r45","name":"ValueListOverMixedKinds","Rule":{
              "errorCondition":"FieldFilled(Price) And AtLeastOneFieldValueIncludedInValueList(Price, Amount In \"A\")",
              "errorEntityRelPath":"../Price",
              "errorCode":"C45","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r44","name":"CountFalseOverText","Rule":{
              "errorCondition":"FieldFilled(Price) And NumberOfValueInFields(False In Price, Amount) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C44","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Computation","id":"c44","name":"CompoundFee","Computation":{
              "computedFieldRelPath":"../Amount",
              "computationAlternatives":[{"operation":"RoundDown([Amount] / 2 / 4, 2)"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r43","name":"FilterUnderAParallelBranch","Rule":{
              "errorCondition":"FieldFilled(Crates/CrateRef) And Sum(Totes/Labels*/LabelQty Having [Totes/Labels/LabelNote] == \"x\") > 0",
              "errorEntityRelPath":"../Crates/CrateRef",
              "errorCode":"C43","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r28","name":"UniquenessUnderParallelIteration","Rule":{
              "errorCondition":"RepetitionNotUnique(Crates/CrateRef) And FieldFilled(Totes/ToteQty)",
              "errorEntityRelPath":"../Crates/CrateRef",
              "errorCode":"C28","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r27","name":"TwoUniquenessCalls","Rule":{
              "errorCondition":"RepetitionNotUnique(Pallets/PalletNote) And RepetitionNotUnique(Pallets/PalletQty)",
              "errorEntityRelPath":"../Pallets/PalletNote",
              "errorCode":"C27","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r26","name":"GroupKeyedIndex","Rule":{
              "errorCondition":"FieldFilled(Price) And [Pallets/PalletQty For Shelf] > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C26","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r24","name":"ScopeDoesNotRepeat","Rule":{
              "errorCondition":"RepetitionNotUnique(Shelf/ShelfCode @From Shelf)",
              "errorEntityRelPath":"../Shelf/ShelfCode",
              "errorCode":"C24","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r22","name":"KeyWithNoRepetition","Rule":{
              "errorCondition":"RepetitionNotUnique(Price)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C22","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r21","name":"StarOnAFixedGroup","Rule":{
              "errorCondition":"FieldFilled(Price) And GroupFilled(/Catalog/Shelf*)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C21","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r20","name":"FieldWhereAGroupBelongs","Rule":{
              "errorCondition":"FieldFilled(Price) And GroupFilled(/Catalog/Shelf/ShelfCode)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C20","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r19","name":"RootOperand","Rule":{
              "errorCondition":"FieldFilled(Price) And AllGroupsFilled(/Depot, /Catalog/Shelf)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C19","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r18","name":"RepeatedGroup","Rule":{
              "errorCondition":"FieldFilled(Price) And AllGroupsFilled(/Catalog/Shelf, /Catalog/Shelf)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C18","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r16","name":"NamesHollow","Rule":{
              "errorCondition":"FieldFilled(Price) And GroupFilled(/Catalog/Hollow)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C16","errorMessage":[{"locale":"en_US","text":"x"}]}},
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
            {"type":"Rule","id":"r22","name":"MessageNamesUnread","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C22","errorMessage":[{"locale":"en_US","text":"see ${'$'}Amount${'$'}"}]}},
            {"type":"Rule","id":"r23","name":"MessageCarriesATab","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C23","errorMessage":[{"locale":"en_US","text":"see\u0009the price"}]}},
            {"type":"Field","id":"f14","name":"Tare","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f15","name":"Gross","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Computation","id":"c7","name":"GrossCalc","Computation":{
              "computedFieldRelPath":"../Gross",
              "computationAlternatives":[{"operation":"[Tare] + 1"}]}},
            {"type":"Computation","id":"c8","name":"TareCalc","Computation":{
              "computedFieldRelPath":"../Tare",
              "computationAlternatives":[{"operation":"[Gross] * 2"}]}},
            {"type":"Computation","id":"c6","name":"GuardedByAnAppCondition","Computation":{
              "computationAlternatives":[
                {"precondition":"FieldFilled(Price) And CustomCondition NotReverse",
                 "operation":"1.00"}]}},
            {"type":"Group","id":"g4","name":"Bins","Group":{"repeatability":9,"indexFieldName":"Slot",
              "elements":[
                {"type":"Field","id":"f13","name":"Slot","Field":{"fieldType":{"type":"NumberType"}}}
              ]}},
            {"type":"Computation","id":"c5","name":"IntoAnIndex","Computation":{
              "computedFieldRelPath":"../Bins/Slot",
              "computationAlternatives":[{"operation":"1"}]}},
            {"type":"Computation","id":"c4","name":"IntoAGroup","Computation":{
              "computedFieldRelPath":"../Shelf",
              "computationAlternatives":[{"operation":"1.00"}]}},
            {"type":"Rule","id":"r9","name":"RawLength","Rule":{
              "errorCondition":"Length(Opaque) >= 1","errorEntityRelPath":"../Opaque",
              "errorCode":"C9","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Field","id":"f1","name":"Twin","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Twin","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Ordered","Group":{"repeatability":20,
              "indexFieldName":"NoSuchIndex","elements":[
                {"type":"Field","id":"f3","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
              ]}},
            {"type":"Group","id":"g2","name":"BadKey","Group":{"repeatability":20,
              "indexFieldName":"Key","elements":[
                {"type":"Field","id":"f4","name":"Key","Field":{
                  "fieldType":{"StringType":{"noValueValidation":true}}}}
              ]}}
          ]}},
          {"type":"Group","id":"g4","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f13","name":"DepotCode","Field":{"fieldType":{"type":"StringType"}}}
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
        CheckFamily.STRING_TYPE_PARAMETERS to
            ("MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK" to "/Catalog/Opaque"),
        CheckFamily.NUMBER_TYPE_DIGITS to ("MVK_FRACT_DIGITS_INVALID" to "/Catalog/Amount"),
        CheckFamily.ENUM_VALUE_LABELS to
            ("MVK_INTERNAL_VALUES_DUPLICATE" to "/Catalog/RepeatedTier"),
        // The finding sits on the RULE that names the group, not on the group: declaring an empty group is
        // legal and only naming one is refused.
        CheckFamily.EMPTY_GROUP_OPERAND to ("MVK_GROUP_IS_EMPTY" to "/Catalog/NamesHollow"),
        CheckFamily.ERROR_FIELD_REFERENCE to
            ("MVK_ERROR_FIELD_NOT_REFERENCED" to "/Catalog/Unreferenced"),
        CheckFamily.COMPUTED_TARGET_KIND to (CK_MODEL_NOT_BUILDABLE to "/Catalog/IntoAGroup"),
        CheckFamily.RAW_TYPE_LENGTH to ("MVK_INVALID_LENGTH_OF_RAW_TYPE" to "/Catalog/RawLength"),
        CheckFamily.COMPUTED_TARGET_INDEX_FIELD to
            ("MVK_ERROR_INDEX_AS_CALCULATED_FIELD" to "/Catalog/IntoAnIndex"),
        CheckFamily.COMPUTATION_PRECONDITION_FEATURES to
            ("MVK_ERROR_INVALID_LANGUAGE_FEATURE" to "/Catalog/GuardedByAnAppCondition"),
        // The circle's report is anchored at the computation writing its smallest member, so the path here
        // is `Gross`'s writer rather than either declaration's position in the file.
        CheckFamily.COMPUTATION_CYCLE to ("MVK_ERROR_CALC_CYCLE" to "/Catalog/GrossCalc"),
        CheckFamily.MESSAGE_PARAMETER_REFERENCE to
            ("INVALID_FIELD" to "/Catalog/MessageNamesUnread"),
        CheckFamily.MESSAGE_TEXT_CHARACTERS to
            ("INVALID_CHARACTERS" to "/Catalog/MessageCarriesATab"),
        CheckFamily.GROUP_LIST_OPERANDS to ("MVK_DUPLICATE_PARAM1" to "/Catalog/RepeatedGroup"),
        CheckFamily.GROUP_LIST_ROOT_OPERAND to
            ("MVK_ROOT_GROUP_REFERENCED" to "/Catalog/RootOperand"),
        CheckFamily.GROUP_LIST_OPERAND_KIND to
            ("MVK_NO_FIELDS_ALLOWED" to "/Catalog/FieldWhereAGroupBelongs"),
        CheckFamily.REPETITION_WILDCARD_TARGET to
            ("MVK_INVALID_WILDCARD" to "/Catalog/StarOnAFixedGroup"),
        // `Price` sits directly in the rule's own group, so no repeatable group separates the two — the
        // shape the class collapses onto, and the one that needs no second group to express.
        CheckFamily.REPETITION_KEY_PLACEMENT to
            ("MVK_REPEATABLE_GROUP_MISSING" to "/Catalog/KeyWithNoRepetition"),
        // `Shelf` is the fixture's non-repeatable group, so scoping the operator to it gives it no
        // repetition to scan — and the key placement family stays silent because a clause-carrying operand
        // is not one it reduces.
        CheckFamily.REPETITION_SCOPE_GROUP to
            ("MVK_GROUP_NOT_REPEATABLE" to "/Catalog/ScopeDoesNotRepeat"),
        // The aggregate's own operand stars `Pallets` legally; the filter's copy of the same star is the
        // defect, which is what makes this row a position claim rather than a wildcard one.
        CheckFamily.FILTER_WILDCARD to
            ("MVK_NO_WILDCARDS_ALLOWED" to "/Catalog/StarInsideAFilter"),
        // The identical star on the identical group, one slot over: legal in the aggregate above and refused
        // here, which is what makes the operator rather than the star the thing this family reads.
        CheckFamily.GROUP_CONDITION_WILDCARD to
            ("MVK_NO_WILDCARDS_G_ALLOWED" to "/Catalog/StarredGroupQuantifier"),
        // Two well-formed starred lists over DIFFERENT owning groups, which is the first of that operator's
        // four gates and the only one reachable without a second defect in the same reference.
        CheckFamily.SUM_OF_PRODUCTS_OPERANDS to
            ("MVK_DIFFERENT_GROUPS" to "/Catalog/UnparallelProducts"),
        // Two literals in ONE call. The nesting that would make the same two legal is the family's own
        // discriminator and lives in its mirror; here the row only has to trigger the family.
        CheckFamily.OPERAND_LIST_CONSTANT_BUDGET to
            ("MVK_TOO_MANY_CONST_FOR_CALC_OP" to "/Catalog/OverBudgetSelector"),
        // A key that stands for a LIST, which is the first of that chain's six gates and the only one this
        // fixture can reach without also tripping the key-kind family's class on the same index.
        CheckFamily.SEMANTIC_INDEX_STRUCTURE to
            ("MVK_SEMANTIC_INDEX_WITH_WILDCARD" to "/Catalog/StarredIndexKey"),
        // `Shelf` again, this time in the KEY position of a semantic index: it is the fixture's only group
        // that does not repeat, which is exactly what keeps A12's iteration gate from pre-empting the class.
        CheckFamily.SEMANTIC_INDEX_KEY to
            ("MVK_SEMANTIC_INDEX_NO_FIELD" to "/Catalog/GroupKeyedIndex"),
        // Two calls over the same repetition. The keys are legally placed and each call is well formed on
        // its own, which is what makes this row about the COUNT rather than about either call.
        CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION to
            ("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE" to "/Catalog/TwoUniquenessCalls"),
        // `Crates` and `Totes` both key on `CrateRef` of the same declared type, so reading one field from
        // each makes the rule iterate them jointly — the shape no other row in this fixture produces.
        CheckFamily.PARALLEL_ITERATION_UNIQUENESS to
            ("MVK_PARALLEL_ITERATION_NOT_ALLOWED" to "/Catalog/UniquenessUnderParallelIteration"),
        // `Totes` is iterated jointly with the target's own `Crates`, and the aggregate's operand hangs a
        // filter off a level beneath it — the only shape in this fixture where one entity id carries both
        // the filter and the parallel marker.
        CheckFamily.FILTERED_ENTITY_ITERATION to
            ("MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED" to
                "/Catalog/FilterUnderAParallelBranch"),
        // Two divisions in one multiplicative chain, with nothing between them to restart the count — the
        // only arithmetic in this fixture that carries a second operator of the same kind.
        CheckFamily.CALCULATION_OPERATOR_ARITY to
            ("MVK_TOO_MANY_DIV_FOR_CALC" to "/Catalog/CompoundFee"),
        // `False` counts only Boolean declarations and the list opens on a text one — the one call in this
        // fixture whose constant and whose field list disagree about kind.
        CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND to
            ("MVK_NO_TYPEYESNO" to "/Catalog/CountFalseOverText"),
        // A text declaration and a number one in one quantifier's field list — the only call in this
        // fixture that compares two lists whose entries are not declared alike.
        CheckFamily.VALUE_LIST_QUANTIFIER_OPERAND_KIND to
            ("MVK_VARYING_TYPES_NOT_ALLOWED" to "/Catalog/ValueListOverMixedKinds"),
        // A repeatable group read without a star into an error field the root holds: the target's own
        // coordinates cannot pick a repetition of `Crates`, and nothing about the operator is at fault —
        // which is what makes the row about the LEVEL rather than about the predicate reading it.
        CheckFamily.OPERAND_REPETITION_SCOPE to
            ("MVK_NO_WILDCARD" to "/Catalog/UnstarredRepetitionRead"),
        // A complete date beside a year-month: the aggregate types its list from the leader and then compares
        // what each operand's FORMAT spells, so two DateType fields are enough to reach the class.
        CheckFamily.TEMPORAL_AGGREGATE_OPERANDS to
            ("MVK_DATEFORMATS_NOT_COMPATIBLE" to "/Catalog/LatestOfTwoComponentSets"),
        // Two keys in repeatable groups that declare the SAME index field, so they iterate jointly and A12
        // reaches the path question at all — index-free the pair is refused at the wildcard gate instead.
        CheckFamily.REPETITION_KEY_CONTAINMENT to
            ("MVK_NOT_IN_SAME_PATH" to "/Catalog/KeysOnParallelBranches"),
        // A filter naming a repeatable SIBLING of the starred operand's group: the operand itself is sound
        // and the filter is grammatical, so only the level the filter reaches can be what answers.
        CheckFamily.FILTER_BODY_ITERATION to
            ("MVK_INVALID_ITERATION_IN_FILTER_CONDITION" to "/Catalog/FilterOffTheOperandsPath"),
        // A day of 32 in a well-formed three-part construction: the other two components are valid and the
        // target is an ordinary full date, so nothing but the slot's own range can be what answers.
        CheckFamily.DATE_CONSTRUCTION_SLOT to
            ("MVK_WRONG_DATE_FORMAT_FOR_OP" to "/Catalog/ImpossibleDay"),
        // The suffix rides a group that DOES repeat, so the star itself is legal and this row cannot borrow
        // a wildcard family's finding: the `.` in front of it is the whole defect.
        CheckFamily.H_MODEL_PATH_SUFFIX to
            ("MVK_POINT_IN_A12_MODEL" to "/Catalog/HModelSuffix"),
        // `Shelf` a third time, now as a SCALAR field predicate's operand: it is the fixture's only group
        // that neither repeats nor is the root, which is what leaves the kind gate the one that answers.
        CheckFamily.FIELD_OPERAND_KIND to
            ("MVK_NO_GROUPS_ALLOWED" to "/Catalog/GroupWhereAFieldBelongs"),
        // The same code from the OTHER carrier: A12 raises it from `checkFilterCondition` as well, so the
        // two families are distinguished by where the operand sits rather than by the class they name.
        CheckFamily.FILTERED_OPERAND_KIND to
            ("MVK_NO_GROUPS_ALLOWED" to "/Catalog/FilteredGroupOperand"),
        // `MiscategorisedTier` declares the category `Band`, so the arrow's target IS an Enumeration with
        // categories and only the NAME behind the arrow is wrong — which is what keeps the enum gate from
        // pre-empting and makes this row about the reference rather than about the declaration.
        CheckFamily.CATEGORY_REFERENCE to
            ("MVK_CATEGORY_UNKOWN" to "/Catalog/UndeclaredCategory"),
        // `LowercaseTier` declares exactly one value, so the list carries one constant A12 admits and one it
        // does not — which keeps the row about the CONSTANT rather than about an empty or unreadable domain.
        CheckFamily.ENUM_CONSTANT_DOMAIN to
            ("MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION" to "/Catalog/ConstantOutsideTheEnum"),
        // `Aisle` carries a sort entry naming a field it really has, so the reference and type gates both
        // pass and only the CARRIER is wrong — which is what makes this row about repeatability alone.
        CheckFamily.GROUP_ORDERING_CARRIER to
            ("MVK_INVALID_SORTINFO" to "/Catalog/Aisle"),
        // A NESTED group past the nested cap, which is an order of magnitude above the root one — so the
        // row also fails if the family ever collapses the two carriers onto a single bound.
        CheckFamily.GROUP_REPETITION_COUNT to
            ("MVK_MAX_TOO_BIG" to "/Catalog/Overstock"),
        // The enumeration declares two values and points its special one at a third, so the row is about
        // the `sValue` alone — the value list itself is sound.
        CheckFamily.ENUM_S_VALUE_DOMAIN to
            ("MVK_S_VALUE_INVALID" to "/Catalog/StrandedSpecialValue"),
        // The rule sits in a repeatable group and reaches its error field only through the root, while
        // reading a sibling of that field — so A12 cannot say which pallet the error belongs to.
        CheckFamily.ERROR_TARGET_ITERATION to
            ("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID" to "/Catalog/Pallets/ReachedFromAbove"),
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
