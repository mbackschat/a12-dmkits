package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;

/**
 * KERNEL-FINDINGS §9 — does an index selection pin a row because it is a SELECTION, or because its KEY is a
 * constant?
 *
 * <p>[KF429](#kf429) measures a selection keyed by a CONSTANT and concludes it pins a row, so an unguarded
 * negative over one is admitted. The advisory then suppresses EVERY indexed presence. An independent
 * differential reports that as wider than the row: a key that is itself a field read at a repeatable level
 * should ITERATE, and an unguarded negative over it be refused.
 *
 * <p><b>Measured: the key's form decides.</b> A constant-keyed negative is admitted and a field-keyed one is
 * refused, one variable apart. The CODE is the dedicated {@code MVK_SEMANTIC_INDEX_INVALID_ITER} rather than
 * the iteration code — and it is locus-dependent: the same field-keyed shape draws
 * {@code MVK_NEG_CONDITION_IN_ITERATION} when the rule sits at the KEY's group instead of the selected
 * field's, so neither code alone characterises the shape.
 *
 * <p>Both rows take the SELECTED field as the error field, so the negative itself satisfies the error-field
 * reference whatever the key's form, and the key is the only thing that differs. Appending
 * {@code And FieldFilled(<error field>)} would add a positive presence guard at the negative's own level and
 * admit every row — the trap recorded in [KF436](#kf436).
 */
class IndexedNegativeKeyFormLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** {@code Items} carries a semantic index; {@code Keys} supplies a key from its own repeatable level. */
    private static final String MODEL = DocumentModelDraft.named("indexed-key-form").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20).indexField("Sku")
                            .field("Sku", FieldKind.STRING)
                            .field("Qty", FieldKind.NUMBER))
                    .group("Keys", k -> k.repeatable(20)
                            .field("Wanted", FieldKind.STRING)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static String verdict(String ruleGroup, String errorField, Condition condition) {
        var result = KernelLaws.checkModel(MODEL, ruleGroup, errorField, KernelLaws.render(condition));
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void theKeysFormDecidesWhetherAnIndexedNegativeIterates() {
        var actual = new LinkedHashMap<String, String>();

        // A FIELD-VALUED key, read at its own repeatable level. The key IS the error field.
        Condition fieldKeyed = Dsl.fieldNotFilled(
                new SemanticIndexFieldValue(M.field("/Order/Items/Qty"), M.field("/Order/Keys/Wanted")));
        // Both rows sit at the SELECTED field's group with it as the error field, so the negative itself
        // satisfies the reference in either case and only the key's form differs.
        actual.put("field-valued key, unguarded", verdict("/Order/Items", "../Qty", fieldKeyed));

        // THE CONTROL, and [KF429]'s own row: the same selection keyed by a CONSTANT. Only the key's form
        // differs, so a divergence below is about the key and not about indexing.
        Condition constantKeyed = Dsl.fieldNotFilled(
                new SemanticIndexValue(M.field("/Order/Items/Qty"), "SKU-1"));
        actual.put("constant key, unguarded", verdict("/Order/Items", "../Qty", constantKeyed));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("field-valued key, unguarded", "MVK_SEMANTIC_INDEX_INVALID_ITER");
        expected.put("constant key, unguarded", KernelLaws.ADMITTED);

        assertEquals(expected, actual);
    }

    /**
     * The group peer, one variable apart again: {@code GroupNotFilled(Items For key)} with the key a constant
     * and a field read at its own repeatable level. The rule sits at {@code Items} with a field of it as the
     * error field, as above. Measured: the same split and the same code as the field form, so the advisory
     * levels a field-keyed group negative by its key too.
     */
    @Test
    void theKeysFormDecidesWhetherAnIndexedGroupNegativeIterates() {
        var actual = new LinkedHashMap<String, String>();
        var items = M.flattenGroup("/Order/Items");
        actual.put("field-valued key, unguarded",
                verdict("/Order/Items", "../Qty", items.forIndex(M.field("/Order/Keys/Wanted")).notFilled()));
        actual.put("constant key, unguarded", verdict("/Order/Items", "../Qty", items.forIndex("SKU-1").notFilled()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("field-valued key, unguarded", "MVK_SEMANTIC_INDEX_INVALID_ITER");
        expected.put("constant key, unguarded", KernelLaws.ADMITTED);

        assertEquals(expected, actual);
    }
}
