package io.github.mbackschat.a12.dm.consistency

/**
 * [CheckFamilyWiringTest]'s main model: one declaration broken per family, each at its own path, so the
 * (code, path) pair attributes a finding to the family that made it. Kept apart from the test so the evidence
 * rows and the assertions stay readable beside each other.
 */
internal val WIRING_MODEL = ModelSource(
    """
    {"header":{"modelVersion":"28.4.0","id":"wiring","locales":[{"code":"en_US"}]},
     "content":{"modelInfo":{"name":"model"},"modelConfig":{"supportedCharacters":["A-Z"]},"modelRoot":{"rootGroups":[
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
        {"type":"Rule","id":"r192","name":"StampFromTwoClocks","Rule":{
          "errorCondition":"FieldFilled(Price) And DateTime(ShiftStart, ShiftStart) == DateTime(TravelDate, ShiftStart)",
          "errorEntityRelPath":"../Price",
          "errorCode":"C192","errorMessage":[{"locale":"en_US","text":"x"}]}},
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
        {"type":"Rule","id":"r190","name":"StarredErrorFieldUnbound","Rule":{
          "errorCondition":"FieldFilled(Pallets/PalletQty) And NumberOfFilledFields(Pallets*/PalletNote) > 0",
          "errorEntityRelPath":"../Pallets/PalletNote",
          "errorCode":"C190","errorMessage":[{"locale":"en_US","text":"x"}]}},
        {"type":"Rule","id":"r191","name":"PalletNoteMissing","Rule":{
          "errorCondition":"FieldNotFilled(Pallets/PalletNote)",
          "errorEntityRelPath":"../Pallets/PalletNote",
          "errorCode":"C191","errorMessage":[{"locale":"en_US","text":"x"}]}},
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
