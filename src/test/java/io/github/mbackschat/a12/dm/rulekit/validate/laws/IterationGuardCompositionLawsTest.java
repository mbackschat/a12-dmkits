package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — how far "the guard must reference the queried level"
 * ({@code IterationGuardLevelLawsTest}) actually reaches, on the two carriers that row does not cover.
 *
 * <p>That row used two INDEPENDENT sibling groups under {@code And}. A client-side rule copying it would
 * apply to nested levels and to {@code Or} as well, and neither was measured — a scope transfer rather than a
 * measurement. These are the rows that bound the widening, because a lint rule that is too strict produces
 * false positives on legal models, which is the failure an advisory cannot afford.
 */
class IterationGuardCompositionLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** NESTED repeatable levels, so a guard can sit on an ancestor level of the negative. */
    private static final String MODEL = DocumentModelDraft.named("iteration-guard-composition").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Items", i -> i.repeatable(20)
                            .field("Label", FieldKind.STRING)
                            .group("Lines", l -> l.repeatable(20)
                                    .field("Sku", FieldKind.STRING)
                                    .field("Count", FieldKind.NUMBER))))
            .build().toJson();

    private static final String GROUP = "/Order/Items/Lines";

    /** The same-level control, on this fixture. */
    @Test
    void aGuardOnTheNegativesOwnLevelAdmitsIt() {
        KernelLaws.acceptsModel(MODEL, GROUP, "../Sku", "FieldFilled(Count) And FieldNotFilled(Sku)");
    }

    /**
     * ANCESTOR level. If an outer-level guard admits the negative, "the guard must reference the queried
     * level" is too strict as a lint rule and would fire on legal models.
     */
    @Test
    void aGuardOnAnAncestorRepeatableLevel() {
        KernelLaws.rejectsModel(MODEL, GROUP, "../Sku",
                "FieldFilled(../Label) And FieldNotFilled(Sku)", NEG);
    }

    /**
     * The CONNECTIVE. The measured mechanism says {@code Or} needs EVERY child admitted at the level, so a
     * positive guard that would license the negative under {@code And} must not license it under {@code Or}.
     * The linter treats both connectives alike.
     */
    @Test
    void theSameGuardUnderOrDoesNotAdmitIt() {
        KernelLaws.rejectsModel(MODEL, GROUP, "../Sku",
                "FieldFilled(Count) Or FieldNotFilled(Sku)", NEG);
    }
}
