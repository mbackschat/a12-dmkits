package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The starred-FIELDS side of the value-list quantifiers (DEV-API-GAPS shape (3)). The {@code Dsl.*ValueList}
 * entry points have taken a flattened repetition list ({@link BoundModel#flatten}) since 2026-06-04
 * ({@code FieldOperand} split) — the gap's "no flatten overload" sub-claim was stale (the WI-T
 * differential author reached for a raw string without checking the typed surface). This locks that the
 * flatten overload authors the exact stored forms pinned by {@code ValueListQuantifierStarDiffTest}, so
 * the "starred fields are typed-authorable" contract is a guarded fact, not folklore.
 */
class StarredValueListQuantifierTest {

    private static final BoundModel m = Fixtures.order();
    private static final String SKUS = "/Order/Items*/Sku";

    @Test
    void atLeastOneOverStarredStringFields() {
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of("A", "B"), m.flatten(SKUS));
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Sku In \"A\", \"B\")",
                new DslRenderer().render(c));
    }

    @Test
    void noOverStarredStringFields() {
        Condition c = Dsl.noFieldValueIncludedInValueList(List.of("A"), m.flatten(SKUS));
        assertEquals("NoFieldValueIncludedInValueList(/Order/Items*/Sku In \"A\")",
                new DslRenderer().render(c));
    }

    @Test
    void notAllOverStarredStringFields() {
        Condition c = Dsl.notAllFieldValuesIncludedInValueList(List.of("A", "B"), m.flatten(SKUS));
        assertEquals("NotAllFieldValuesIncludedInValueList(/Order/Items*/Sku In \"A\", \"B\")",
                new DslRenderer().render(c));
    }

    @Test
    void theRealKernelAcceptsTheStarredQuantifier() {
        Condition c = m.str("/Order/CustomerName").filled()
                .and(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("A", "B"), m.flatten(SKUS)));
        String rendered = new DslRenderer().render(c);
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "StarredVlqRule", "../CustomerName", rendered, "VLQ", "ERROR"));
        assertTrue(vr.ok(), () -> "kernel rejected the starred value-list quantifier: " + vr.diagnostics());
    }
}
