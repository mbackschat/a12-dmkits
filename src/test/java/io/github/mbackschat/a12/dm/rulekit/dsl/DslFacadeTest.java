package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.allFieldsFilled;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldNotFilled;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.length;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The {@code Dsl.*} static facade (study finding 1, proposal §10): free-standing factories that
 * take <em>handles</em> — never string paths — so reusable combinators read naturally while
 * model-awareness stays mandatory (handles only come from {@link BoundModel}).
 */
class DslFacadeTest {

    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";
    private static final String NAME = "/Subscription/Addons/Name";

    private static BoundModel m;

    @BeforeAll
    static void bind() throws Exception {
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
    }

    /** The study's combinator shape: an ordinary method over Dsl factories. */
    private static Condition mandatoryWhen(Condition gate, FieldRef field) {
        return gate.and(fieldNotFilled(field));
    }

    @Test
    void combinatorOverDslFactoriesRendersLikeTheHandleForm() {
        Condition viaCombinator = mandatoryWhen(m.enumOf("/Subscription/Tier").is("PRO"), m.field(MONTHLY_FEE));
        Condition viaHandles = m.enumOf("/Subscription/Tier").is("PRO").and(m.field(MONTHLY_FEE).notFilled());
        assertEquals(new DslRenderer().render(viaHandles), new DslRenderer().render(viaCombinator));
    }

    @Test
    void quantifierFactoryOverRefsMatchesTheBoundModelStringForm() {
        Condition viaDsl = allFieldsFilled(m.field(NAME), m.field(MONTHLY_FEE));
        Condition viaModel = m.allFieldsFilled(NAME, MONTHLY_FEE);
        assertEquals(new DslRenderer().render(viaModel), new DslRenderer().render(viaDsl));
    }

    @Test
    void allOfFoldsWithAndWithoutRedundantParentheses() {
        Condition a = m.field(NAME).filled();
        Condition b = m.field(MONTHLY_FEE).filled();
        Condition c = m.num(MONTHLY_FEE).gt(0);
        assertEquals(
                "FieldFilled(/Subscription/Addons/Name) And FieldFilled(/Subscription/Addons/MonthlyFee)"
                        + " And [/Subscription/Addons/MonthlyFee] > 0",
                new DslRenderer().render(Dsl.allOf(a, b, c)));
    }

    @Test
    void anyOfNestedInAllOfParenthesizesPerTheKernelMixingRule() {
        Condition a = m.field(NAME).filled();
        Condition b = m.num(MONTHLY_FEE).le(0);
        Condition c = m.num(MONTHLY_FEE).gt(100);
        assertEquals(
                "FieldFilled(/Subscription/Addons/Name) And "
                        + "([/Subscription/Addons/MonthlyFee] <= 0 Or [/Subscription/Addons/MonthlyFee] > 100)",
                new DslRenderer().render(Dsl.allOf(a, Dsl.anyOf(b, c))));
    }

    @Test
    void allOfOverAListFoldsLikeTheVarargsForm() {
        Condition a = m.field(NAME).filled();
        Condition b = m.field(MONTHLY_FEE).filled();
        Condition c = m.num(MONTHLY_FEE).gt(0);
        // the bulk/generated-authoring form: build the clauses, then fold the list
        assertEquals(new DslRenderer().render(Dsl.allOf(a, b, c)),
                new DslRenderer().render(Dsl.allOf(java.util.List.of(a, b, c))));
        assertEquals(new DslRenderer().render(Dsl.anyOf(a, b, c)),
                new DslRenderer().render(Dsl.anyOf(java.util.List.of(a, b, c))));
    }

    @Test
    void foldOverASingletonListIsTheElementItself() {
        Condition a = m.field(NAME).filled();
        assertEquals(new DslRenderer().render(a), new DslRenderer().render(Dsl.allOf(java.util.List.of(a))));
        assertEquals(new DslRenderer().render(a), new DslRenderer().render(Dsl.anyOf(java.util.List.of(a))));
    }

    @Test
    void foldOverAnEmptyListIsAnError() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.allOf(java.util.List.<Condition>of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.anyOf(java.util.List.<Condition>of()));
    }

    @Test
    void presenceOverAListMatchesTheVarargsForm() {
        var fields = java.util.List.of(m.field(NAME), m.field(MONTHLY_FEE));
        assertEquals(new DslRenderer().render(allFieldsFilled(m.field(NAME), m.field(MONTHLY_FEE))),
                new DslRenderer().render(Dsl.allFieldsFilled(fields)));
        assertEquals(new DslRenderer().render(Dsl.noFieldFilled(m.field(NAME), m.field(MONTHLY_FEE))),
                new DslRenderer().render(Dsl.noFieldFilled(fields)));
        assertEquals(new DslRenderer().render(Dsl.atLeastOneFieldFilled(m.field(NAME), m.field(MONTHLY_FEE))),
                new DslRenderer().render(Dsl.atLeastOneFieldFilled(fields)));
    }

    @Test
    void lengthFactoryMatchesTheHandleForm() {
        Condition viaDsl = length(m.str(NAME)).gt(10);
        assertEquals("Length(/Subscription/Addons/Name) > 10", new DslRenderer().render(viaDsl));
    }

    private static Reader fixture(String classpathLocation) {
        InputStream in = DslFacadeTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
