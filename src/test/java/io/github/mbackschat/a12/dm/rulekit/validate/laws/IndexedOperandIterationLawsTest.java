package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — B6: does an INDEX-SELECTED reference iterate at its repeatable level, or pin one row?
 *
 * <p>The question decides how a client-side iteration lint must treat it, and rulekit's answer today is an
 * assumption rather than a measurement: {@code Linter} reads an {@code IndexedPresenceCondition} exactly like
 * a plain field read, so it both counts as a negative needing a guard and counts as a guard for others at
 * that level.
 *
 * <p>If an index selection pins a row, it does not iterate, and an unguarded negative over one is not the
 * shape {@code MVK_NEG_CONDITION_IN_ITERATION} exists for. If it still iterates, the current treatment is
 * right and the lint needs no change. Either answer is worth having; only the assumption is not.
 */
class IndexedOperandIterationLawsTest {

    /** One repeatable group with a declared index, so a cell can be selected by key. */
    private static final String MODEL = DocumentModelDraft.named("indexed-operand-iteration").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20).indexField("RowKey")
                            .field("RowKey", FieldKind.STRING)
                            .field("Sku", FieldKind.STRING)
                            .field("Spare", FieldKind.STRING)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** The index-selected cell, built typed and rendered — the spelling is the DSL's, not this test's. */
    private static String rendered(boolean filled) {
        SemanticIndexValue cell = new SemanticIndexValue(M.field("/Order/Items/Sku"), "K1");
        Condition c = filled ? Dsl.fieldFilled(cell) : Dsl.fieldNotFilled(cell);
        return new DslRenderer().render(c);
    }

    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, group, errorField, condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    @Test
    void anIndexSelectedNegativeAgainstAPlainOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        // The baseline: a plain per-row negative at the iterating level is the refused shape.
        actual.put("plain negative, iterating", verdict("/Order/Items", "../Sku", "FieldNotFilled(Sku)"));
        // The question: the same negative on a cell selected by key, from OUTSIDE the repeatable group.
        actual.put("index-selected negative", verdict("/Order", "../Items/Sku", rendered(false)));
        // Liveness for the indexed form: the positive reads the same cell and must be admitted.
        actual.put("index-selected positive", verdict("/Order", "../Items/Sku", rendered(true)));
        // THE CONTROL the first version of this class lacked: the discriminator moves the rule group as well
        // as the index selection, so this row holds the group at /Order and drops the index. If a PLAIN
        // negative is admitted here too, the group is the explanation and the pinning claim collapses.
        actual.put("plain negative, rule at /Order", verdict("/Order", "../Items/Sku",
                "FieldNotFilled(/Order/Items/Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("plain negative, iterating", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("index-selected negative", "admitted");
        expected.put("index-selected positive", "admitted");
        expected.put("plain negative, rule at /Order", "MVK_NEG_CONDITION_IN_ITERATION");
        Assertions.assertEquals(expected, actual,
                "whether an index selection pins a row (no iteration) or iterates like a plain read");
    }
}
