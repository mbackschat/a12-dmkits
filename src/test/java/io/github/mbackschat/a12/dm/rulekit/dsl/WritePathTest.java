package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * F6 DM-JSON write path: add a rule to a bound model and get a NEW binding (snapshot semantics, F0),
 * serializable back to DM-JSON. The add re-binds through {@code ModelLoader}, so a write that would
 * break the model fails loudly (kernel-validated on write).
 */
class WritePathTest {

    private static BoundModel bind() {
        return BoundModel.ofJson(Fixtures.subscriptionJson());
    }

    @Test
    void addRuleYieldsANewBindingThatRendersIntoTheJson() {
        BoundModel m = bind();
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeePositiveRule")
                .errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(m.num("/Subscription/Addons/MonthlyFee").filled()
                        .and(m.num("/Subscription/Addons/MonthlyFee").le(0)))
                .code("FEE_POSITIVE")
                .severity(Severity.ERROR)
                .message(Dsl.en("Fee must be positive."), Dsl.de("Gebühr muss positiv sein."))
                .build();

        BoundModel m2 = m.add(rule);

        assertTrue(m2.toJson().contains("FeePositiveRule"), "new binding's JSON carries the rule");
        assertTrue(m2.toJson().contains("Fee must be positive."), "with its real message text");
        // snapshot: the original binding is unchanged
        assertFalse(m.toJson().contains("FeePositiveRule"), "original binding must be untouched (F0)");
    }

    @Test
    void replaceConditionSwapsOnlyTheConditionSlot() {
        BoundModel m = bind();
        BoundModel withRule = m.add(feePositive(m));
        String rulePath = "/Subscription/Addons/FeePositiveRule";

        BoundModel applied = withRule.replaceCondition(rulePath,
                withRule.num("/Subscription/Addons/MonthlyFee").gt(1000));

        String json = applied.toJson();
        assertTrue(json.contains("> 1000"), "the new condition is written");
        assertFalse(json.contains("<= 0"), "the old condition is gone");
        assertTrue(json.contains("FEE_POSITIVE"), "the error code (and the rest of the rule) is preserved");
        // snapshot: the prior binding still carries the original condition (F0)
        assertTrue(withRule.toJson().contains("<= 0"), "prior binding untouched");
        // reaching here means the re-bind re-ran checkConsistency and the write validated
    }

    @Test
    void addComputationYieldsANewBindingThatRendersIntoTheJson() {
        BoundModel m = bind();
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeComp")
                .calc(m.num("/Subscription/Billing/BaseFee").value())
                .message(Dsl.en("EffectiveFee must equal BaseFee."), Dsl.de("Effektivgebühr = Basisgebühr."))
                .build();

        BoundModel m2 = m.add(comp);
        String json = m2.toJson();
        assertTrue(json.contains("EffectiveFeeComp"), "new binding carries the computation");
        assertTrue(json.contains("computationAlternatives"), "in the computation DM-JSON shape");
        assertTrue(json.contains("EffectiveFee must equal BaseFee."), "with its real message text");
        assertFalse(m.toJson().contains("EffectiveFeeComp"), "original binding untouched (F0)");
        // re-binding ran checkConsistency (a broken computation would have thrown), and it round-trips
        assertTrue(BoundModel.ofJson(json).toJson().contains("EffectiveFeeComp"));
    }

    @Test
    void removeRuleDropsTheRuleAndReValidates() {
        BoundModel withRule = bind().add(feePositive(bind()));
        String rulePath = "/Subscription/Addons/FeePositiveRule";
        assertTrue(withRule.toJson().contains("FeePositiveRule"), "precondition: the rule is present");

        BoundModel without = withRule.removeRule(rulePath);
        assertFalse(without.toJson().contains("FeePositiveRule"), "the rule is gone");
        assertTrue(withRule.toJson().contains("FeePositiveRule"), "prior binding untouched (F0)");
        // removing a non-existent rule fails loudly with the coded diagnostic (DG3)
        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException.class,
                () -> bind().removeRule("/Subscription/Addons/NoSuchRule"));
    }

    private static Rule feePositive(BoundModel m) {
        return m.rule(m.group("/Subscription/Addons"))
                .name("FeePositiveRule").errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(m.num("/Subscription/Addons/MonthlyFee").filled()
                        .and(m.num("/Subscription/Addons/MonthlyFee").le(0)))
                .code("FEE_POSITIVE").severity(Severity.ERROR)
                .message(Dsl.en("x"), Dsl.de("y")).build();
    }

    @Test
    void writtenJsonReloadsAndKeepsTheRule() {
        BoundModel m = bind();
        BoundModel m2 = m.add(feePositive(m));
        // a fresh bind of the written JSON sees the rule too — round-trips
        assertTrue(BoundModel.ofJson(m2.toJson()).toJson().contains("FeePositiveRule"));
    }

    @Test
    void writeToPersistsTheModel(@TempDir Path dir) throws Exception {
        BoundModel m = bind();
        Path out = dir.resolve("subscription.dm.json");
        m.add(feePositive(m)).writeTo(out);

        assertTrue(Files.readString(out).contains("FeePositiveRule"));
        assertEquals(m.toJson(), bind().toJson(), "writing did not mutate the source binding");
    }

    @Test
    void toJsonReturnsTheAuthoredSourceVerbatim() {
        // The include-safety guarantee: toJson() is the AUTHORED (unexpanded) DM-JSON, byte-for-byte
        // — rulekit never reserializes the kernel's expanded model, so a model with includes /
        // modelReferences keeps its modular structure (includes are not inlined) on write.
        String json = Fixtures.subscriptionJson();
        assertEquals(json, BoundModel.ofJson(json).toJson());
    }

    @Test
    void addReValidatesAndRejectsAKernelInvalidWrite() {
        // an unguarded negative-ish condition over a repeatable group is kernel-rejected; add() must
        // surface that (the write path re-runs checkConsistency) rather than persist a broken model.
        BoundModel m = bind();
        Rule bad = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule").errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(m.group("/Subscription/Addons").notFilled())   // GroupNotFilled in an iterating rule
                .code("BAD").severity(Severity.ERROR)
                .message(Dsl.en("x"), Dsl.de("y")).build();
        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.mbackschat.a12.dm.core.ModelConsistencyException.class, () -> m.add(bad));
    }
}
