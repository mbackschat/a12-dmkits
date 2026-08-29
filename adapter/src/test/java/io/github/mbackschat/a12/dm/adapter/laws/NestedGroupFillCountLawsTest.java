package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>what the plain multi-group {@code NumberOfFilledGroups} COUNTS when a group's only
 * content lies in a descendant subgroup.</b>
 *
 * <p>a12-kernel-lean measured the static half ({@code SPEC-2026-08-30-01}: such a group is an admitted
 * operand at any position and arity) and left the counting rule open, having no runtime route: <i>"does a
 * nested-descendant operand count through the whole subtree, or over direct children only?"</i> Admission
 * cannot answer it — an operand can be legal and contribute nothing.
 *
 * <p><b>The discriminator</b> is one document in which the two accounts predict different numbers: the flat
 * group filled and the shell's descendant filled. Whole-subtree says 2, direct-children says 1, because under
 * that account a shell has no direct field and so can never be filled. The second document empties the
 * descendant, where both accounts say 1 — without it, a count stuck at 1 would satisfy the direct-children
 * prediction for the wrong reason.
 *
 * <p>Measured on BOTH arms, because they are separately specified and the count is documented as the stricter
 * of the two (KF120): the computation arm reads the value the kernel stores, the validation arm reads a
 * comparison against it.
 *
 * <p>Own domain (a supplier catalogue).
 */
class NestedGroupFillCountLawsTest {

    private static final String TALLY = "/Catalogue/SectionTally";

    /**
     * {@code Contact} is flat — a direct field. {@code Warranty} is a SHELL: no direct field at all, its only
     * field two levels down in {@code Terms}. That asymmetry is the whole fixture.
     */
    private static final String CATALOGUE = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .field("Reference", FieldKind.STRING)
                    .numberField("SectionTally", n -> n.fractionDigits(0))
                    .group("Contact", g -> g.field("Email", FieldKind.STRING))
                    .group("Warranty", w -> w.group("Terms", t -> t.field("Clause", FieldKind.STRING))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CATALOGUE);

    private static String computationModel() {
        Computation comp = M.computation(M.field(TALLY)).name("SectionTallyComp")
                .calc(Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Warranty"))
                        .expression())
                .message(en("Section tally."))
                .build();
        return M.add(comp).toJson();
    }

    /**
     * The conjunct is not decoration: an operand-only condition draws {@code MVK_ERROR_FIELD_NOT_REFERENCED},
     * because the count names groups and the error field sits outside all of them. {@code Reference} is filled
     * in both documents below, so the conjunct is true throughout and only the count moves.
     */
    private static String ruleModel(int expected) {
        String condition = new DslRenderer().render(
                Dsl.fieldFilled(M.field("/Catalogue/Reference")).and(
                        Dsl.numberOfFilledGroups(M.group("/Catalogue/Contact"), M.group("/Catalogue/Warranty"))
                                .eq(expected)));
        return RuntimeLaws.withRule(CATALOGUE, "/Catalogue", "TallyCheck", "../Reference", "TALLY", condition);
    }

    /** Both groups carry content — the flat one directly, the shell one two levels down. */
    private static RuntimeLaws.Doc bothFilled() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1)
                .field("/Catalogue/Warranty/Terms/Clause", "24 months", 1, 1, 1, 1);
    }

    /** The shell's descendant is empty, so only the flat group is filled under either account. */
    private static RuntimeLaws.Doc onlyFlatFilled() {
        return RuntimeLaws.doc().group("/Catalogue", 1)
                .field("/Catalogue/Reference", "CAT-1", 1, 1)
                .field("/Catalogue/Contact/Email", "buyer@example.com", 1, 1, 1);
    }

    private static Optional<BigDecimal> tally(RuntimeLaws.Doc doc) {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(computationModel(), doc);
        return RuntimeLaws.computedValue(out, TALLY).map(BigDecimal::new);
    }

    /**
     * <b>THE DISCRIMINATOR.</b> A shell whose only field lies in a descendant subgroup contributes to the
     * count, so the operator reads content over the whole subtree.
     */
    @Test
    void aShellWhoseOnlyFieldIsADescendantCountsAsFilled() {
        Optional<BigDecimal> counted = tally(bothFilled());
        assertTrue(counted.isPresent(), "the computation must produce a value at all");
        assertEquals(0, counted.get().compareTo(BigDecimal.valueOf(2)),
                () -> "whole-subtree predicts 2, direct-children predicts 1; got " + counted.get());
    }

    /**
     * <b>THE CONTROL.</b> Emptying the descendant drops the count to 1. Without it, a count that ignored the
     * shell entirely would be indistinguishable from one that read it and found it empty.
     */
    @Test
    void emptyingThatDescendantDropsTheCount() {
        Optional<BigDecimal> counted = tally(onlyFlatFilled());
        assertTrue(counted.isPresent(), "the computation must produce a value at all");
        assertEquals(0, counted.get().compareTo(BigDecimal.ONE),
                () -> "only the flat group is filled; got " + counted.get());
    }

    /**
     * The VALIDATION arm reaches the same number. The two arms are separately specified — the plain
     * multi-group count is documented as the stricter of the two on erroneous operands (KF120) — so their
     * agreement on the nested shape is measured rather than assumed.
     */
    @Test
    void theValidationArmCountsTheNestedDescendantTheSameWay() {
        assertTrue(RuntimeLaws.fires(ruleModel(2), "TALLY", bothFilled()),
                "the rule arm also sees 2 filled groups");
        assertFalse(RuntimeLaws.fires(ruleModel(1), "TALLY", bothFilled()),
                "and does not see 1 — the arms agree on the nested shape");
    }
}
