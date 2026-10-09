package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * How the group-list duplicate gate keys a {@code $}-captured scope. A12's exact arm counts the marker and its
 * nested arm ignores it, so a group beside its own capture is admitted while a group beside a captured
 * descendant is nested (KERNEL-FINDINGS KF325, measured by {@code adapter.laws.CheckerOverlapIdentityAgreementTest}
 * on {@code AllGroupsFilled(Shipping, $/Order/Shipping)} and its siblings). The typed record must refuse exactly
 * the two refused shapes and build the admitted one. A scope's refusal withholds the kernel code, A12 possibly
 * refusing the scope while reading it, so the rows tell the two arms apart by the problem they state.
 */
class CapturedGroupListDuplicateTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("order-capture").locale("en_US")
            .rootGroup("Order", root -> root
                    .stringField("Reference", s -> { })
                    .group("Lines", lines -> lines.repeatable(20).indexField("Sku")
                            .stringField("Sku", s -> { }))
                    .group("Shipping", shipping -> shipping
                            .stringField("Carrier", s -> { })
                            .group("Address", address -> address.stringField("Street", s -> { }))))
            .build().toJson());

    private static final GroupListRef SHIPPING = M.flattenGroup("/Order/Shipping");

    @Test
    void aGroupBesideItsOwnCaptureIsTwoOperands() {
        assertDoesNotThrow(() -> Dsl.noGroupFilled(SHIPPING, SHIPPING.outer()));
    }

    @Test
    void aCaptureTwiceIsTheDuplicateClass() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> Dsl.noGroupFilled(SHIPPING.outer(), SHIPPING.outer())).getMessage();
        assertTrue(message.contains("is listed more than once"), () -> "expected the exact arm: " + message);
    }

    @Test
    void aGroupBesideItsCapturedSubgroupIsNested() {
        GroupListRef address = M.flattenGroup("/Order/Shipping/Address");
        String message = assertThrows(IllegalArgumentException.class,
                () -> Dsl.noGroupFilled(SHIPPING, address.outer())).getMessage();
        assertTrue(message.contains("is already included through"), () -> "expected the nested arm: " + message);
    }

    /**
     * An index selection on a captured scope keeps the marker in the text and in the identity. A12 refuses any
     * semantic index in a filter body ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}, measured on this shape by
     * {@code adapter.laws.CheckerOverlapIdentityAgreementTest}), so the record must hand A12 the operand as authored;
     * dropping the marker would author a different one.
     */
    @Test
    void anIndexOnACaptureKeepsTheMarker() {
        GroupListRef lines = M.flattenGroup("/Order/Lines");
        IndexedGroupValue captured = lines.outer().forIndex("A");
        assertEquals("GroupFilled($/Order/Lines For \"A\")", new DslRenderer().render(captured.filled()));
        assertDoesNotThrow(() -> Dsl.noGroupFilled(lines.forIndex("A"), captured));
    }
}
