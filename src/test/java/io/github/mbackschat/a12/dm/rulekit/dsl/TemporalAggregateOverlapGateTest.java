package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The temporal aggregate's containment arm, which the shared gate wired and no client test reached.
 *
 * <p>It needed a fixture neither committed model has — a DATE field inside a group — so the model is built
 * here. Both group fields are dates of one format, so a kind or component-set bar cannot be what decides the
 * rows. Field names avoid the condition grammar's reserved words: {@code To} is one, and a fixture using it
 * fails to parse in a way that reads like a semantic verdict.
 *
 * <p>Kernel row: {@code MaxValue(/Cal/Window, /Cal/Window/Opens)} is {@code MVK_DUPLICATE_PARAM2}, with two
 * disjoint date fields admitted as the liveness control
 * ({@code validate.laws.DuplicateOverlapCarrierSweepLawsTest}).
 */
class TemporalAggregateOverlapGateTest {

    private static final BoundModel CAL = BoundModel.ofJson(
            DocumentModelDraft.named("calendar-gate").locale("en_US")
                    .rootGroup("Cal", c -> c
                            .field("Ref", FieldKind.STRING)
                            .dateField("Marker", d -> d.format("yyyy-MM-dd"))
                            .group("Window", w -> w
                                    .dateField("Opens", d -> d.format("yyyy-MM-dd"))
                                    .dateField("Closes", d -> d.format("yyyy-MM-dd"))))
                    .build().toJson());

    @Test
    void aGroupBesideADateFieldInsideItIsRefusedAsAnOverlap() {
        var scope = new GroupListRef(CAL.group("/Cal/Window"), List.of());
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> CAL.latestOf(scope, CAL.field("/Cal/Window/Opens")));
        assertTrue(refusal.getMessage().contains("MVK_DUPLICATE_PARAM2"),
                () -> "the containment arm owns this shape: " + refusal.getMessage());
    }

    /** The liveness control: two disjoint dates in the same group are the operator's ordinary shape. */
    @Test
    void twoDisjointDateFieldsAreAdmitted() {
        assertDoesNotThrow(() -> CAL.latestOf(
                CAL.field("/Cal/Window/Opens"), CAL.field("/Cal/Window/Closes")));
    }

    /** And the exact arm on this carrier, so both are controlled in one place. */
    @Test
    void theSameDateFieldTwiceIsRefused() {
        FieldRef opens = CAL.field("/Cal/Window/Opens");
        var refusal = assertThrows(IllegalArgumentException.class, () -> CAL.latestOf(opens, opens));
        assertTrue(refusal.getMessage().contains("MVK_DUPLICATE_PARAM1"),
                () -> "expected the exact-duplicate code: " + refusal.getMessage());
    }
}
