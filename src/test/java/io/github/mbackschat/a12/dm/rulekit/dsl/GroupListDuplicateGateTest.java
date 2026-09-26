package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed twin of the group-list quantifiers' duplicate and overlap rows, which the kernel has answered since
 * 2026-07-29 and the typed surface has been accepting ever since.
 *
 * <p>Seventeen rows across {@code validate.laws.GroupListEntityOperandLawsTest} and {@code
 * GroupListDiagnosticIdentityLawsTest} measure this carrier, and `GroupListPredicate` carried no refusal at
 * all — the largest single instance of the measured-but-unpromoted class. The three shapes below are the
 * separators: a bare duplicate is refused, an ancestor/descendant pair is refused with the OTHER code, and a
 * repeated STARRED scope stays admitted because the kernel's exact arm skips a wildcarded ref.
 *
 * <p>The starred row is the one that makes this gate non-trivial. Exempting every group scope — which is what
 * the shared identity did before this gate existed — is right for {@code Damages*} and wrong for
 * {@code Handler}, so the exemption has to key on whether the operand actually carries a star.
 */
class GroupListDuplicateGateTest {

    private static final BoundModel CLAIM = BoundModel.ofJson(
            DocumentModelDraft.named("claimgate").locale("en_US")
                    .rootGroup("Claim", c -> c
                            .field("PolicyNumber", FieldKind.STRING)
                            .field("IncidentNote", FieldKind.STRING)
                            .group("Vehicle", v -> v
                                    .field("Vin", FieldKind.STRING)
                                    .field("Plate", FieldKind.STRING)
                                    .group("Registration", r -> r
                                            .field("Authority", FieldKind.STRING)
                                            .field("IssuedOn", FieldKind.STRING)))
                            .group("Handler", h -> h
                                    .field("Name", FieldKind.STRING)
                                    .field("Phone", FieldKind.STRING))
                            .group("Damages", d -> d.repeatable(5).indexField("Description")
                                    .field("Description", FieldKind.STRING)
                                    .field("Amount", FieldKind.NUMBER)))
                    .build().toJson());

    @Test
    void theSameBareGroupTwiceIsRefused() {
        GroupRef handler = CLAIM.group("/Claim/Handler");
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.noGroupFilled(handler, handler));
        // the exact arm, whose class is withheld: A12 reads each group before its duplicate gate, and an empty
        // group answers there (validate.laws.EntityListQuantifierReadLawsTest)
        String message = refusal.getMessage();
        assertTrue(message.contains("is listed more than once") && !message.contains("MVK_"),
                () -> "the exact arm, uncoded: " + message);
    }

    @Test
    void aGroupBesideItsOwnSubgroupIsRefusedWithTheOverlapCode() {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> Dsl.allGroupsFilled(
                        CLAIM.group("/Claim/Vehicle"), CLAIM.group("/Claim/Vehicle/Registration")));
        String message = refusal.getMessage();
        assertTrue(message.contains("is already included through") && !message.contains("MVK_"),
                () -> "the containment arm, not the exact one, uncoded: " + message);
    }

    @Test
    void aRepeatedStarredScopeStaysAdmitted() {
        GroupListRef damages = CLAIM.flattenGroup("/Claim/Damages*");
        assertDoesNotThrow(() -> Dsl.noGroupFilled(damages, damages),
                "the kernel's exact arm skips a wildcarded ref, so refusing this would over-reject");
    }

    /**
     * An INDEX-SELECTED group does not contain its plainly-addressed descendant, so the overlap arm stays
     * silent even though the paths do nest.
     *
     * <p>Containment compares the modifiers as well as the path relation, measured one variable apart on the
     * aggregate carrier: a starred ancestor overlaps its starred descendant while the same ancestor
     * index-selected finds no duplicate at all (KERNEL-FINDINGS {@code kf439}). That measurement is on a
     * different carrier, and the transfer rests on the containment arm being one shared kernel check with one
     * caller rather than on a row taken here — the group-list spelling of this shape cannot be measured,
     * because a per-ref construction bar refuses the unstarred descendant first. So this asserts our gate's
     * LENIENCY, which delegates, and never that the kernel admits the pair.
     */
    @Test
    void anIndexSelectedGroupDoesNotContainItsPlainDescendant() {
        var indexed = new GroupListRef(CLAIM.group("/Claim/Damages"), List.of()).forIndex("D1");
        assertDoesNotThrow(() -> Dsl.allGroupsFilled(indexed, CLAIM.field("/Claim/Damages/Description")),
                "the index selection is part of what containment compares, so this is not an overlap");
    }

    /** A distinct pair is the ordinary shape and must stay admitted — the gate must not fire on list length. */
    @Test
    void twoDistinctGroupsAreAdmitted() {
        assertDoesNotThrow(() -> Dsl.noGroupFilled(
                CLAIM.group("/Claim/Handler"), CLAIM.group("/Claim/Vehicle")));
    }
}
