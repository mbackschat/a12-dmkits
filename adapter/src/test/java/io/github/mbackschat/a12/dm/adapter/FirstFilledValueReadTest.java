package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * F10 read tail — {@code FirstFilledValue(…)} over a repetition list, both result types: a number scope
 * yields a number, a date scope yields a date (Corpus-1's largest residual bucket after the comparison-RHS
 * work). The converter routes the number variant through {@code NumberExpr} and the date variant through
 * {@code DateExpr}, sharing one {@code FirstFilledValue} node whose kind follows its scope. Own domain: an
 * employee whose stored "first contract" roll-ups must equal the first specified contract row.
 */
class FirstFilledValueReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String employeeJson() {
        return DocumentModelDraft.named("employee").locale("en_US")
                .rootGroup("Employee", e -> e
                        .numberField("FirstContractValue", n -> { })
                        .dateField("FirstContractDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("FallbackContractDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("FirstContractMonthDate", d -> d.format("yyyy-MM"))
                        .stringField("FirstContractCode", s -> { })
                        .enumField("FirstContractType", t -> t.value("FULL").value("PART")
                                .category("Band", "PRIMARY", "SECONDARY"))
                        .field("FirstContractApproved", FieldKind.BOOLEAN)
                        .customField("FirstContractCodeType", c -> c.type("ContractCode"))
                        .customField("FallbackContractCodeType", c -> c.type("ContractCode"))
                        .dateFragmentField("FirstContractMonth", d -> d.fragmentFormat("yyyy-MM"))
                        .timeField("FirstContractTime", t -> t.format("HH:mm:ss"))
                        .dateTimeField("FirstContractMoment", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateRangeField("FirstContractWindow", d -> d.format("yyyy-MM-dd").separator("/"))
                        .group("FallbackCodes", g -> g
                                .stringField("Primary", s -> { })
                                .stringField("Secondary", s -> { }))
                        .group("NumberChoices", g -> g.repeatable(20)
                                .numberField("Primary", n -> { })
                                .numberField("Secondary", n -> { }))
                        .group("IndexedNumberChoices", g -> g.repeatable(20).indexField("Key")
                                .numberField("Key", n -> { })
                                .numberField("Primary", n -> { })
                                .numberField("Secondary", n -> { }))
                        .group("Contracts", g -> g
                                .repeatable(20).indexField("Code")
                                .numberField("Value", n -> { })
                                .dateField("StartDate", d -> d.format("yyyy-MM-dd"))
                                .stringField("Code", s -> { })
                                .enumField("Type", t -> t.value("FULL").value("PART"))
                                .field("Approved", FieldKind.BOOLEAN)
                                .customField("CodeType", c -> c.type("ContractCode"))
                                .dateFragmentField("Month", d -> d.fragmentFormat("yyyy-MM"))
                                .dateField("MonthDate", d -> d.format("yyyy-MM"))
                                .timeField("StartTime", t -> t.format("HH:mm:ss"))
                                .dateTimeField("StartedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                                .dateRangeField("Window", d -> d.format("yyyy-MM-dd").separator("/"))))
                .build().toJson();
    }

    @Test
    void roundTripsNumberAndDateFirstFilledValue() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition num = m.num("/Employee/FirstContractValue")
                .ne(Dsl.firstFilledNumber(m.flatten("/Employee/Contracts*/Value")));
        Condition date = m.date("/Employee/FirstContractDate")
                .ne(Dsl.firstFilledDate(m.flatten("/Employee/Contracts*/StartDate")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstValueRule", "/Employee/FirstContractValue", num))
                .add(rule(m, "FirstDateRule", "/Employee/FirstContractDate", date)));
        assertEquals(RENDER.render(num), RENDER.render(converted(adapter.read("/Employee/FirstValueRule"))));
        assertEquals(RENDER.render(date), RENDER.render(converted(adapter.read("/Employee/FirstDateRule"))));
    }

    @Test
    void roundTripsADateFirstFilledValueOnTheLeftOfAComparison() {
        // Corpus shape: FirstFilledValue(dates*) > [dateField] — the date-valued aggregate is the LEFT
        // operand (was mis-routed to the number builder and rejected: "needs a NUMBER field list").
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition full = m.date("/Employee/FirstContractDate").filled()
                .and(Dsl.firstFilledDate(m.flatten("/Employee/Contracts*/StartDate"))
                        .after(m.date("/Employee/FirstContractDate")));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FirstDateLeftRule", "/Employee/FirstContractDate", full)));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.read("/Employee/FirstDateLeftRule"))));
    }

    @Test
    void roundTripsStringFirstFilledValueAsComparandAndConcatPart() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        // string FirstFilledValue on the right of a string comparison
        Condition cmp = m.str("/Employee/FirstContractCode")
                .ne(Dsl.firstFilledString(m.flatten("/Employee/Contracts*/Code")));
        // string FirstFilledValue as a concatenation part
        Condition concat = m.str("/Employee/FirstContractCode")
                .ne(Dsl.firstFilledString(m.flatten("/Employee/Contracts*/Code")).plus("-X"));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstCodeRule", "/Employee/FirstContractCode", cmp))
                .add(rule(m, "FirstCodeConcatRule", "/Employee/FirstContractCode", concat)));
        assertEquals(RENDER.render(cmp), RENDER.render(converted(adapter.read("/Employee/FirstCodeRule"))));
        assertEquals(RENDER.render(concat), RENDER.render(converted(adapter.read("/Employee/FirstCodeConcatRule"))));
    }

    @Test
    void roundTripsEnumFirstFilledValue() {
        // Corpus shape: [enumField] != FirstFilledValue(enums*) — the enum-valued FirstFilledValue on the right.
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition full = m.enumOf("/Employee/FirstContractType").filled()
                .and(m.enumOf("/Employee/FirstContractType")
                        .isNot(Dsl.firstFilledEnum(m.flatten("/Employee/Contracts*/Type"))));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FirstTypeRule", "/Employee/FirstContractType", full)));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.read("/Employee/FirstTypeRule"))));
    }

    @Test
    void roundTripsEveryRemainingOwnerAdmittedKind() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition bool = m.field("/Employee/FirstContractApproved").filled().and(
                Dsl.firstFilledBoolean(m.flatten("/Employee/Contracts*/Approved")).isTrue());
        Condition custom = m.custom("/Employee/FirstContractCodeType").ne(
                Dsl.firstFilledCustom(m.flatten("/Employee/Contracts*/CodeType")));
        Condition fragment = m.dateFragment("/Employee/FirstContractMonth").ne(
                Dsl.firstFilledDateFragment(m.flatten("/Employee/Contracts*/Month")));
        Condition time = m.time("/Employee/FirstContractTime").ne(
                Dsl.firstFilledTime(m.flatten("/Employee/Contracts*/StartTime")));
        Condition dateTime = m.dateTime("/Employee/FirstContractMoment").ne(
                Dsl.firstFilledDateTime(m.flatten("/Employee/Contracts*/StartedAt")));
        Condition dateRange = m.dateRange("/Employee/FirstContractWindow").ne(
                Dsl.firstFilledDateRange(m.flatten("/Employee/Contracts*/Window")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstApprovedRule", "/Employee/FirstContractApproved", bool))
                .add(rule(m, "FirstCodeTypeRule", "/Employee/FirstContractCodeType", custom))
                .add(rule(m, "FirstMonthRule", "/Employee/FirstContractMonth", fragment))
                .add(rule(m, "FirstTimeRule", "/Employee/FirstContractTime", time))
                .add(rule(m, "FirstMomentRule", "/Employee/FirstContractMoment", dateTime))
                .add(rule(m, "FirstWindowRule", "/Employee/FirstContractWindow", dateRange)));

        assertEquals(RENDER.render(bool), RENDER.render(converted(adapter.read("/Employee/FirstApprovedRule"))));
        assertEquals(RENDER.render(custom), RENDER.render(converted(adapter.read("/Employee/FirstCodeTypeRule"))));
        assertEquals(RENDER.render(fragment), RENDER.render(converted(adapter.read("/Employee/FirstMonthRule"))));
        assertEquals(RENDER.render(time), RENDER.render(converted(adapter.read("/Employee/FirstTimeRule"))));
        assertEquals(RENDER.render(dateTime), RENDER.render(converted(adapter.read("/Employee/FirstMomentRule"))));
        assertEquals(RENDER.render(dateRange), RENDER.render(converted(adapter.read("/Employee/FirstWindowRule"))));
    }

    @Test
    void roundTripsTheOwnerStringAndDateFamilyUnions() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition stringFamily = m.custom("/Employee/FirstContractCodeType").ne(Dsl.firstFilledCustom(
                m.flatten("/Employee/Contracts*/CodeType"),
                m.flatten("/Employee/Contracts*/Code"),
                m.flatten("/Employee/Contracts*/Type")));
        Condition dateFamily = m.dateFragment("/Employee/FirstContractMonth").ne(Dsl.firstFilledDateFragment(
                m.flatten("/Employee/Contracts*/Month"),
                m.flatten("/Employee/Contracts*/MonthDate")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstStringFamilyRule", "/Employee/FirstContractCodeType", stringFamily))
                .add(rule(m, "FirstDateFamilyRule", "/Employee/FirstContractMonth", dateFamily)));

        assertEquals(RENDER.render(stringFamily),
                RENDER.render(converted(adapter.read("/Employee/FirstStringFamilyRule"))));
        assertEquals(RENDER.render(dateFamily),
                RENDER.render(converted(adapter.read("/Employee/FirstDateFamilyRule"))));
    }

    @Test
    void roundTripsTheOwnerScalarFirstForm() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition dates = Dsl.firstFilledDate(
                m.date("/Employee/FirstContractDate").field(),
                m.date("/Employee/FallbackContractDate").field())
                .after(m.date("/Employee/FirstContractDate"));
        Condition strings = m.custom("/Employee/FirstContractCodeType").ne(Dsl.firstFilledCustom(
                m.custom("/Employee/FirstContractCodeType").field(),
                m.str("/Employee/FirstContractCode").field(),
                m.enumOf("/Employee/FirstContractType").field()));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstScalarDateRule", "/Employee/FirstContractDate", dates))
                .add(rule(m, "FirstScalarStringRule", "/Employee/FirstContractCodeType", strings)));

        assertEquals(RENDER.render(dates),
                RENDER.render(converted(adapter.read("/Employee/FirstScalarDateRule"))));
        assertEquals(RENDER.render(strings),
                RENDER.render(converted(adapter.read("/Employee/FirstScalarStringRule"))));
    }

