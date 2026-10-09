package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            {"type":"Field","id":"f96","name":"Tier.value","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f5","name":"MiscategorisedTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}],
              "categories":[{"name":"Band","values":["STANDARD"]}]}}}},
            {"type":"Field","id":"f6","name":"LowercaseTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"q"}]}}}},
            {"type":"Field","id":"f95","name":"TabbedTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"A\tB"}]}}}},
            {"type":"Field","id":"f94","name":"Glyph","annotations":[{"name":"mark","value":"\uD83D\uDE00"}],
              "Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f7","name":"Reference","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"a++","errorMessage":[{"locale":"en_US","text":"x"}]}}}},
            {"type":"Field","id":"f8","name":"Unexplained","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"[A-Z]+"}}}},
            {"type":"Field","id":"f98","name":"Tagged","Field":{"fieldType":{"type":"StringType",
              "StringType":{"pattern":"[A-Z]+","errorMessage":[{"locale":"en_US","text":"see ${'$'}Tag${'$'}"}]}}}},
            {"type":"Field","id":"f9","name":"Opaque","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true}}}},
            {"type":"Field","id":"f97","name":"Suggested","Field":{"fieldType":{"type":"StringType",
              "StringType":{"maxLength":3,
                "hintList":[{"locale":"en_US","values":["ABCD"]}]}}}},
            {"type":"Field","id":"f10","name":"Amount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":3,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f11","name":"RepeatedTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"A"},{"value":"A"}]}}}},
            {"type":"Field","id":"f49","name":"TargetGrade","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"P"}]}}}},
            {"type":"Field","id":"f50","name":"SourceGrade","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"P"},{"value":"Q"}]}}}},
            {"type":"Field","id":"f48","name":"LabelledTier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[
                {"value":"A","label":[{"locale":"en_US","text":"Alpha"}]},
                {"value":"B","label":[{"locale":"en_US","text":"Beta"}]}]}}}},
            {"type":"Field","id":"f45","name":"Signed","Field":{"fieldType":{"type":"ConfirmType"}}},
            {"type":"Field","id":"f47","name":"ShiftStart","Field":{"fieldType":{"type":"TimeType",
              "TimeType":{"format":"HH:mm:ss"}}}},
            {"type":"Field","id":"f46","name":"UnitCount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":0}}}},
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
            {"type":"Field","id":"f30","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
              "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
            {"type":"Group","id":"g3","name":"Shelf","Group":{"elements":[
              {"type":"Field","id":"f12","name":"ShelfCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Rule","id":"r1","name":"BadTarget","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../NoSuchField",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r102","name":"YearOfAPureClock","Rule":{
              "errorCondition":"FieldFilled(Price) And YearFromDate(ShiftStart) == 1970",
              "errorEntityRelPath":"../Price",
              "errorCode":"C102","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r101","name":"CategoryInACount","Rule":{
              "errorCondition":"FieldFilled(Price) And NumberOfFilledFields(MiscategorisedTier -> Band, Price) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C101","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r100","name":"ReadOfAnUnparsedText","Rule":{
              "errorCondition":"FieldFilled(Price) And [Opaque] == \"X\"","errorEntityRelPath":"../Price",
              "errorCode":"C100","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r99","name":"TableSubject","Rule":{
              "errorCondition":"ValueNotConsistent(Price To \"HIGH\")","errorEntityRelPath":"../Price",
              "errorCode":"C99","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r2","name":"BadEntity","Rule":{
              "errorCondition":"FieldFilled(NoSuchEntity)","errorEntityRelPath":"../Price",
              "errorCode":"C2","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r97","name":"CarriesNoMessage","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C97","errorMessage":[]}},
            {"type":"Rule","id":"r98","name":"NamesTheBaseYear","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C98","errorMessage":[{"locale":"en_US","text":"since ${'$'}BaseYear${'$'}"}]}},
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
              ,{"type":"Rule","id":"r68","name":"WritesOutsideItsExtent","Rule":{
                "errorCondition":"FieldFilled(../Price) And [PalletQty] > 0",
                "errorEntityRelPath":"../../Price",
                "errorCode":"C68","errorMessage":[{"locale":"en_US","text":"x"}]}}
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
              "errorCondition":"FieldFilled(Price) And NumberOfFilledFields(Pallets*/PalletNote Having [Crates/CrateQty] > 0) > 0",
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
            {"type":"Rule","id":"r52","name":"PatternOverANumber","Rule":{
              "errorCondition":"FieldFilled(Price) And [Amount] PatternMatched \"a+\"",
              "errorEntityRelPath":"../Price",
              "errorCode":"C52","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r51","name":"DateAgainstATextField","Rule":{
              "errorCondition":"FieldFilled(Price) And [TravelDate] == [Price]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C51","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r50","name":"ScaleTwoAgainstAWholeField","Rule":{
              "errorCondition":"FieldFilled(Price) And [Amount] == [UnitCount]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C50","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r49","name":"TextAgainstANumber","Rule":{
              "errorCondition":"FieldFilled(Price) And [Price] == [Amount]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C49","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r48","name":"ConfirmAgainstFalse","Rule":{
              "errorCondition":"FieldFilled(Price) And [Signed] == False",
              "errorEntityRelPath":"../Price",
              "errorCode":"C48","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r47","name":"OrderedTextComparison","Rule":{
              "errorCondition":"FieldFilled(Price) And [Price] < [Reference]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C47","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r60","name":"PartialDateFromATime","Rule":{
              "errorCondition":"FieldFilled(Price) And ValueAsDate(ShiftStart, FirstDay) > [TravelDate]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C60","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r59","name":"LabelledAgainstPlain","Rule":{
              "errorCondition":"FieldFilled(Price) And [LabelledTier] == [RepeatedTier]",
              "errorEntityRelPath":"../Price",
              "errorCode":"C59","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r58","name":"CutOfANumber","Rule":{
              "errorCondition":"FieldFilled(Price) And RangeAsString(UnitCount, 1, 3) == \"ABC\"",
              "errorEntityRelPath":"../Price",
              "errorCode":"C58","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r57","name":"DateWithoutItsYear","Rule":{
              "errorCondition":"FieldFilled(Price) And [TravelDate] == Date(\"01\", \"02\")",
              "errorEntityRelPath":"../Price",
              "errorCode":"C57","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r56","name":"CodeValidityOnANumber","Rule":{
              "errorCondition":"FieldFilled(Price) And Valid(UnitCount, \"EMail\")",
              "errorEntityRelPath":"../Price",
              "errorCode":"C56","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r55","name":"RangesAgainstAText","Rule":{
              "errorCondition":"FieldFilled(Price) And DateRangesOverlap(Window, Reference)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C55","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r54","name":"DaysFromAReference","Rule":{
              "errorCondition":"FieldFilled(Price) And DifferenceInDays(Reference, TravelDate) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C54","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r53","name":"LengthOfANumber","Rule":{
              "errorCondition":"FieldFilled(Price) And Length(UnitCount) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C53","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r52","name":"RoundedToTooManyPlaces","Rule":{
              "errorCondition":"FieldFilled(Price) And RoundUpValue(UnitCount, 99) > 0",
              "errorEntityRelPath":"../Price",
              "errorCode":"C52","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r51","name":"HoursAddedToAPureClock","Rule":{
              "errorCondition":"FieldFilled(Price) And [ShiftStart] > AddHours(ShiftStart, 1)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C51","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r50","name":"FirstFilledOverAConfirm","Rule":{
              "errorCondition":"FieldFilled(Price) And FirstFilledValue(Signed, Price) == \"x\"",
              "errorEntityRelPath":"../Price",
              "errorCode":"C50","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r49","name":"UniquenessOverADateRange","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldValuesNotUnique(Window, Price)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C49","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r48","name":"FieldListOverAGroupAndItsField","Rule":{
              "errorCondition":"FieldFilled(Price) And AllFieldsFilled(Shelf, Shelf/ShelfCode)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C48","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r46","name":"AllFieldsOverOneField","Rule":{
              "errorCondition":"FieldFilled(Price) And AllFieldsFilled(Amount)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C46","errorMessage":[{"locale":"en_US","text":"x"}]}},
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
            {"type":"Rule","id":"r102","name":"GroupAsAUniquenessKey","Rule":{
              "errorCondition":"RepetitionNotUnique(Shelf)",
              "errorEntityRelPath":"../Price",
              "errorCode":"C102","errorMessage":[{"locale":"en_US","text":"x"}]}},
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
            {"type":"Computation","id":"c61","name":"NarrowedGrade","Computation":{
              "computedFieldRelPath":"../TargetGrade",
              "computationAlternatives":[{"operation":"[SourceGrade]"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}},
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
            {"type":"Field","id":"f70","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Computation","id":"c70","name":"ReadsItsOwnTarget","Computation":{
              "computedFieldRelPath":"../Label",
              "computationAlternatives":[{"operation":"[Label]"}]}},
            {"type":"Computation","id":"c6","name":"GuardedByAnAppCondition","Computation":{
              "computationAlternatives":[
                {"precondition":"FieldFilled(Price) And CustomCondition NotReverse",
                 "operation":"1.00"}]}},
            {"type":"Group","id":"g4","name":"Bins","Group":{"repeatability":9,"indexFieldName":"Slot",
              "elements":[
                {"type":"Field","id":"f13","name":"Slot","Field":{"fieldType":{"type":"NumberType"}}}
              ]}},
            {"type":"Field","id":"f14","name":"Remark","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
            {"type":"Rule","id":"r67","name":"SuffixesAValueFreeField","Rule":{
              "errorCondition":"FieldFilled(Price) And FieldFilled(Remark)","errorEntityRelPath":"../Price","errorCode":"C67",
              "errorMessage":[{"locale":"en_US","text":"see ${'$'}Remark.value${'$'}"}]}},
            {"type":"Rule","id":"r66","name":"NamesAGroupBesideTheRule","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price","errorCode":"C66",
              "errorMessage":[{"locale":"en_US","text":"row ${'$'}#/Catalog/Aisle${'$'} here"}]}},
                        {"type":"Rule","id":"r65","name":"OpensAParameterItNeverCloses","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price","errorCode":"C65",
              "errorMessage":[{"locale":"en_US","text":"see ${'$'}Price"}]}},
                        {"type":"Rule","id":"r64","name":"MarksAnOuterIterationOutsideAFilter","Rule":{
              "errorCondition":"FieldFilled(Price) And [${'$'}/Catalog/UnitCount] > 0",
              "errorEntityRelPath":"../Price","errorCode":"C64",
              "errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Rule","id":"r63","name":"MessageIndexesOffThePath","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C63","errorMessage":[{"locale":"en_US",
                "text":"row ${'$'}index(NoSuchIndexField).value${'$'} here"}]}},
            {"type":"Rule","id":"r62","name":"MessageProjectsANonEnum","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C62","errorMessage":[{"locale":"en_US",
                "text":"the band ${'$'}Price -> Band${'$'} is not an enum"}]}},
            {"type":"Rule","id":"r61","name":"MessageIndexesAnUnreadPair","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"C61","errorMessage":[{"locale":"en_US",
                "text":"the bin ${'$'}Bins/Slot For \"S-1\"${'$'} is not read"}]}},
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
        // A sound condition beside no message at all, so the error-text class is not withheld behind a DSL one.
        CheckFamily.ERROR_TEXT_PRESENCE to ("RULE_HAS_NO_ERRORTEXT" to "/Catalog/CarriesNoMessage"),
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
        CheckFamily.FIELD_NAME_RESERVED_SEGMENT to
            ("MVK_NAME_INVALID_A12_DV" to "/Catalog/Tier.value"),
        CheckFamily.ENUM_CATEGORY_ALIGNMENT to
            ("MVK_MISSING_CATEGORY_VALUE" to "/Catalog/MiscategorisedTier"),
        CheckFamily.ENUM_VALUE_CHARACTERS to
            ("MVK_ENUM_VALUE_WITH_INVALID_CHARACTER" to "/Catalog/LowercaseTier"),
        CheckFamily.ENUM_TEXT_FORBIDDEN_CHARACTERS to
            ("MVK_INTERNAL_VALUE_INVALID" to "/Catalog/TabbedTier"),
        CheckFamily.MODEL_TEXT_REPERTOIRE to ("RK_UNSUPPORTED_CHARACTER" to "/Catalog/Glyph"),
        CheckFamily.HINT_LIST_VALUES to ("MVK_VALUE_TOO_LONG" to "/Catalog/Suggested"),
        CheckFamily.TABLE_ENUM_OPERAND to ("MVK_TABLE_ENUM_EXPECTED" to "/Catalog/TableSubject"),
        CheckFamily.STRING_PATTERN_ADMISSION to ("MVK_PATTERN_INVALID" to "/Catalog/Reference"),
        CheckFamily.STRING_PATTERN_MESSAGE to
            ("MVK_ERROR_MESSAGE_AND_PATTERN_INVALID" to "/Catalog/Unexplained"),
        CheckFamily.FIELD_TYPE_MESSAGE_TEXT to ("INVALID_PARAMETER" to "/Catalog/Tagged"),
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
        // A STRING target, so the scale gate this family stands aside for cannot be what decides the row.
        CheckFamily.COMPUTATION_SELF_READ to
            ("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD" to "/Catalog/ReadsItsOwnTarget"),
        CheckFamily.MESSAGE_PARAMETER_REFERENCE to
            ("INVALID_FIELD" to "/Catalog/MessageNamesUnread"),
        CheckFamily.ERROR_TEXT_SEMANTIC_INDEX to
            ("MVK_INDEX_FOR_ERROR_TEXT_INVALID" to "/Catalog/MessageIndexesAnUnreadPair"),
        CheckFamily.ERROR_TEXT_CATEGORY_SUFFIX to
            ("FIELD_NO_ENUM" to "/Catalog/MessageProjectsANonEnum"),
        CheckFamily.ERROR_TEXT_INDEX_TERMINAL to
            ("INVALID_INDEX_FIELD" to "/Catalog/MessageIndexesOffThePath"),
        CheckFamily.MESSAGE_TEXT_CHARACTERS to
            ("INVALID_CHARACTERS" to "/Catalog/MessageCarriesATab"),
        CheckFamily.GROUP_LIST_OPERANDS to ("MVK_DUPLICATE_PARAM1" to "/Catalog/RepeatedGroup"),
        CheckFamily.GROUP_LIST_ROOT_OPERAND to
            ("MVK_ROOT_GROUP_REFERENCED" to "/Catalog/RootOperand"),
        CheckFamily.GROUP_LIST_OPERAND_KIND to
            ("MVK_NO_FIELDS_ALLOWED" to "/Catalog/FieldWhereAGroupBelongs"),
        CheckFamily.REPETITION_WILDCARD_TARGET to
            ("MVK_INVALID_WILDCARD" to "/Catalog/StarOnAFixedGroup"),
        // A group as the key: the key list refuses it ahead of the placement gate, which `Shelf` — not
        // repeating — would fail too, so this row also shows the placement family deferring.
        CheckFamily.REPETITION_KEY_LIST to
            ("MVK_NO_GROUPS_ALLOWED" to "/Catalog/GroupAsAUniquenessKey"),
        // `Price` sits directly in the rule's own group, so no repeatable group separates the two — the
        // shape the class collapses onto, and the one that needs no second group to express.
        CheckFamily.REPETITION_KEY_PLACEMENT to
            ("MVK_REPEATABLE_GROUP_MISSING" to "/Catalog/KeyWithNoRepetition"),
        // `Shelf` is the fixture's non-repeatable group, so scoping the operator to it gives it no
        // repetition to scan — and the key placement family stays silent because a clause-carrying operand
        // is not one it reduces.
        CheckFamily.REPETITION_SCOPE_GROUP to
            ("MVK_GROUP_NOT_REPEATABLE" to "/Catalog/ScopeDoesNotRepeat"),
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
        CheckFamily.ARITHMETIC_OPERATION_GATES to
            ("MVK_TOO_MANY_DIV_FOR_CALC" to "/Catalog/CompoundFee"),
        // `False` counts only Boolean declarations and the list opens on a text one — the one call in this
        // fixture whose constant and whose field list disagree about kind.
        CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND to
            ("MVK_NO_TYPEYESNO" to "/Catalog/CountFalseOverText"),
        // A text declaration and a number one in one quantifier's field list — the only call in this
        // fixture that compares two lists whose entries are not declared alike.
        CheckFamily.VALUE_LIST_CONDITION_GATES to
            ("MVK_VARYING_TYPES_NOT_ALLOWED" to "/Catalog/ValueListOverMixedKinds"),
        // A quantifier over a single unstarred field — the one call in this fixture whose entity list is
        // shorter than the operator's own arity gate accepts.
        CheckFamily.ENTITY_LIST_ARITY to
            ("MVK_PARAMSIZE_INVALIDN" to "/Catalog/AllFieldsOverOneField"),
        // A FIELD list naming a group and a field inside it — the nested-duplicate half, on a carrier the
        // group-list family does not own, so the row fails if this family is narrowed back to group lists.
        CheckFamily.ENTITY_LIST_PRE_GATES to
            ("MVK_DUPLICATE_PARAM2" to "/Catalog/FieldListOverAGroupAndItsField"),
        // A date RANGE in a uniqueness list — the one declared type this gate refuses, so the row fails if
        // the refusal set is ever widened to the clock and stamp kinds a later gate owns.
        CheckFamily.FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND to
            ("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED" to "/Catalog/UniquenessOverADateRange"),
        // A confirm beside a text field under `FirstFilledValue` — the one kind that gate refuses, and the
        // row fails if it is ever widened to the boolean sibling the code's name suggests.
        CheckFamily.FIELD_LIST_OPERATION_OPERANDS to
            ("MVK_NO_BOOLY_ALLOWED" to "/Catalog/FirstFilledOverAConfirm"),
        // A pure clock shifted by hours — the one route to this class, since the component gate ahead of it
        // answers every calendar operator first, so the row fails if the two gates are ever reordered.
        CheckFamily.DATE_ARITHMETIC_OPERANDS to
            ("MVK_INVALID_TIME" to "/Catalog/HoursAddedToAPureClock"),
        // The year of a clock on a model with no Base Year — the format question the shift family asks of a
        // shift, asked of an extractor, so the row fails if an extractor's operand ever goes unread again.
        CheckFamily.TEMPORAL_EXTRACTOR_OPERANDS to
            ("MVK_WRONG_DATE_FORMAT_FOR_OP" to "/Catalog/YearOfAPureClock"),
        // A digit count above the ceiling, behind an operand the construct accepts — the row fails if the
        // digits gate is ever moved ahead of the operand ones, which would change what a double defect says.
        CheckFamily.ROUNDING_OPERANDS to
            ("MVK_INVALID_DIGIT_FOR_ROUNDING" to "/Catalog/RoundedToTooManyPlaces"),
        // `Length` over a NUMBER — the one class no other family reports, and the arm whose demand is the
        // opposite of `FieldValueAsString`'s, so the row fails if the two arms are ever merged.
        CheckFamily.FIELD_VALUE_OPERATOR_OPERANDS to
            ("MVK_NO_STRING" to "/Catalog/LengthOfANumber"),
        // A field-value read of a text held unparsed — the one gate of the four no comparison family asks, so
        // the row fails if the bracket's gates are ever folded into the comparison it stands in.
        CheckFamily.FIELD_VALUE_READ_OPERAND to
            ("MVK_INVALID_RAW_TYPE" to "/Catalog/ReadOfAnUnparsedText"),
        // A TEXT field in a date slot, beside a date one — so the slot's own kind gate is what answers and
        // the row fails if the family ever starts judging the carrier's format gate as well.
        CheckFamily.DATE_ARGUMENT_SLOT to
            ("MVK_NO_DATE" to "/Catalog/DaysFromAReference"),
        // A text beside a date range under the overlap operator — the per-member walk, which is what the
        // row pins: the agreement walk cannot reach a list this one already refuses.
        CheckFamily.DATE_RANGE_OPERAND_LIST to
            ("MVK_NO_DATE_RANGE" to "/Catalog/RangesAgainstAText"),
        // A number under the predefined-type arm — the kind gate, whose admitted set is narrower than the
        // code's name, so the row fails if a custom or extensible reading is ever folded into it.
        CheckFamily.VALIDITY_CONDITION_OPERANDS to
            ("MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM" to "/Catalog/CodeValidityOnANumber"),
        // A date built from a day and a month in a model declaring no reference year — the row fails if the
        // family ever starts reading the declaration as optional.
        CheckFamily.BASE_YEAR_REQUIREMENT to
            ("MVK_MISSING_YEAR" to "/Catalog/DateWithoutItsYear"),
        // A number field cut as text — the gate reads the FIELD's declaration, so this row fails the moment
        // the family starts reading the operator's result kind instead.
        CheckFamily.RANGE_OPERAND_GATES to
            ("MVK_INVALID_TYPE" to "/Catalog/CutOfANumber"),
        // One enumeration labels its values and the other does not — a claim about the PAIR, so the row
        // fails the moment the family starts deciding from one side alone.
        CheckFamily.ENUM_DISPLAY_COMPARABILITY to
            ("MVK_INVALID_COMPARE_ENUM_TO_STRING" to "/Catalog/LabelledAgainstPlain"),
        // A TIME field handed to the operator that resolves a partially known date — the kind gate reads
        // the declared precision, so the row fails the moment the family keys on A12's date INTERFACE, which
        // a time field carries.
        CheckFamily.VALUE_AS_DATE_OPERAND to
            ("MVK_INVALID_DATE_TYPE_FOR_OP" to "/Catalog/PartialDateFromATime"),
        // A computation assigning a two-value enumeration into a one-value one — coverage runs one way, so
        // the row fails the moment the family tests equality or reverses the direction.
        CheckFamily.ENUM_COMPUTATION_SOURCE to
            ("MVK_ERROR_INVALID_ENUM_CALCULATION" to "/Catalog/NarrowedGrade"),
        // Two text declarations under an ordering operator — the one comparison in this fixture whose
        // operator demands more of its sides than they are.
        CheckFamily.COMPARISON_OPERATOR_OPERANDS to
            ("MVK_INVALID_TYPE_FOR_COMPARISON" to "/Catalog/OrderedTextComparison"),
        // A confirm against `False` — the asymmetry itself, and the one partner a boolean would admit, so
        // the row fails the moment the two kinds are collapsed.
        CheckFamily.BOOLEAN_COMPARISON_AGREEMENT to
            ("MVK_INVALID_COMPARE_TO_YES" to "/Catalog/ConfirmAgainstFalse"),
        // A text declaration against a number one — the kind question alone, with no enumeration in it, so
        // the row cannot be satisfied by the domain half of the same walk.
        CheckFamily.STRING_ENUM_COMPARISON_AGREEMENT to
            ("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING" to "/Catalog/TextAgainstANumber"),
        // Two NUMBER declarations of different scale under `==` — no literal on either side, so the row
        // cannot be satisfied by the rescaling waiver that admits a differing literal.
        CheckFamily.NUMBER_COMPARISON_SCALE to
            ("MVK_INVALID_COMPARE_DEC_PLACES" to "/Catalog/ScaleTwoAgainstAWholeField"),
        // A date against a TEXT declaration — the date arm claiming a partner the text arm would otherwise
        // own, so the row fails the moment the two arms' precedence is read the other way round.
        CheckFamily.TEMPORAL_COMPARISON_AGREEMENT to
            ("MVK_INVALID_COMPARE_TO_DATE" to "/Catalog/DateAgainstATextField"),
        // A number declaration matched against a written-out pattern — the KIND question, with a plain
        // field read on the left, so the row cannot be satisfied by the shape question ahead of it.
        CheckFamily.PATTERN_COMPARISON_OPERANDS to
            ("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON" to "/Catalog/PatternOverANumber"),
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
        // The marker on a reference that RESOLVES, outside any filter: resolution answers before this gate,
        // so a marked path naming nothing would trigger the reference family instead of this one.
        // A complete text but for the closing dollar, and no control character anywhere: the character gate
        // pre-empts this one, so a row carrying both would be evidence for the wrong family.
        // The rule sits in `/Catalog`, so a group BESIDE it does not contain it — and `Aisle` is declared,
        // which keeps the row on containment rather than on the root gate or on a missing address.
        CheckFamily.MESSAGE_GROUP_PARAMETER to
            ("INVALID_GROUP" to "/Catalog/NamesAGroupBesideTheRule"),
        // The condition reads the field, so membership is satisfied and the suffix's own gates are what is
        // left — and the field is read WITHOUT a star, which leaves the declaration gate as the one answering
        // rather than the star gate that pre-empts it.
        CheckFamily.MESSAGE_VALUE_SUFFIX to
            ("INVALID_NO_VALUE_VALIDATION" to "/Catalog/SuffixesAValueFreeField"),
        CheckFamily.MESSAGE_PARAMETER_SYNTAX to
            ("WRONG_NUMBER_OF_DOLLARS" to "/Catalog/OpensAParameterItNeverCloses"),
        // The catalog declares no Base Year, which the year-less date row above relies on too.
        CheckFamily.MESSAGE_BASE_YEAR to ("NO_BASE_YEAR" to "/Catalog/NamesTheBaseYear"),
        CheckFamily.OUTER_MARKER_POSITION to
            ("MVK_INVALID_OUTER_ITERATION" to "/Catalog/MarksAnOuterIterationOutsideAFilter"),
        CheckFamily.FILTER_BODY_GATES to
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
        // `MiscategorisedTier` declares the category `Band`, so the spec's own category gate passes and only
        // the count's list gate is left to refuse it.
        CheckFamily.ENTITY_LIST_CATEGORY_GATE to
            ("MVK_CATEGORY_NOT_ALLOWED" to "/Catalog/CategoryInACount"),
        // `LowercaseTier` declares exactly one value, so the list carries one constant A12 admits and one it
        // does not — which keeps the row about the CONSTANT rather than about an empty or unreadable domain.
        CheckFamily.SCALAR_VALUE_LIST_GATES to
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
        // The same extent, the other way round: the rule reads one pallet at a time and reports on a field
        // ABOVE its own group, so A12 cannot say which pallet the error belongs to and refuses during the
        // parse — ahead of the class the row above measures.
        CheckFamily.ERROR_TARGET_CONTAINMENT to
            ("MVK_ERROR_FIELD_NOT_IN_RULEGROUP" to "/Catalog/Pallets/WritesOutsideItsExtent"),
    )

    /**
     * A second, GERMAN-language model, for the families whose law an English model cannot reach.
     *
     * The H-model form spellings exist in no English keyword bundle, so the fixture above can carry no
     * declaration that triggers them — flipping its condition language instead would unspell every other
     * row's operator. One extra model is the cheaper half of that trade, and it is where the next
     * German-only family lands too.
     */
    private val germanSource = ModelSource(
        """
        {"header":{"id":"wiring-de","locales":[{"code":"de_DE"}]},
         "content":{"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"gd0","name":"Bestellung","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"fd0","name":"Kennzeichen","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"rd0","name":"FormularRegel","Rule":{
              "errorCondition":"VordruckAngegeben(Bestellung)",
              "errorEntityRelPath":"../Kennzeichen",
              "errorCode":"D0","errorMessage":[{"locale":"de_DE","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /** What the German model proves, in the same (code, at) shape as [evidence]. */
    private val germanEvidence = mapOf(
        CheckFamily.H_MODEL_FORM_CONSTRUCT to
            ("MVK_INVALID_ROOT_GROUP_CONSTR" to "/Bestellung/FormularRegel"),
    )

    /**
     * A model that draws NOTHING but a dependency cycle, because A12 draws the cycle only on a model it
     * otherwise admits: its code generator raises it, and any other class withholds it
     * (`rulekit.validate.laws.CalculationMemberWithholdingLawsTest`). The fixture above draws a class per
     * family, so it can carry no cycle the report would show.
     */
    private val cycleSource = ModelSource(
        """
        {"header":{"id":"wiring-cycle","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"gc0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"fc0","name":"Tare","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"fc1","name":"Gross","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Computation","id":"cc0","name":"GrossCalc","Computation":{
              "computedFieldRelPath":"../Gross",
              "computationAlternatives":[{"operation":"[Tare] + 1"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Computation","id":"cc1","name":"TareCalc","Computation":{
              "computedFieldRelPath":"../Tare",
              "computationAlternatives":[{"operation":"[Gross] * 2"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /**
     * What the cycle model proves. The circle's report is anchored at the computation writing its smallest
     * member, so the path is `Gross`'s writer rather than either declaration's position in the file.
     */
    private val cycleEvidence = mapOf(
        CheckFamily.COMPUTATION_CYCLE to ("MVK_ERROR_CALC_CYCLE" to "/Catalog/GrossCalc"),
    )

    /** The models beside [source], each carrying the families only it can reach, with what each proves. */
    private val sideModels = listOf(germanSource to germanEvidence, cycleSource to cycleEvidence)

    @Test
    fun theFieldReadProducerOwnsTheFilterRead() {
        val admitted = admit(source)
        assertIs<Admission.Admitted>(admitted)
        val tracked = TrackedFactBase(prepare(admitted), ReadTranscript())
        val findings = schedule(tracked)
            .filter { CheckFamily.FIELD_VALUE_READ_OPERAND in it.key.producer.completes }
            .flatMap { it.produce().findings }
            .filter { it.at == "/Catalog/StarInsideAFilter" }
        assertEquals(listOf("MVK_NO_WILDCARDS_ALLOWED"), findings.map { it.code })
        assertEquals("errorCondition", findings.single().span?.member)
    }

    @Test
    fun postParseProducersRetainTheirOwnerAndStage() {
        val facts = TrackedFactBase(prepare(assertIs<Admission.Admitted>(admit(source))), ReadTranscript())
        val producers: List<Triple<CheckFamily, PostParseCheck,
            (TrackedDeclaration) -> List<ConditionFinding>>> = listOf(
            Triple(CheckFamily.PARALLEL_ITERATION_UNIQUENESS, PostParseCheck.PARALLEL_ITERATION,
                { facts.parallelIterationContributions(it) }),
            Triple(CheckFamily.FILTERED_ENTITY_ITERATION, PostParseCheck.FILTERED_ENTITY,
                { facts.filteredEntityContributions(it) }),
            Triple(CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION, PostParseCheck.REPEATED_UNIQUENESS,
                { facts.repeatedUniquenessContributions(it) }),
            Triple(CheckFamily.RAW_TYPE_LENGTH, PostParseCheck.RAW_LENGTH,
                { facts.rawLengthContributions(it) }),
            Triple(CheckFamily.ERROR_FIELD_REFERENCE, PostParseCheck.ERROR_TARGET_ITERATION,
                { facts.errorFieldReferenceContributions(it) }),
            Triple(CheckFamily.ERROR_TARGET_ITERATION, PostParseCheck.ERROR_TARGET_ITERATION,
                { facts.errorTargetIterationContributions(it) }),
        )
        for ((family, site, produce) in producers) {
            val (code, path) = evidence.getValue(family)
            val element = facts.declarations().single { it.path == path }
            val contribution = produce(element).single()
            assertEquals(code, contribution.finding.code, family.name)
            assertEquals(path, contribution.finding.at, family.name)
            assertEquals(element.key, contribution.owner, family.name)
            assertEquals(site, contribution.site, family.name)
        }
    }

    @Test
    fun everySideModelFamilyReachesTheReport() {
        sideModels.forEach { (model, rows) ->
            val present = checkRead(model).findings.map { it.code to it.at }.toSet()
            assertEquals(rows.mapValues { true }, rows.mapValues { (_, row) -> row in present },
                "a family only a side model reaches reports nothing while checksCompleted still names it")
        }
    }

    @Test
    fun everyAdvertisedFamilyReachesTheReport() {
        val report = checkRead(source)
        val present = report.findings.map { it.code to it.at }.toSet()
        val actual = evidence.mapValues { (_, row) -> row in present }
        assertEquals(evidence.mapValues { true }, actual,
            "a family whose wire into checkRead() is missing reports nothing while checksCompleted still names it")
    }

    /**
     * The variant family is the exception by design: its output is a COVERAGE GAP, because A12 accepts the
     * declaration it concerns. Its wire therefore has to be checked on the gap channel, not the finding one.
     */
    @Test
    fun theVariantFamilyReachesTheReportThroughItsGap() {
        assertTrue(checkRead(source).gaps.any { it.at == "/Catalog/Filtered" && it.reason.contains("ONLY") },
            "the ONLY-filtered declaration must be disclosed as unexamined")
    }

    /** Every family is triggered by something above — the half a per-family test cannot supply. */
    @Test
    fun everyFamilyIsAccountedFor() {
        assertEquals(CheckFamily.entries.toSet(),
            evidence.keys + sideModels.flatMap { (_, rows) -> rows.keys } + CheckFamily.VARIANT_FILTER,
            "a new CheckFamily constant needs a declaration here that triggers it, not only a call site")
    }

    /** …and `checksCompleted` names exactly the families this model proves ran. */
    @Test
    fun checksCompletedNamesTheFamiliesThatRan() {
        assertEquals(CheckFamily.entries.toSet(), checkRead(source).checksCompleted,
            "checksCompleted is evidence, so it may not name a family the report never carried")
    }

    /**
     * That each registration in [CHECK_PRODUCERS] produces the families it CLAIMS, and no others.
     *
     * **The guard the registry needs, and the reason the table above is what can supply it.** Pairing a
     * hundred and three constants with ninety-eight producers is a hundred and three acts of care, and a
     * mis-pairing is invisible to every other test here: `checksCompleted` would still name every family,
     * because the UNION is right however the pairs are scrambled, and each family's own test calls its
     * function directly and never asks who claims it. What discriminates is this file's (code, path)
     * evidence, which already attributes one finding to one family — so running a single registration and
     * asking which rows it carries settles the pairing mechanically.
     *
     * **Both directions, because they fail differently.** A family claimed by the wrong producer is keyed on
     * the wrong transcript, so L-b3b would reuse its verdict across an edit that should have felled it —
     * unsound. A family claimed by NO producer only under-reports, which is why it needs the inverse check
     * to be seen at all.
     *
     * A registration is run raw here, before occurrence-owned report selection, so its output is a superset
     * of what the report carries — which is the right side to be on: a claimed row must appear, and a row
     * belonging to another family must not, whether or not a later filter would have dropped it.
     *
     */
    @Test
    fun eachRegistrationProducesExactlyTheFamiliesItClaims() {
        val misattributed = mutableListOf<String>()
        (listOf(source to evidence) + sideModels).forEach { (model, rows) ->
            val admitted = admit(model)
            assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
            val tracked = TrackedFactBase(prepare(admitted), ReadTranscript())
            val byProducer = schedule(tracked)
                .groupBy({ it.key.producer }, { it.produce().findings })
                .mapValues { (_, runs) -> runs.flatten() }
            byProducer.forEach { (producer, findings) ->
                val produced = findings.map { it.code to it.at }.toSet()
                rows.forEach { (family, row) ->
                    val claimed = family in producer.completes
                    if (claimed && row !in produced) {
                        misattributed += "$family is claimed by a producer that does not report it"
                    }
                    if (!claimed && row in produced) {
                        misattributed += "$family is reported by a producer that claims ${producer.completes}"
                    }
                }
            }
        }
        assertEquals(emptyList(), misattributed,
            "a CheckFamily is paired with the wrong producer, so its cell would be keyed on the wrong reads")
    }

    @Test
    fun aWalkingFamilyAskedPerDeclarationSaysWhatItSaidOverTheWholeModel() {
        val differing = mutableListOf<String>()
        // This class's own two fixtures, plus every model in the parity corpus: the fixtures guarantee each
        // family is reached at all, and the corpus widens the shapes it is reached on — a family whose scoped
        // pass differs only where a kind appears twice would pass on a fixture that declares it once.
        val corpus = ParityCorpusJson.cases().flatMap { it.sources }.distinct()
            .map { ModelSource(ParityCorpusJson.source(it)) }
        var examined = 0
        (listOf(source) + sideModels.map { (model, _) -> model } + corpus).forEach { model ->
            if (admit(model) !is Admission.Admitted) return@forEach
            examined++
            // The comparison itself lives in [scopedWalkDivergences], shared with
            // `ScopedWalkBranchDiscriminatorsTest`: this row varies the POPULATION and that one varies the
            // BRANCH, and two copies of the comparison could have drifted on what "differently" means.
            differing += scopedWalkDivergences(model)
        }

        // The equivalence [CheckProducer.PerDeclarationWalk] CLAIMS, run as a differential rather than
        // argued: a family narrowed to one declaration must produce, over the walk, exactly the list it
        // produced over the model — same findings, same order. Nothing else can see the failure this
        // catches. A family that uses the walk to look up ANOTHER declaration finds nothing in a scoped
        // base, so it silently under-reports while charging no set shape, which is invisible to the
        // charge-profile invariant.
        assertEquals(emptyList(), differing,
            "a family asked per declaration answered differently from the same family over the model")

        // Two controls. Non-vacuity per FAMILY is supplied by `everyAdvertisedFamilyReachesTheReport`
        // rather than asserted again here — it already fails unless every family reports its own row on one
        // of the two fixtures, so no producer above is compared on two empty lists throughout. What that
        // test cannot supply is that the registrations and the corpus are there at all.
        assertTrue(
            CHECK_PRODUCERS.any { it is CheckProducer.PerDeclarationWalk },
            "no family is registered as a scoped walk, so this differential covers nothing",
        )
        assertTrue(examined > 2, "only the two fixtures were examined, so the corpus reached this arm empty")
    }

    @Test
    fun onlyDeclaredTargetsReachTheReportWithoutACell() {
        // R5 condition references carry occurrence/site identity in cells; only target resolution is composed.
        assertEquals(
            setOf(CheckFamily.DECLARED_REFERENCES),
            COMPOSED_FAMILIES,
            "a family outside the registry bypasses the cell store; register it as a producer instead",
        )

        // …and the other direction, so the pin cannot be satisfied by shrinking the denominator: every
        // family is either composed or registered, which is what makes the two sets a partition.
        assertEquals(
            CheckFamily.entries.toSet(),
            COMPOSED_FAMILIES + CHECK_PRODUCERS.flatMap { it.completes },
            "every CheckFamily must be composed or registered, or it is advertised by neither",
        )
    }
}
