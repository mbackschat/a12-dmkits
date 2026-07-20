package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The renderer/handle core: composition, parenthesization, literal quoting, typed-handle kind
 * checks (PROJECT-DESIGN §6/§10). Operator-family behavior lives in {@code dsl.operators.*} — one test
 * file per family (test-layout convention, CLAUDE.md). Paths render in absolute form for now; the
 * relative/short path policy + rule-group context (§18 fork 9, F3a) come later.
 */
class RenderConditionTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";
    private static final String NAME = "/Subscription/Addons/Name";

    @Test
    void rendersConjunctionInEnglish() {
        Condition c = m.num(MONTHLY_FEE).filled().and(m.num(MONTHLY_FEE).le(0));
        assertEquals(
                "FieldFilled(/Subscription/Addons/MonthlyFee) And [/Subscription/Addons/MonthlyFee] <= 0",
                new DslRenderer().render(c));
    }

    @Test
    void rendersSameAstInGermanFromTheCatalog() {
        Condition c = m.num(MONTHLY_FEE).filled().and(m.num(MONTHLY_FEE).le(0));
        assertEquals(
                "FeldAngegeben(/Subscription/Addons/MonthlyFee) Und [/Subscription/Addons/MonthlyFee] <= 0",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void parenthesizesWhenMixingAndWithOr() {
        Condition c = m.field(NAME).filled()
                .and(m.num(MONTHLY_FEE).le(0).or(m.num(MONTHLY_FEE).gt(100)));
        assertEquals(
                "FieldFilled(/Subscription/Addons/Name) And "
                        + "([/Subscription/Addons/MonthlyFee] <= 0 Or [/Subscription/Addons/MonthlyFee] > 100)",
                new DslRenderer().render(c));
    }

    @Test
    void typedNumberHandleRejectsAStringField() {
        assertThrows(IllegalArgumentException.class, () -> m.num(NAME));
    }

    @Test
    void typedStringHandleRejectsANumberField() {
        assertThrows(IllegalArgumentException.class, () -> m.str(MONTHLY_FEE));
    }

    // A model carrying a custom-typed field, so the CUSTOM-kind handle (m.custom) has something to bind to.
    private static final BoundModel customModel = BoundModel.ofJson(
            DocumentModelDraft.named("form").locale("en_US")
                    .rootGroup("Order", g -> g
                            .customField("Code", c -> c.type("Code").minLength(3).maxLength(5))
                            .stringField("Note", s -> {}))
                    .build().toJson());
    private static final String CODE = "/Order/Code";

    @Test
    void customHandleRendersAValueComparison() {
        // DG13 — a custom field's value is string-shaped; the kernel grammar compares it like any string.
        Condition c = customModel.custom(CODE).eq("abcd");
        assertEquals("[/Order/Code] == \"abcd\"", new DslRenderer().render(c));
    }

    @Test
    void typedCustomHandleRejectsANonCustomField() {
        assertThrows(IllegalArgumentException.class, () -> customModel.custom("/Order/Note"));
    }

    @Test
    void typedStringHandleRejectsACustomField() {
        // str() keeps its STRING-only contract — the custom value goes through custom(), not str().
        assertThrows(IllegalArgumentException.class, () -> customModel.str(CODE));
    }

    @Test
    void rendersStringEqualityWithAQuotedLiteral() {
        Condition c = m.str(NAME).eq("ACME");
        assertEquals("[/Subscription/Addons/Name] == \"ACME\"", new DslRenderer().render(c));
    }

    @Test
    void quotesStringLiteralsTheKernelWay() {
        // Kernel lexer (verified 2026-06-04): StringLiteral : '"' ( '""' | ~["] )* '"' — an embedded
        // quote is DOUBLED, and backslash is a plain character (no escape sequences).
        assertEquals("[/Subscription/Addons/Name] == \"say \"\"hi\"\"\"",
                new DslRenderer().render(m.str(NAME).eq("say \"hi\"")));
        assertEquals("[/Subscription/Billing/InvoiceEmail] PatternMatched \"\\d{5}\"",
                new DslRenderer().render(m.str("/Subscription/Billing/InvoiceEmail").matches("\\d{5}")));
    }

    @Test
    void rendersScaleExplicitLiteralsFromStringAndBigDecimalOverloads() {
        // Scale-aware ergonomics (study finding 2): the written scale is preserved exactly.
        // Written scale participates in the kernel's signed-scale/constant-capability summary (NumberLawsTest);
        // these overloads preserve it exactly rather than treating every literal as scale-exempt.
        assertEquals("[/Subscription/Addons/MonthlyFee] == 0.00",
                new DslRenderer().render(m.num(MONTHLY_FEE).eq("0.00")));
        assertEquals("[/Subscription/Addons/MonthlyFee] >= 19.90",
                new DslRenderer().render(m.num(MONTHLY_FEE).ge(new java.math.BigDecimal("19.90"))));
        assertEquals("Length(/Subscription/Addons/Name) <= 10.5",
                new DslRenderer().render(m.str(NAME).length().le("10.5")));
    }

    @Test
    void rendersStringFieldVsFieldEquality() {
        // field-vs-field — strings have no scale, so == / != between two string fields is direct
        assertEquals("[/Subscription/Addons/Name] == [/Subscription/Billing/InvoiceEmail]",
                new DslRenderer().render(m.str(NAME).eq(m.str("/Subscription/Billing/InvoiceEmail"))));
        assertEquals("[/Subscription/Addons/Name] != [/Subscription/Billing/InvoiceEmail]",
                new DslRenderer().render(m.str(NAME).ne(m.str("/Subscription/Billing/InvoiceEmail"))));
    }

    @Test
    void rendersNumberFieldVsFieldComparisons() {
        // ordering between two number fields ignores scale (verified 2026-06-04)
        assertEquals("[/Subscription/Billing/BaseFee] <= [/Subscription/Billing/EffectiveFee]",
                new DslRenderer().render(m.num("/Subscription/Billing/BaseFee").le(m.num("/Subscription/Billing/EffectiveFee"))));
        // == between equal-scale number fields is fine
        assertEquals("[/Subscription/Billing/BaseFee] == [/Subscription/Billing/EffectiveFee]",
                new DslRenderer().render(m.num("/Subscription/Billing/BaseFee").eq(m.num("/Subscription/Billing/EffectiveFee"))));
    }

    @Test
    void rendersLengthFunctionBilingually() {
        Condition c = m.str(NAME).length().gt(10);
        assertEquals("Length(/Subscription/Addons/Name) > 10", new DslRenderer().render(c));
        assertEquals("Laenge(/Subscription/Addons/Name) > 10", new DslRenderer(ConditionLanguage.DE).render(c));
    }
}
