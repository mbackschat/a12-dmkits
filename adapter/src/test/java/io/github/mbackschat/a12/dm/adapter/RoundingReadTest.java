package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — the operand-list rounding functions {@code RoundDown/RoundUp/RoundAccounting(expr, n)}
 * on the right of a computed-field consistency rule (Corpus-1's largest opaque shape: a stored value
 * compared against a re-derived one). The kernel emits these as an {@code IOpListOperationWithParameter}
 * wrapping one operation plus a decimal-place count; the converter now models them as a {@code Rounding}
 * node. Own domain: a city whose stored {@code Density} must equal {@code Population / Area} rounded.
 */
class RoundingReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String cityJson() {
        return DocumentModelDraft.named("city").locale("en_US")
                .rootGroup("City", c -> c
                        .numberField("Population", n -> { })
                        .numberField("Area", n -> { })
                        .numberField("NetAmount", n -> n.fractionDigits(2))
                        .numberField("GrossAmount", n -> n.fractionDigits(2))
                        .numberField("Density", n -> n.fractionDigits(2)))
                .build().toJson();
    }

    @Test
    void roundTripsAllThreeRoundingDirections() {
        BoundModel m = BoundModel.ofJson(cityJson());
        NumberExpr ratio = m.num("/City/Population").asNumber().dividedBy(m.num("/City/Area").asNumber());
        Condition down = m.num("/City/Density").ne(Dsl.roundDown(ratio, 2));
        Condition up = m.num("/City/GrossAmount").ne(Dsl.roundUp(m.num("/City/NetAmount").asNumber(), 2));
        Condition acc = m.num("/City/GrossAmount").ne(Dsl.roundAccounting(m.num("/City/NetAmount").asNumber(), 2));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "DensityRule", "/City/Density", down))
                .add(rule(m, "GrossUpRule", "/City/GrossAmount", up))
                .add(rule(m, "GrossAccRule", "/City/GrossAmount", acc)));
        assertEquals(RENDER.render(down), RENDER.render(converted(adapter.read("/City/DensityRule"))));
        assertEquals(RENDER.render(up), RENDER.render(converted(adapter.read("/City/GrossUpRule"))));
        assertEquals(RENDER.render(acc), RENDER.render(converted(adapter.read("/City/GrossAccRule"))));
    }

    @Test
    void roundDownOfAnArithmeticExpressionIsKernelValid() {
        ValidationResult r = new RuleValidator().check(cityJson(),
                new DraftRule("/City", "DensityRule", "../Density",
                        "AllFieldsFilled(Population, Area) And [/City/Density] != RoundDown([/City/Population] / [/City/Area], 2)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/City")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
