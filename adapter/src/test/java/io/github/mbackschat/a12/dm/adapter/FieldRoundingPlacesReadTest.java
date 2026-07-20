package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Both rounding families retain their optional decimal-place argument across typed authoring, kernel
 * persistence, and typed read-back. The same {@code 0..14} boundary applies to expression and
 * field-value forms; omission remains the canonical zero-place shorthand.
 */
class FieldRoundingPlacesReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final String RESULT = "/Ledger/Result2";
    private static final String AMOUNT = "/Ledger/Amount";

    private static BoundModel ledger() {
        String json = DocumentModelDraft.named("ledger-rounding").locale("en_US")
                .rootGroup("Ledger", g -> g
                        .numberField("Amount", n -> n.fractionDigits(0, 14))
                        .numberField("Result0", n -> n.fractionDigits(0, 0))
                        .numberField("Result2", n -> n.fractionDigits(2, 2))
                        .numberField("Result14", n -> n.fractionDigits(14, 14)))
                .build().toJson();
        return BoundModel.ofJson(json);
    }

    @Test
    void placesBearingValueFormRoundTripsWithoutLosingItsArgument() {
        BoundModel model = ledger();
        Condition condition = model.num(RESULT).filled()
                .and(model.num(RESULT).ne(Dsl.roundAccountingValue(model.num(AMOUNT), 2)));
        Rule rule = model.rule(model.group("/Ledger")).name("Rounded")
                .errorField(model.field(RESULT)).when(condition).code("ROUND")
                .severity(Severity.ERROR).message(en("x")).build();

        KernelAdapter adapter = KernelAdapter.of(model.add(rule));
        ReadResult.Converted read = (ReadResult.Converted) adapter.read("/Ledger/Rounded");

        assertEquals(RENDER.render(condition), RENDER.render(read.condition()));
        assertTrue(RENDER.render(read.condition()).contains("RoundAccountingValue(/Ledger/Amount, 2)"));
    }

    @Test
    void zeroAndFourteenAreLegalAndOmissionStillMeansZero() {
        BoundModel model = ledger();
        assertEquals("RoundDown([/Ledger/Amount])",
                RENDER.render(Dsl.roundDown(model.num(AMOUNT).asNumber()).expression()));
        assertEquals("RoundUp([/Ledger/Amount])",
                RENDER.render(Dsl.roundUp(model.num(AMOUNT).asNumber()).expression()));
        assertEquals("RoundAccounting([/Ledger/Amount])",
                RENDER.render(Dsl.roundAccounting(model.num(AMOUNT).asNumber()).expression()));
        assertEquals("RoundDown([/Ledger/Amount], 0)",
                RENDER.render(Dsl.roundDown(model.num(AMOUNT).asNumber(), 0).expression()));
        assertEquals("RoundDown([/Ledger/Amount], 14)",
                RENDER.render(Dsl.roundDown(model.num(AMOUNT).asNumber(), 14).expression()));
        assertEquals("RoundDownValue(/Ledger/Amount)",
                RENDER.render(Dsl.roundDownValue(model.num(AMOUNT)).expression()));
        assertEquals("RoundDownValue(/Ledger/Amount, 0)",
                RENDER.render(Dsl.roundDownValue(model.num(AMOUNT), 0).expression()));
        assertEquals("RoundDownValue(/Ledger/Amount, 14)",
                RENDER.render(Dsl.roundDownValue(model.num(AMOUNT), 14).expression()));

        RuleValidator validator = new RuleValidator();
        assertTrue(validator.check(model.toJson(), draft("Result0", "RoundDown([Amount])")).ok());
        assertTrue(validator.check(model.toJson(), draft("Result0", "RoundDown([Amount], 0)")).ok());
        assertTrue(validator.check(model.toJson(), draft("Result14", "RoundDown([Amount], 14)")).ok());
        assertTrue(validator.check(model.toJson(), draft("Result0", "RoundDownValue(Amount)")).ok());
        assertTrue(validator.check(model.toJson(), draft("Result0", "RoundDownValue(Amount, 0)")).ok());
        assertTrue(validator.check(model.toJson(), draft("Result14", "RoundDownValue(Amount, 14)")).ok());
    }

    @Test
    void kernelReadCanonicalizesExplicitZeroToTheEquivalentOmittedForm() {
        BoundModel model = ledger();
        Condition authored = model.num("/Ledger/Result0").filled()
                .and(model.num("/Ledger/Result0").ne(Dsl.roundDownValue(model.num(AMOUNT), 0)));
        Rule rule = model.rule(model.group("/Ledger")).name("ExplicitZero")
                .errorField(model.field("/Ledger/Result0")).when(authored).code("ROUND_ZERO")
                .severity(Severity.ERROR).message(en("x")).build();

        ReadResult.Converted read = (ReadResult.Converted) KernelAdapter.of(model.add(rule)).read("/Ledger/ExplicitZero");

        assertEquals(
                "FieldFilled(/Ledger/Result0) And [/Ledger/Result0] != RoundDownValue(/Ledger/Amount)",
                RENDER.render(read.condition()));

        Condition expressionAuthored = model.num("/Ledger/Result0").filled()
                .and(model.num("/Ledger/Result0").ne(Dsl.roundDown(model.num(AMOUNT).asNumber(), 0)));
        Rule expressionRule = model.rule(model.group("/Ledger")).name("ExplicitExpressionZero")
                .errorField(model.field("/Ledger/Result0")).when(expressionAuthored).code("ROUND_EXPR_ZERO")
                .severity(Severity.ERROR).message(en("x")).build();

        ReadResult.Converted expressionRead = (ReadResult.Converted) KernelAdapter.of(model.add(expressionRule))
                .read("/Ledger/ExplicitExpressionZero");
        assertEquals(
                "FieldFilled(/Ledger/Result0) And [/Ledger/Result0] != RoundDown([/Ledger/Amount])",
                RENDER.render(expressionRead.condition()));
    }

    @Test
    void fifteenIsRejectedAtBothTypedConstructionBoundariesAndByTheKernel() {
        BoundModel model = ledger();
        assertThrows(IllegalArgumentException.class, () -> Dsl.roundDown(model.num(AMOUNT).asNumber(), 15));
        assertThrows(IllegalArgumentException.class, () -> Dsl.roundDownValue(model.num(AMOUNT), 15));

        var expressionResult = new RuleValidator().check(model.toJson(), draft("Result14", "RoundDown([Amount], 15)"));
        assertTrue(expressionResult.diagnostics().stream()
                .anyMatch(d -> d.code().orElse("").equals("MVK_INVALID_DIGIT_FOR_ROUNDING")),
                () -> "expected MVK_INVALID_DIGIT_FOR_ROUNDING, got " + expressionResult.diagnostics());

        var fieldResult = new RuleValidator().check(model.toJson(), draft("Result14", "RoundDownValue(Amount, 15)"));
        assertTrue(fieldResult.diagnostics().stream()
                .anyMatch(d -> d.code().orElse("").equals("MVK_INVALID_DIGIT_FOR_ROUNDING")),
                () -> "expected MVK_INVALID_DIGIT_FOR_ROUNDING, got " + fieldResult.diagnostics());
    }

    private static DraftRule draft(String resultField, String rounded) {
        return new DraftRule("/Ledger", "Boundary", "../" + resultField,
                "FieldFilled(" + resultField + ") And [" + resultField + "] != " + rounded, "ROUND", "ERROR");
    }
}
