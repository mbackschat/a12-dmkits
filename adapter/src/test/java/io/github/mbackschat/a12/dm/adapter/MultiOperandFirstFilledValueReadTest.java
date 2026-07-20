package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * Read-back lock for the complete multi-operand {@code FirstFilledValue} family. Each expression visits
 * two flattened lists and then a plain fallback field, preserving both operand order and the result kind.
 */
class MultiOperandFirstFilledValueReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void roundTripsEveryKindWithListAndPlainTrailingOperands() {
        BoundModel model = BoundModel.ofJson(calibrationModel());

        Condition number = model.num("/Calibration/ExpectedNumber").ne(Dsl.firstFilledNumber(
                model.flatten("/Calibration/Primary*/Amount"),
                model.flatten("/Calibration/Secondary*/Amount"),
                model.num("/Calibration/FallbackNumber").field()));
        Condition string = model.str("/Calibration/ExpectedString").ne(Dsl.firstFilledString(
                model.flatten("/Calibration/Primary*/Label"),
                model.flatten("/Calibration/Secondary*/Label"),
                model.str("/Calibration/FallbackString").field()));
        Condition date = model.date("/Calibration/ExpectedDate").ne(Dsl.firstFilledDate(
                model.flatten("/Calibration/Primary*/ObservedOn"),
                model.flatten("/Calibration/Secondary*/ObservedOn"),
                model.date("/Calibration/FallbackDate").field()));
        Condition enumeration = model.enumOf("/Calibration/ExpectedStatus").isNot(Dsl.firstFilledEnum(
                model.flatten("/Calibration/Primary*/Status"),
                model.flatten("/Calibration/Secondary*/Status"),
                model.enumOf("/Calibration/FallbackStatus").field()));

        KernelAdapter adapter = KernelAdapter.of(model
                .add(rule(model, "NumberRule", "/Calibration/ExpectedNumber", number))
                .add(rule(model, "StringRule", "/Calibration/ExpectedString", string))
                .add(rule(model, "DateRule", "/Calibration/ExpectedDate", date))
                .add(rule(model, "EnumRule", "/Calibration/ExpectedStatus", enumeration)));

        assertRoundTrip(number, adapter.read("/Calibration/NumberRule"));
        assertRoundTrip(string, adapter.read("/Calibration/StringRule"));
        assertRoundTrip(date, adapter.read("/Calibration/DateRule"));
        assertRoundTrip(enumeration, adapter.read("/Calibration/EnumRule"));
    }

    private static String calibrationModel() {
        return DocumentModelDraft.named("calibration").locale("en_US")
                .rootGroup("Calibration", root -> root
                        .numberField("ExpectedNumber", field -> { })
                        .numberField("FallbackNumber", field -> { })
                        .stringField("ExpectedString", field -> { })
                        .stringField("FallbackString", field -> { })
                        .dateField("ExpectedDate", field -> field.format("yyyy-MM-dd"))
                        .dateField("FallbackDate", field -> field.format("yyyy-MM-dd"))
                        .enumField("ExpectedStatus", field -> field.value("READY").value("WAITING"))
                        .enumField("FallbackStatus", field -> field.value("READY").value("WAITING"))
                        .group("Primary", group -> sampleFields(group.repeatable(20)))
                        .group("Secondary", group -> sampleFields(group.repeatable(20))))
                .build().toJson();
    }

    private static void sampleFields(DocumentModelDraft.GroupBuilder group) {
        group.numberField("Amount", field -> { })
                .stringField("Label", field -> { })
                .dateField("ObservedOn", field -> field.format("yyyy-MM-dd"))
                .enumField("Status", field -> field.value("READY").value("WAITING"));
    }

    private static Rule rule(BoundModel model, String name, String errorField, Condition condition) {
        return model.rule(model.group("/Calibration"))
                .name(name)
                .errorField(model.field(errorField))
                .when(condition)
                .code("CALIBRATION_MISMATCH")
                .severity(Severity.ERROR)
                .message(en("calibration mismatch"))
                .build();
    }

    private static void assertRoundTrip(Condition expected, ReadResult result) {
        Condition converted = assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
        assertEquals(RENDER.render(expected), RENDER.render(converted));
    }
}
