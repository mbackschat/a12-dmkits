package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;

/**
 * First vertical slice: typed, model-bound handles compose a {@link Condition} AST that the
 * catalog-driven renderer turns into canonical DSL text (proposal §6/§10). Paths render in absolute
 * form for now; the relative/short path policy + rule-group context (§18 fork 9, F3a) come later.
 */
class RenderConditionTest {

    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";
    private static final String NAME = "/Subscription/Addons/Name";

    private static BoundModel m;

    @BeforeAll
    static void bind() throws Exception {
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
    }

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
    void rendersStringEqualityWithAQuotedLiteral() {
        Condition c = m.str(NAME).eq("ACME");
        assertEquals("[/Subscription/Addons/Name] == \"ACME\"", new DslRenderer().render(c));
    }

    @Test
    void rendersScaleExplicitLiteralsFromStringAndBigDecimalOverloads() {
        // Scale-aware ergonomics (study finding 2): the written scale is preserved exactly.
        // Literals are scale-flexible at the kernel (verified — see RuleValidatorTest), so these
        // overloads are about faithful rendering, not about dodging MVK_INVALID_COMPARE_DEC_PLACES.
        assertEquals("[/Subscription/Addons/MonthlyFee] == 0.00",
                new DslRenderer().render(m.num(MONTHLY_FEE).eq("0.00")));
        assertEquals("[/Subscription/Addons/MonthlyFee] >= 19.90",
                new DslRenderer().render(m.num(MONTHLY_FEE).ge(new java.math.BigDecimal("19.90"))));
        assertEquals("Length(/Subscription/Addons/Name) <= 10.5",
                new DslRenderer().render(m.str(NAME).length().le("10.5")));
    }

    @Test
    void rendersLengthFunctionBilingually() {
        Condition c = m.str(NAME).length().gt(10);
        assertEquals("Length(/Subscription/Addons/Name) > 10", new DslRenderer().render(c));
        assertEquals("Laenge(/Subscription/Addons/Name) > 10", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void typedStringHandleRejectsANumberField() {
        assertThrows(IllegalArgumentException.class, () -> m.str(MONTHLY_FEE));
    }

    @Test
    void rendersEnumComparisonByStoredValue() {
        Condition c = m.enumOf("/Subscription/Tier").is("PRO");
        assertEquals("[/Subscription/Tier] == \"PRO\"", new DslRenderer().render(c));
    }

    @Test
    void enumHandleRejectsAValueOutsideTheModelDomain() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").is("PLATINUM"));
    }

    @Test
    void typedEnumHandleRejectsANonEnumField() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf(MONTHLY_FEE));
    }

    @Test
    void rendersFieldListQuantifierBilingually() {
        Condition c = m.allFieldsFilled(NAME, MONTHLY_FEE);
        assertEquals("AllFieldsFilled(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer().render(c));
        assertEquals("AlleFelderAngegeben(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void fieldListQuantifierValidatesEveryPath() {
        assertThrows(IllegalArgumentException.class, () -> m.allFieldsFilled(NAME, "/Subscription/Addons/Nope"));
    }

    private static Reader fixture(String classpathLocation) {
        InputStream in = RenderConditionTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
