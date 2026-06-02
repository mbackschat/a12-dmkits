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
 * form for now; the relative/short path policy + rule-group context (§18.9, F3) come later.
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

    private static Reader fixture(String classpathLocation) {
        InputStream in = RenderConditionTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