    @Test
    void roundTripsACustomFirstFilledValueOnTheLeftOfAComparison() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition custom = Dsl.firstFilledCustom(
                m.custom("/Employee/FirstContractCodeType").field(),
                m.custom("/Employee/FallbackContractCodeType").field())
                .eq(m.custom("/Employee/FirstContractCodeType").asString());

        KernelAdapter adapter = KernelAdapter.of(m.add(
                rule(m, "FirstCustomLeftRule", "/Employee/FirstContractCodeType", custom)));

        assertEquals(RENDER.render(custom),
                RENDER.render(converted(adapter.read("/Employee/FirstCustomLeftRule"))));
    }

    @Test
    void roundTripsBothDirectionsOfTheOwnersSharedResultFamilies() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition stringFromCustom = m.str("/Employee/FirstContractCode").eq(
                Dsl.firstFilledCustom(m.flatten("/Employee/Contracts*/CodeType")));
        Condition dateFromFragment = m.date("/Employee/FirstContractMonthDate").eq(
                Dsl.firstFilledDateFragment(m.flatten("/Employee/Contracts*/Month")));
        Computation computedString = m.computation(m.str("/Employee/FirstContractCode"))
                .name("FirstCustomCodeComp")
                .calc(Dsl.firstFilledCustom(m.flatten("/Employee/Contracts*/CodeType")).expression())
                .message(en("First custom code as a string-family value.")).build();

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "StringFromCustomRule", "/Employee/FirstContractCode", stringFromCustom))
                .add(rule(m, "DateFromFragmentRule", "/Employee/FirstContractMonthDate", dateFromFragment))
                .add(computedString));

        assertEquals(RENDER.render(stringFromCustom),
                RENDER.render(converted(adapter.read("/Employee/StringFromCustomRule"))));
        assertEquals(RENDER.render(dateFromFragment),
                RENDER.render(converted(adapter.read("/Employee/DateFromFragmentRule"))));
        ReadComputation read = adapter.readComputation("/Employee/FirstCustomCodeComp");
        Computation converted = assertInstanceOf(ReadComputation.Converted.class, read,
                () -> "expected a converted computation, got: " + read).computation();
        assertEquals(computedString.draft(), converted.draft());
    }

    @Test
    void theKernelAdmitsAFirstFilledCustomValueIntoAStringField() {
        DraftComputation computation = new DraftComputation(
                "/Employee", "FirstCustomCodeComp", "../FirstContractCode", null,
                List.of(new DraftComputation.Alternative(null,
                        "FirstFilledValue(Contracts*/CodeType)")));

        ValidationResult result = new RuleValidator().check(employeeJson(), computation);

        assertTrue(result.ok(), () -> "expected owner admission, got: " + result.diagnostics());
    }

    @Test
    void roundTripsGroupSemanticIndexAndFilteredOperandsWithoutFlatteningTheirMeaning() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition group = m.str("/Employee/FirstContractCode").ne(
                Dsl.firstFilledString(m.firstFilledGroup("/Employee/FallbackCodes")));
        Condition repeatedGroup = m.num("/Employee/FirstContractValue").ne(
                Dsl.firstFilledNumber(m.firstFilledGroup("/Employee/NumberChoices*")));
        Condition literalIndex = m.num("/Employee/FirstContractValue").ne(Dsl.firstFilledNumber(
                m.num("/Employee/Contracts/Value").semanticIndex("A").index().firstFilledOperand(),
                m.num("/Employee/FirstContractValue").field().firstFilledOperand()));
        Condition fieldIndex = m.num("/Employee/FirstContractValue").ne(Dsl.firstFilledNumber(
                m.num("/Employee/Contracts/Value")
                        .semanticIndex(m.field("/Employee/FirstContractCode")).index().firstFilledOperand(),
                m.num("/Employee/FirstContractValue").field().firstFilledOperand()));
        Condition filtered = m.num("/Employee/FirstContractValue").ne(Dsl.firstFilledNumber(
                m.flatten("/Employee/Contracts*/Value")
                        .having(m.num("/Employee/Contracts/Value").gt(0))));
        Condition category = Dsl.firstFilledString(
                m.enumOf("/Employee/FirstContractType").category("Band").firstFilledOperand(),
                m.str("/Employee/FirstContractCode").field().firstFilledOperand()).ne("PRIMARY");
        Condition literalGroupIndex = m.num("/Employee/FirstContractValue").ne(
                Dsl.firstFilledNumber(m.firstFilledGroupFor("/Employee/IndexedNumberChoices", "1")));
        Condition fieldGroupIndex = m.num("/Employee/FirstContractValue").ne(
                Dsl.firstFilledNumber(m.firstFilledGroupFor(
                        "/Employee/IndexedNumberChoices", m.num("/Employee/FirstContractValue").field())));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstGroupRule", "/Employee/FirstContractCode", group))
                .add(rule(m, "FirstRepeatedGroupRule", "/Employee/FirstContractValue", repeatedGroup))
                .add(rule(m, "FirstLiteralIndexRule", "/Employee/FirstContractValue", literalIndex))
                .add(rule(m, "FirstFieldIndexRule", "/Employee/FirstContractValue", fieldIndex))
                .add(rule(m, "FirstFilteredRule", "/Employee/FirstContractValue", filtered))
                .add(rule(m, "FirstCategoryRule", "/Employee/FirstContractType", category))
                .add(rule(m, "FirstLiteralGroupIndexRule", "/Employee/FirstContractValue", literalGroupIndex))
                .add(rule(m, "FirstFieldGroupIndexRule", "/Employee/FirstContractValue", fieldGroupIndex)));

        assertEquals(RENDER.render(group), RENDER.render(converted(adapter.read("/Employee/FirstGroupRule"))));
        assertEquals(RENDER.render(repeatedGroup),
                RENDER.render(converted(adapter.read("/Employee/FirstRepeatedGroupRule"))));
        assertEquals(RENDER.render(literalIndex),
                RENDER.render(converted(adapter.read("/Employee/FirstLiteralIndexRule"))));
        assertEquals(RENDER.render(fieldIndex),
                RENDER.render(converted(adapter.read("/Employee/FirstFieldIndexRule"))));
        assertEquals(RENDER.render(filtered),
                RENDER.render(converted(adapter.read("/Employee/FirstFilteredRule"))));
        assertEquals(RENDER.render(category),
                RENDER.render(converted(adapter.read("/Employee/FirstCategoryRule"))));
        assertEquals(RENDER.render(literalGroupIndex),
                RENDER.render(converted(adapter.read("/Employee/FirstLiteralGroupIndexRule"))));
        assertEquals(RENDER.render(fieldGroupIndex),
                RENDER.render(converted(adapter.read("/Employee/FirstFieldGroupIndexRule"))));
    }

    @Test
    void firstFilledValueIsKernelValid() {
        ValidationResult r = new RuleValidator().check(employeeJson(),
                new DraftRule("/Employee", "FirstValueRule", "../FirstContractValue",
                        "FieldFilled(FirstContractValue) And [/Employee/FirstContractValue] != FirstFilledValue(Contracts*/Value)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Employee")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
