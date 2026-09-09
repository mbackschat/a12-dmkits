package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;

/**
 * The typed peer of {@code validate.laws.DateRangeConstructionEndpointComponentLawsTest}: a
 * {@code DateRange(start, end)} construction needs both endpoints to expose the same declared date-component
 * SET, and the kernel reports {@code MVK_WRONG_DATE_FORMAT_FOR_OP} at the construction when they differ.
 *
 * <p>It lives on {@link BoundModel} because an endpoint's component set comes from the field's declared
 * format, so only a model can resolve it — the contextual half of the API-FINDINGS AF148 split. The record's own
 * constructor keeps the INTRINSIC gate (each endpoint must be a date or date-fragment entity reference), which
 * is visible from the operands alone.
 *
 * <p>{@link Dsl#dateRange} stays ungated: {@code :adapter}'s converter builds constructions through it while
 * rebuilding a kernel-ACCEPTED model, and must never refuse one.
 */
class DateRangeEndpointComponentGateTest {

    private static final BoundModel M = BoundModel.ofJson(ModelDrafts.dateRangeProfiles().toJson());

    @Test
    void agreeingEndpointsAreAdmitted() {
        assertDoesNotThrow(() -> M.dateRangeOf(M.date("/Cover/FromDate"), M.date("/Cover/ToDate")));
        assertDoesNotThrow(
                () -> M.dateRangeOf(M.dateFragment("/Cover/FromYear"), M.dateFragment("/Cover/ToYear")));
    }

    /** Mixed DECLARED KINDS are fine while the components match — the gate compares sets, not kinds. */
    @Test
    void mixedEndpointKindsWithMatchingComponentsAreAdmitted() {
        assertDoesNotThrow(
                () -> M.dateRangeOf(M.dateFragment("/Cover/FromYear"), M.date("/Cover/ToYearAsDate")));
    }

    @Test
    void disagreeingEndpointsAreRefusedInEitherOrder() {
        var forward = assertThrows(IllegalArgumentException.class,
                () -> M.dateRangeOf(M.dateFragment("/Cover/FromYear"), M.date("/Cover/ToDate")));
        assertTrue(forward.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), forward::getMessage);

        var reversed = assertThrows(IllegalArgumentException.class,
                () -> M.dateRangeOf(M.date("/Cover/FromDate"), M.dateFragment("/Cover/ToYear")));
        assertTrue(reversed.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), reversed::getMessage);
    }

    /**
     * The INTRINSIC gate still runs first and reports its own refusal rather than this one: a calculated date
     * is not an entity reference, which is visible from the operand without a model.
     */
    @Test
    void theIntrinsicEntityGateStillPrecedesThisOne() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> M.dateRangeOf(M.date("/Cover/FromDate"), Dsl.today()));
        assertTrue(thrown.getMessage().contains("must be a date or date-fragment entity reference"),
                thrown::getMessage);
    }

    /** An endpoint whose declaration carries no format is DELEGATED, never refused. */
    @Test
    void anEndpointWithNoDeclaredFormatIsDelegated() {
        BoundModel undeclared = BoundModel.ofJson(
                io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("range-undeclared")
                        .locale("en_US")
                        .rootGroup("Cover", c -> c
                                .field("Ref", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING)
                                .dateField("NoFormat", d -> { })
                                .dateField("FullDate", d -> d.format("yyyy-MM-dd")))
                        .build().toJson());
        assertDoesNotThrow(() -> undeclared.dateRangeOf(
                undeclared.date("/Cover/NoFormat"), undeclared.date("/Cover/FullDate")));
    }
}
