package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Error-message interpolation tokens beyond {@code $Field.value$} (FEATURE-GAPS). {@link Dsl#fieldLabel}
 * renders the kernel's {@code $Field$} — the localized-label form (a bare path, no {@code .value} suffix) —
 * and {@link Dsl#repetitionIndex} renders {@code $#Group$}, the current repetition index of a repeatable
 * group. Both are message-rendering tokens, typed to a {@link FieldRef}/{@link GroupRef}, and verified
 * end-to-end: the kernel must accept a rule whose error message carries them.
 */
class MessageInterpolationTest {

    private static final BoundModel M = Fixtures.subscription();
    private static final String MODEL_JSON = Fixtures.subscriptionJson();

    @Test
    void fieldLabelRendersTheBarePathToken() {
        assertEquals("$/Subscription/Addons/MonthlyFee$",
                Dsl.fieldLabel(M.num("/Subscription/Addons/MonthlyFee").field()));
    }

    @Test
    void repetitionIndexRendersTheHashGroupToken() {
        assertEquals("$#/Subscription/Addons$", Dsl.repetitionIndex(M.group("/Subscription/Addons")));
    }

    @Test
    void theKernelAcceptsARuleWhoseMessageUsesLabelAndRepetitionIndex() {
        NumberRef fee = M.num("/Subscription/Addons/MonthlyFee");
        String label = Dsl.fieldLabel(fee.field());
        String index = Dsl.repetitionIndex(M.group("/Subscription/Addons"));
        Rule rule = M.rule(M.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(fee.gt(1000)))
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("Addon #" + index + ": " + label + " exceeds the cap."),
                        de("Zusatz #" + index + ": " + label + " überschreitet das Limit."))
                .build();

        ValidationResult vr = new RuleValidator().check(MODEL_JSON, rule.draft());
        assertTrue(vr.ok(),
                () -> "the kernel should accept the $Field$ / $#Group$ message tokens, got: " + vr.diagnostics());
    }
}
