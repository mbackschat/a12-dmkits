package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/** Positive model-check laws for catalog operand domains that runtime differentials cannot establish. */
class OperatorAdmissionLawsTest {

    @Test
    void fieldPresenceQuantifiersAcceptFieldListsAndRecursiveGroupScopes() {
        for (String operator : new String[] {
                "AllFieldsFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "NotAllFieldsFilled",
                "FieldsNotCollectivelyFilled", "NotExactlyOneFieldFilled", "MoreThanOneFieldFilled"
        }) {
            KernelLaws.acceptsOrder("/Order", "../CustomerName",
                    operator + "(CustomerName, ProductCode)");
            KernelLaws.acceptsOrder("/Order", "../BillingAddress/PostalCode",
                    operator + "(BillingAddress)");
        }
    }

    @Test
    void groupNamedQuantifiersAcceptMixedAndFieldOnlyEntityLists() {
        for (String operator : new String[] {
                "AllGroupsFilled", "NoGroupFilled", "AtLeastOneGroupFilled",
                "NotAllGroupsFilled", "GroupsNotCollectivelyFilled"
        }) {
            KernelLaws.acceptsOrder("/Order", "../CustomerName",
                    operator + "(ShippingAddress, CustomerName)");
            KernelLaws.acceptsOrder("/Order", "../CustomerName",
                    operator + "(CustomerName, ProductCode)");
        }
    }

    @Test
    void countAndUniquenessOperatorsAcceptTheirPublishedOperandShapes() {
        for (String[] testCase : new String[][] {
                { "../CustomerName", "NumberOfFilledFields(CustomerName, Quantity) >= 0" },
                { "../BillingAddress/PostalCode", "NumberOfFilledFields(BillingAddress) >= 0" },
                { "../Items/Count", "NumberOfFilledFields(Items*/Count) >= 0" },
                { "../ShippingAddress/Street", "NumberOfFilledGroups(ShippingAddress, BillingAddress) >= 0" },
                { "../Items/Count", "NumberOfFilledGroups(Items*) >= 0" },
                { "../CustomerName", "FieldValuesNotUnique(CustomerName, ProductCode)" },
                { "../Items/Count", "FieldValuesNotUnique(Items*/Count)" }
        }) {
            KernelLaws.acceptsOrder("/Order", testCase[0], testCase[1]);
        }
    }

    @Test
    void scalarMinAndMaxAcceptComparableTemporalOperands() {
        for (String operator : new String[] { "Min", "Max" }) {
            KernelLaws.acceptsOrder("/Order", "../OrderDate",
                    operator + "([OrderDate], [DeliveryDate]) <= [DeliveryDate]");
            KernelLaws.acceptsOrder("/Order", "../PickupTime",
                    operator + "([PickupTime], Time(\"12\", \"00\")) <= [PickupTime]");
            KernelLaws.acceptsOrder("/Order", "../ScheduledAt",
                    operator + "([ScheduledAt], [CompletedAt]) <= [CompletedAt]");
        }
    }

    @Test
    void currentRepetitionAcceptsANonRepeatableGroup() {
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And CurrentRepetition(ShippingAddress) == 1");
    }

    @Test
    void dateAndDateTimeSourcesAreAdmittedByTheirCalendarShiftOperators() {
        KernelLaws.acceptsOrder("/Order", "../OrderDate", "AddDays(OrderDate, -1) < [DeliveryDate]");
        KernelLaws.acceptsOrder("/Order", "../ScheduledAt", "AddDays(ScheduledAt, -1) < [CompletedAt]");
        KernelLaws.acceptsOrder("/Order", "../ScheduledAt", "AddHours(ScheduledAt, -1) < [CompletedAt]");
    }

    @Test
    void unregisteredPredefinedTypeNamesAreAcceptedForBothPolarities() {
        String model = DocumentModelDraft.named("typed-value").locale("en_US")
                .rootGroup("Value", root -> root.stringField("Code", field -> { }))
                .build().toJson();
        KernelLaws.acceptsModel(model, "/Value", "../Code",
                "FieldFilled(Code) And Valid(Code, \"NotRegistered\")");
        KernelLaws.acceptsModel(model, "/Value", "../Code",
                "FieldFilled(Code) And Invalid(Code, \"NotRegistered\")");
    }

