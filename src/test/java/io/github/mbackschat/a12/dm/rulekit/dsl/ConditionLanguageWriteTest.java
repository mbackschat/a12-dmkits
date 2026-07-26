package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Typed persistence renders into the destination model's declared condition language. Display/message locales remain
 * independent, and the raw text overloads stay verbatim for CLI/spec consumers that already supply stored DSL text.
 */
class ConditionLanguageWriteTest {

    @Test
    void typedRuleAddAndReplacementUseTheModelConditionLanguage() {
        // tag::condition-language-write[]
        BoundModel model = germanModel();

        BoundModel added = model.add(positiveAmountRule(model, "PositiveAmount"));
        var amount = added.num("/Policy/Amount");
        BoundModel replaced = added.replaceCondition(
                "/Policy/PositiveAmount",
                amount.filled().and(amount.gt(10)));
        // end::condition-language-write[]

        assertGermanRuleText(added.toJson());
        assertTrue(replaced.toJson().contains("FeldAngegeben(Amount) Und [Amount] > 10"));
        assertFalse(replaced.toJson().contains("FieldFilled(Amount)"));
    }

    @Test
    void typedComputationAddAndReplacementUseTheModelConditionLanguage() {
        BoundModel model = germanModel();

        BoundModel added = model.add(amountComputation(model, "CalculateResult", "/Policy/Amount"));
        assertGermanComputationText(added.toJson(), "Amount");

        BoundModel replaced = added.replaceComputation(
                "/Policy/CalculateResult",
                amountComputation(added, "CalculateResult", "/Policy/Limit"));
        assertGermanComputationText(replaced.toJson(), "Limit");
        assertFalse(replaced.toJson().contains("FieldFilled(Limit)"));
    }

    @Test
    void typedBulkWriteUsesTheModelConditionLanguage() {
        BoundModel model = germanModel();

        BoundModel written = model.addAll(
                List.of(positiveAmountRule(model, "BulkPositiveAmount")),
                List.of(amountComputation(model, "BulkCalculateResult", "/Policy/Amount")));

        assertGermanRuleText(written.toJson());
        assertGermanComputationText(written.toJson(), "Amount");
    }

    @Test
    void rawTextWriteRemainsVerbatim() {
        BoundModel model = germanModel();

        String written = ModelWriter.addRule(
                model.toJson(),
                "/Policy",
                "RawEnglish",
                "../Amount",
                "RAW_EN",
                "FieldFilled(Amount)",
                "ERROR",
                List.of(Dsl.en("raw text")));

        assertTrue(written.contains("FieldFilled(Amount)"));
        assertFalse(written.contains("FeldAngegeben(Amount)"));
    }

    private static BoundModel germanModel() {
        String structure = DocumentModelDraft.named("localized-write")
                .locale("en_US")
                .rootGroup("Policy", policy -> policy
                        .numberField("Amount", number -> number.fractionDigits(0))
                        .numberField("Limit", number -> number.fractionDigits(0))
                        .numberField("Result", number -> number.fractionDigits(0)))
                .build()
                .toJson();
        EditSession session = EditSession.read(structure);
        session.config().conditionLanguage("de_DE");
        return BoundModel.ofJson(session.toJson());
    }

    private static Rule positiveAmountRule(BoundModel model, String name) {
        var amount = model.num("/Policy/Amount");
        return model.rule(model.group("/Policy"))
                .name(name)
                .errorField(amount)
                .when(amount.filled().and(amount.le(0)))
                .code("POSITIVE_AMOUNT")
                .severity(Severity.ERROR)
                .message(Dsl.en("Amount must be positive."))
                .build();
    }

    private static Computation amountComputation(BoundModel model, String name, String sourcePath) {
        var source = model.num(sourcePath);
        return model.computation(model.num("/Policy/Result"))
                .name(name)
                .commonPrecondition(source.filled())
                .calc(source.value())
                .message(Dsl.en("Result must equal its source."))
                .build();
    }

    private static void assertGermanRuleText(String json) {
        assertTrue(json.contains("FeldAngegeben(Amount) Und [Amount] <= 0"));
        assertFalse(json.contains("FieldFilled(Amount)"));
    }

    private static void assertGermanComputationText(String json, String field) {
        assertTrue(json.contains("FeldAngegeben(" + field + ")"));
    }
}
