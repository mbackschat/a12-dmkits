package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * {@code FieldValueAsString([Field For key])} over an indexed cell (DEV-API-GAPS D11; KERNEL-SEMANTICS §9).
 * The operand is an {@link io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex} handle
 * ({@code ref.semanticIndex(key)}) fed to {@link Dsl#fieldValueAsString(io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex)},
 * the indexed peer of the plain {@code fieldValueAsString(field)} coercion. Rendered form pinned by the
 * interpreter differential {@code SemanticIndexMatchedInvalidCellDiffTest}; this proves the typed authoring
 * renders to it and the real kernel accepts it.
 */
class IndexedFieldValueAsStringTest {

    private static final BoundModel m = Fixtures.order();
    private static final String COUNT = "/Order/Items/Count";
    private static final String PC = "/Order/ProductCode";

    @Test
    void literalKeyedCoercionRendersBareIndexBody() {
        Condition c = Dsl.fieldValueAsString(m.num(COUNT).semanticIndex("SKU-1")).eq("5");
        assertEquals("FieldValueAsString(/Order/Items/Count For \"SKU-1\") == \"5\"",
                new DslRenderer().render(c));
        // DE: the operator + the "For" keyword localize
        assertEquals("FeldWertAlsString(/Order/Items/Count Fuer \"SKU-1\") == \"5\"",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void fieldKeyedCoercionRendersBareIndexBody() {
        Condition c = Dsl.fieldValueAsString(m.num(COUNT).semanticIndex(m.field(PC))).eq("5");
        assertEquals("FieldValueAsString(/Order/Items/Count For /Order/ProductCode) == \"5\"",
                new DslRenderer().render(c));
    }

    @Test
    void composesInAConcatenation() {
        // the corpus shape: a coerced indexed number cell spliced into a string concat
        Condition c = m.str("/Order/CustomerName").eq(
                Dsl.text("C-").plus(Dsl.fieldValueAsString(m.num(COUNT).semanticIndex("SKU-1"))));
        assertEquals("[/Order/CustomerName] == \"C-\" + FieldValueAsString(/Order/Items/Count For \"SKU-1\")",
                new DslRenderer().render(c));
    }

    @Test
    void theRealKernelAcceptsTypedIndexedCoercion() {
        // mirrors SemanticIndexPresenceDiffTest.fieldValueAsStringOverIndexedCell — a guarded rule.
        Condition c = m.str("/Order/CustomerName").filled()
                .and(Dsl.fieldValueAsString(m.num(COUNT).semanticIndex("SKU-1")).eq("5"));
        String rendered = new DslRenderer().render(c);
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "IndexedCoercionRule", "../CustomerName", rendered, "IDX", "ERROR"));
        assertTrue(vr.ok(), () -> "kernel rejected typed indexed FieldValueAsString: " + vr.diagnostics());
    }
}