    @Test
    void semanticIndexesProjectEveryPublishedValueFamily() {
        String model = DocumentModelDraft.named("indexed-values").locale("en_US")
                .rootGroup("Selection", root -> root
                        .numberField("NumberRef", field -> { })
                        .stringField("StringRef", field -> { })
                        .customField("CustomRef", field -> field.type("SelectionCode"))
                        .enumField("EnumRef", field -> field.value("A").value("B"))
                        .dateField("DateRef", field -> field.format("yyyy-MM-dd"))
                        .dateFragmentField("MonthRef", field -> field.fragmentFormat("MM"))
                        .timeField("TimeRef", field -> field.format("HH:mm:ss"))
                        .dateTimeField("InstantRef", field -> field.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .group("Rows", rows -> rows.repeatable(10).indexField("Key")
                                .stringField("Key", field -> { })
                                .numberField("NumberValue", field -> { })
                                .stringField("StringValue", field -> { })
                                .customField("CustomValue", field -> field.type("SelectionCode"))
                                .enumField("EnumValue", field -> field.value("A").value("B"))
                                .dateField("DateValue", field -> field.format("yyyy-MM-dd"))
                                .dateFragmentField("MonthValue", field -> field.fragmentFormat("MM"))
                                .timeField("TimeValue", field -> field.format("HH:mm:ss"))
                                .dateTimeField("InstantValue", field ->
                                        field.format("yyyy-MM-dd'T'HH:mm:ss"))))
                .build().toJson();
        for (String[] testCase : new String[][] {
                { "../NumberRef", "[Rows/NumberValue For \"K\"] == [NumberRef]" },
                { "../StringRef", "[Rows/StringValue For \"K\"] == [StringRef]" },
                { "../CustomRef", "[Rows/CustomValue For \"K\"] == [CustomRef]" },
                { "../EnumRef", "[Rows/EnumValue For \"K\"] == [EnumRef]" },
                { "../DateRef", "[Rows/DateValue For \"K\"] == [DateRef]" },
                { "../MonthRef", "[Rows/MonthValue For \"K\"] == [MonthRef]" },
                { "../TimeRef", "[Rows/TimeValue For \"K\"] == [TimeRef]" },
                { "../InstantRef", "[Rows/InstantValue For \"K\"] == [InstantRef]" }
        }) {
            KernelLaws.acceptsModel(model, "/Selection", testCase[0], testCase[1]);
        }
    }

    @Test
    void firstFilledValueAcceptsTheOwnerEntitySpecUnionAndCompatibleFamilies() {
        String model = DocumentModelDraft.named("selection").locale("en_US")
                .rootGroup("Selection", root -> root
                        .numberField("Fallback", field -> { })
                        .stringField("Expected", field -> { })
                        .customField("CustomCode", field -> field.type("SelectionCode"))
                        .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                .category("Speed", "NORMAL", "FAST"))
                        .dateField("MonthDate", field -> field.format("MM"))
                        .dateFragmentField("Month", field -> field.fragmentFormat("MM"))
                        .group("Address", address -> address
                                .stringField("Street", field -> { })
                                .stringField("City", field -> { }))
                        .group("NumberRows", rows -> rows.repeatable(10)
                                .numberField("Primary", field -> { })
                                .numberField("Secondary", field -> { }))
                        .group("IndexedNumbers", rows -> rows.repeatable(10).indexField("Key")
                                .numberField("Key", field -> { })
                                .numberField("Primary", field -> { })
                                .numberField("Secondary", field -> { })))
                .build().toJson();
        for (String[] testCase : new String[][] {
                { "../Address/Street", "FirstFilledValue(Address) == \"x\"" },
                { "../NumberRows/Primary", "FirstFilledValue(NumberRows*) > 0" },
                { "../Fallback", "FirstFilledValue(IndexedNumbers/Primary For \"1\", Fallback) > 0" },
                { "../Fallback", "FirstFilledValue(IndexedNumbers/Primary For Fallback, Fallback) > 0" },
                { "../Expected", "FirstFilledValue(Priority -> Speed, Expected) == \"FAST\"" },
                { "../Expected", "FirstFilledValue(CustomCode, Expected, Priority) == \"FAST\"" },
                { "../Month", "FirstFilledValue(Month, MonthDate) == [Month]" },
                { "../IndexedNumbers/Primary", "FirstFilledValue(IndexedNumbers For \"1\") > 0" },
                { "../Fallback", "FirstFilledValue(IndexedNumbers For Fallback) > 0" }
        }) {
            KernelLaws.acceptsModel(model, "/Selection", testCase[0], testCase[1]);
        }
    }

    @Test
    void firstFilledValuePreservesComposableEntitySpecModifiers() {
        String model = DocumentModelDraft.named("selection-modifiers").locale("en_US")
                .rootGroup("Selection", root -> root
                        .stringField("Expected", field -> { })
                        .group("IndexedRows", rows -> rows.repeatable(10).indexField("Key")
                                .stringField("Key", field -> { })
                                .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                        .category("Speed", "NORMAL", "FAST")))
                        .group("FilteredRows", rows -> rows.repeatable(10)
                                .stringField("Key", field -> { })
                                .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                        .category("Speed", "NORMAL", "FAST"))))
                .build().toJson();

        KernelLaws.acceptsModel(model, "/Selection", "../Expected",
                "FirstFilledValue(IndexedRows/Priority -> Speed For \"MATCH\", Expected) == \"FAST\"");
        KernelLaws.acceptsModel(model, "/Selection", "../Expected",
                "FirstFilledValue(FilteredRows*/Priority -> Speed Having "
                        + "[FilteredRows/Key] == \"MATCH\", Expected) == \"FAST\"");
        KernelLaws.rejectsModel(model, "/Selection", "../Expected",
                "FirstFilledValue(IndexedRows*/Priority For \"MATCH\", Expected) == \"EXPRESS\"",
                "MVK_SEMANTIC_INDEX_AND_WILDCARD");
    }
}
