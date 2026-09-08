package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — B1: does a positive presence guard license a negative sibling <b>regardless of which
 * iteration level it references</b>?
 *
 * <p>The measured mechanism says no: {@code IterationNotValidVisitor} enumerates the relevant levels and
 * {@code And} needs one child that both REFERENCES and is admitted <em>at that level</em>. But every existing
 * lock exercises a guard on the same level as the negative, so "a positive guard admits the negative" and "a
 * positive guard AT THAT LEVEL admits it" are indistinguishable across the whole set — and rulekit's linter
 * implements the first, suppressing its warning for any positive presence anywhere in the tree.
 *
 * <p>This is the row that separates them: a guard on a DIFFERENT repeatable group.
 *
 * <p><b>Scoped to the STARRED spelling, which is the one this row takes</b> and not a property of
 * different-level guards in general: an UNSTARRED read into that same sibling group ADMITS the negative
 * ({@code IterationGuardAdmissionRoutesLawsTest}). The advisory generalised this row past its spelling and
 * warned on legal models for a fortnight before a differential over real ones caught it.
 *
 * <p><b>The error field is constant across every row here, and that is forced rather than chosen.</b> An error
 * field outside the rule group is refused outright, under a different code that pre-empts this one, so the axis
 * cannot be varied while the rule stays legal. The rival reading — that the error field selects the level the
 * guard must reference — is therefore <em>unfalsifiable</em> on a legal model rather than refuted: the error
 * field's level is always the rule group's. Measured next door in
 * {@code IterationGuardErrorFieldLevelLawsTest}, on a nested model; two sibling groups at one depth, as here,
 * leave nowhere to put such an error field at all.
 */
class IterationGuardLevelLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** Two independent repeatable groups, so a guard can reference a level the negative does not. */
    private static final String MODEL = DocumentModelDraft.named("iteration-guard-levels").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER))
                    .group("Notes", n -> n.repeatable(20)
                            .field("Text", FieldKind.STRING)))
            .build().toJson();

    /**
     * THE LIVENESS CONTROL: a guard on the SAME level admits the negative, which is the shape every existing
     * lock uses. Without this row a refusal below could be about the fixture rather than about the level.
     */
    @Test
    void aGuardOnTheSameLevelAdmitsTheNegative() {
        KernelLaws.acceptsModel(MODEL, "/Order/Items", "../Sku",
                "FieldFilled(Count) And FieldNotFilled(Sku)");
    }

    /**
     * THE DISCRIMINATOR. The guard references {@code Notes*}; the negative references {@code Items}. If the
     * rule were "any positive presence guard", this would be admitted.
     */
    @Test
    void aGuardOnADifferentRepeatableLevelDoesNotAdmitTheNegative() {
        KernelLaws.rejectsModel(MODEL, "/Order/Items", "../Sku",
                "AtLeastOneGroupFilled(/Order/Notes*) And FieldNotFilled(Sku)", NEG);
    }

    /** The unguarded baseline, so the discriminator above is not merely the negative's own refusal. */
    @Test
    void anUnguardedNegativeIsRefused() {
        KernelLaws.rejectsModel(MODEL, "/Order/Items", "../Sku", "FieldNotFilled(Sku)", NEG);
    }
}
