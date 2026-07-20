package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — field-level rounding/abs functions: {@code RoundDownValue(f[, n])}/{@code RoundUpValue(f[, n])}/
 * {@code RoundAccountingValue(f[, n])} (optional {@code 0..14}; omission means zero) and {@code AbsValue(f)}
 * (absolute value), the kernel's {@code FieldIdOperationWithParameters} numeric ops — distinct from the
 * operand-list {@code RoundDown(expr[, n])}. Own domain: a ledger whose rounded/absolute roll-ups must
 * match the per-entry amount.
 */
class FieldRoundingReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String ledgerJson() {
        return DocumentModelDraft.named("ledger").locale("en_US")
                .rootGroup("Ledger", l -> l
                        .numberField("Amount", n -> n.fractionDigits(2))
                        .numberField("RoundedAmount", n -> { })
                        .numberField("Balance", n -> n.fractionDigits(2)))
                .build().toJson();
    }

    @Test
    void roundTripsFieldLevelRoundingAndAbs() {
        BoundModel m = BoundModel.ofJson(ledgerJson());
        Condition down = m.num("/Ledger/RoundedAmount").filled()
                .and(m.num("/Ledger/RoundedAmount").ne(Dsl.roundDownValue(m.num("/Ledger/Amount"))));
        Condition up = m.num("/Ledger/RoundedAmount").filled()
                .and(m.num("/Ledger/RoundedAmount").ne(Dsl.roundUpValue(m.num("/Ledger/Amount"))));
        Condition acct = m.num("/Ledger/RoundedAmount").filled()
                .and(m.num("/Ledger/RoundedAmount").ne(Dsl.roundAccountingValue(m.num("/Ledger/Amount"))));
        Condition abs = m.num("/Ledger/Balance").filled()
                .and(m.num("/Ledger/Balance").ne(Dsl.absValue(m.num("/Ledger/Balance"))));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "DownRule", "/Ledger/RoundedAmount", down))
                .add(rule(m, "UpRule", "/Ledger/RoundedAmount", up))
                .add(rule(m, "AcctRule", "/Ledger/RoundedAmount", acct))
                .add(rule(m, "AbsRule", "/Ledger/Balance", abs)));
        assertTrue(RENDER.render(down).contains("RoundDownValue(/Ledger/Amount)"), RENDER.render(down));
        assertEquals(RENDER.render(down), RENDER.render(converted(adapter.read("/Ledger/DownRule"))));
        assertEquals(RENDER.render(up), RENDER.render(converted(adapter.read("/Ledger/UpRule"))));
        assertEquals(RENDER.render(acct), RENDER.render(converted(adapter.read("/Ledger/AcctRule"))));
        assertEquals(RENDER.render(abs), RENDER.render(converted(adapter.read("/Ledger/AbsRule"))));
    }

    @Test
    void fieldRoundingIsKernelValid() {
        BoundModel m = BoundModel.ofJson(ledgerJson());
        Condition c = m.num("/Ledger/RoundedAmount").filled()
                .and(m.num("/Ledger/RoundedAmount").ne(Dsl.roundDownValue(m.num("/Ledger/Amount"))));
        ValidationResult r = new RuleValidator().check(ledgerJson(),
                new DraftRule("/Ledger", "DownRule", "../RoundedAmount", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Ledger")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
