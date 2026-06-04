package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.rulekit.Fixtures;

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
                io.github.mbackschat.a12.rulekit.model.ModelConsistencyException.class, () -> m.add(bad));
    }
}
