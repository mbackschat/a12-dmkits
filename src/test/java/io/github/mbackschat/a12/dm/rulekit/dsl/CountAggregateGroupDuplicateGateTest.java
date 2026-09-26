package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * {@code NumberOfFilledGroups}' group list, whose duplicate arms the kernel has answered since 2026-08-10.
 *
 * <p>This carrier was sharper than an ungated one: {@code CountAggregate} called the dedup on its {@code
 * fields} list, which its own constructor forces EMPTY for this operator, so the call read as wiring and could
 * not fire while the {@code groups} list carrying the actual operands was never compared. A call-site scan
 * reports the carrier as gated, which is why the guard for this class has to be behavioural.
 *
 * <p>The exemption is carrier-specific here for a measured reason, and it is the reason this list is projected
 * rather than gated in place: {@code CountAggregate} renders a group as {@code path + "*"} when the group
 * repeats, so a repeatable operand IS starred in the emitted text and the kernel's exact arm skips it — while
 * the group-list quantifiers wrap the same {@link GroupRef} unstarred and render it bare. Exemption is a
 * property of the rendered operand, not of the operand type.
 *
 * <p>Each arm's class is withheld, and the arm is told apart by its problem text: A12 reads a group's emptiness
 * ahead of every list gate, which a {@link GroupRef} does not show ({@code validate.laws.EntityListOperandReadLawsTest}).
 */
class CountAggregateGroupDuplicateGateTest {

    private static final BoundModel DEPOT = BoundModel.ofJson(
            DocumentModelDraft.named("depotgate").locale("en_US")
                    .rootGroup("Depot", d -> d
                            .field("Code", FieldKind.STRING)
                            .field("Region", FieldKind.STRING)
                            .group("Alpha", a -> a
                                    .field("Label", FieldKind.STRING)
                                    .field("Capacity", FieldKind.NUMBER)
                                    .group("Nested", n -> n
                                            .field("Bin", FieldKind.STRING)
                                            .field("Slot", FieldKind.NUMBER)))
                            .group("Beta", b -> b
                                    .field("Label", FieldKind.STRING)
                                    .field("Capacity", FieldKind.NUMBER))
                            .group("Rows", r -> r.repeatable(10)
                                    .field("Item", FieldKind.STRING)
                                    .field("Qty", FieldKind.NUMBER)))
                    .build().toJson());

    private static String refusalFor(GroupRef first, GroupRef second) {
        return withheld(assertThrows(IllegalArgumentException.class, () -> Dsl.numberOfFilledGroups(first, second)));
    }

    /** The refusal's text, once shown to carry no code: A12 reads a group's emptiness ahead of both arms. */
    private static String withheld(IllegalArgumentException refusal) {
        assertFalse(refusal instanceof KernelCodeRefusal, refusal::getMessage);
        return refusal.getMessage();
    }

    @Test
    void theSameGroupTwiceIsRefused() {
        GroupRef alpha = DEPOT.group("/Depot/Alpha");
        String message = refusalFor(alpha, alpha);
        assertTrue(message.contains("is listed more than once"), () -> "expected the exact arm: " + message);
    }

    @Test
    void aGroupBesideItsOwnSubgroupIsRefusedWithTheOverlapCode() {
        String message = refusalFor(DEPOT.group("/Depot/Alpha"), DEPOT.group("/Depot/Alpha/Nested"));
        assertTrue(message.contains("is already included through"), () -> "an overlap is the OTHER arm: " + message);
    }

    /** The root beside a group it contains is the same overlap one level up. */
    @Test
    void theRootBesideAContainedGroupIsRefused() {
        String message = refusalFor(DEPOT.group("/Depot"), DEPOT.group("/Depot/Alpha"));
        assertTrue(message.contains("is already included through"), () -> "the root contains Alpha: " + message);
    }

    /** Two disjoint groups are the operator's ordinary shape. */
    @Test
    void twoDisjointGroupsAreAdmitted() {
        assertDoesNotThrow(() -> Dsl.numberOfFilledGroups(
                DEPOT.group("/Depot/Alpha"), DEPOT.group("/Depot/Beta")));
    }

    /** A field beside its containing group is an overlap, as it is in the one list the kernel reads. */
    @Test
    void aFieldBesideItsContainingGroupIsRefused() {
        String message = withheld(assertThrows(IllegalArgumentException.class,
                () -> new CountAggregate(CountAggregate.CountOp.FILLED_FIELDS,
                        List.of(DEPOT.field("/Depot/Alpha/Label"), DEPOT.flattenGroup("/Depot/Alpha")))));
        assertTrue(message.contains("is already included through"),
                () -> "a field beside its containing group: " + message);
    }

    /**
     * A REPEATABLE group renders starred on this carrier, so repeating it is the wildcard-exempt shape the
     * kernel admits — refusing it would over-reject. This is the row that makes the projection necessary
     * rather than cosmetic.
     */
    @Test
    void theSameRepeatableGroupTwiceStaysAdmittedBecauseItRendersStarred() {
        GroupRef rows = DEPOT.group("/Depot/Rows");
        assertDoesNotThrow(() -> Dsl.numberOfFilledGroups(rows, rows),
                "it renders as Rows*, which the kernel's exact-duplicate arm skips");
    }
}
