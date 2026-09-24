package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;

/**
 * The iteration-guard advisory over an index-selected ROW, {@code GroupNotFilled(Items For key)}. A12 splits it
 * on the key's form exactly as it splits the field form: a constant key pins the row and is admitted, a key read
 * at its own repeatable level iterates and is refused ({@code validate.laws.IndexedNegativeKeyFormLawsTest}).
 * So the advisory levels the row's negative by its key, as {@code IterationGuardLintLevelTest} locks for fields.
 */
class IterationGuardLintIndexedGroupTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("lint-indexed-group").locale("en_US")
                    .rootGroup("Order", o -> o
                            .field("Reference", FieldKind.STRING)
                            .group("Items", i -> i.repeatable(20).indexField("Sku")
                                    .field("Sku", FieldKind.STRING)
                                    .field("Count", FieldKind.NUMBER))
                            .group("Notes", n -> n.repeatable(20)
                                    .field("Text", FieldKind.STRING)))
                    .build().toJson());

    private final GroupListRef items = M.flattenGroup("/Order/Items");

    private static boolean warns(Condition condition) {
        return new Linter().lint(M, "/Order/Items/Sku", condition).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }

    @Test
    void aFieldKeyedRowNegativeWarns() {
        assertTrue(warns(items.forIndex(M.field("/Order/Notes/Text")).notFilled()));
    }

    /** The false-positive control: the same selection keyed by a constant is a pinned row, which A12 admits. */
    @Test
    void aConstantKeyedRowNegativeDoesNotWarn() {
        assertEquals(false, warns(items.forIndex("SKU-1").notFilled()));
    }

    /** The second control: a guard at the KEY's level bounds the iteration, so the same negative is silent. */
    @Test
    void aFieldKeyedRowNegativeGuardedAtTheKeysLevelDoesNotWarn() {
        assertEquals(false, warns(Dsl.fieldFilled(M.field("/Order/Notes/Text"))
                .and(items.forIndex(M.field("/Order/Notes/Text")).notFilled())));
    }
}
