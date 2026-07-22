package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §5 — complete {@code FieldValueAsNumber} source-domain admission
 * (SPEC-2026-07-22-13). String admission is the exact value-validating {@code [0-9]+}/max-15
 * profile. Enumeration admission checks the SELECTED stored/category domain with the kernel decimal
 * grammar and 15-digit budget; its result scale is the maximum selected fractional length.
 */
class FieldValueAsNumberDomainLawsTest {

    private static final String GROUP = "/Tariff";
    private static final String SOURCE = "/Tariff/Source";

    @Test
    void exactBoundedStringProfileIsAdmitted() {
        String json = stringModel("[0-9]+", 15, false);
        KernelLaws.acceptsModel(json, GROUP, "../Code", renderDirect(json));
    }

    @Test
    void everyOtherStringProfileIsRejected() {
        List.of(
                stringModel("[0-9]*", 15, false),
                stringModel("[0-9]+", 16, false),
                stringModel("[0-9]+", null, false))
                .forEach(json -> KernelLaws.rejectsModel(
                        json, GROUP, "../Code", renderDirect(json), "MVK_NO_CONVERT_IN_NUMBER"));

        String raw = stringModel(null, 15, true);
        KernelLaws.rejectsModel(raw, GROUP, "../Code", renderDirect(raw), "MVK_NO_CONVERT_IN_NUMBER");
    }

    @Test
    void categorySelectionAdmitsTheCompleteDecimalGrammarAndDerivesMaximumScale() {
        String json = enumModel(List.of(".5", "-1.25", "123456789012345", "١٢.٥"));
        BoundModel model = BoundModel.ofJson(json);
        Condition condition = Dsl.fieldValueAsNumber(model.enumOf(SOURCE).category("Numeric"))
                .eq(model.num("/Tariff/Expected").asNumber());
        assertEquals("FieldValueAsNumber(Source -> Numeric) == [Expected]", render(condition));
        KernelLaws.acceptsModel(json, GROUP, "../Source", render(condition));
    }

    @Test
    void invalidDecimalFormsAndSixteenDigitsAreRejectedFromTheSelectedDomain() {
        List.of("1.", "+1", "1e2", " 1", "1 ", "1,2", "1234567890123456")
                .forEach(token -> {
                    String json = enumModel(List.of(token));
                    BoundModel model = BoundModel.ofJson(json);
                    Condition condition = Dsl.fieldValueAsNumber(model.enumOf(SOURCE).category("Numeric")).ge(0);
                    KernelLaws.rejectsModel(
                            json, GROUP, "../Source", render(condition), "MVK_NO_CONVERT_IN_NUMBER");
                });
    }

    private static String stringModel(String pattern, Integer maxLength, boolean noValueValidation) {
        return DocumentModelDraft.named("tariff-string").locale("en_US")
                .rootGroup("Tariff", group -> group.stringField("Code", text -> {
                    if (pattern != null) text.pattern(pattern, "digits only");
                    if (maxLength != null) text.maxLength(maxLength);
                    if (noValueValidation) text.lineBreaks().noValueValidation();
                }))
                .build().toJson();
    }

    private static String enumModel(List<String> selectedDomain) {
        return DocumentModelDraft.named("tariff-enum").locale("en_US")
                .rootGroup("Tariff", group -> {
                    group.enumField("Source", source -> {
                        for (int i = 0; i < selectedDomain.size(); i++) source.value("TOKEN_" + i);
                        source.category("Numeric", selectedDomain.toArray(String[]::new));
                    });
                    group.numberField("Expected", number -> number.fractionDigits(2));
                })
                .build().toJson();
    }

    private static String renderDirect(String json) {
        BoundModel model = BoundModel.ofJson(json);
        return render(Dsl.fieldValueAsNumber(model.field("/Tariff/Code")).ge(0));
    }

    private static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN, GROUP).render(condition);
    }
}
