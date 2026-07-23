package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The typed surface of the common field-or-group entity list (SPEC-2026-07-23-09): the fixed
 * group-list quantifiers take {@link GroupListOperand} — a checked field ({@link FieldRef}) or an
 * exact group scope ({@link GroupListRef}) — and render each operand by its own kind. The group-only
 * constructors stay source-compatible; {@code groups()} stays the group-only view and fails fast on
 * a mixed list instead of silently dropping field operands. Kernel admission/rejection is F4-locked
 * in {@code validate.laws.GroupListEntityOperandLawsTest}; runtime in
 * {@code adapter.laws.GroupListEntityOperandDiffTest}.
 */
class GroupListEntityOperandDslTest {

    private final BoundModel m = BoundModel.ofJson(Fixtures.orderJson());

    @Test
    void rendersAMixedFieldGroupEntityList() {
        Condition mixed = Dsl.atLeastOneGroupFilled(
                m.field("/Order/CustomerName"), m.flattenGroup("/Order/ShippingAddress"));

        assertEquals(
                "AtLeastOneGroupFilled(/Order/CustomerName, /Order/ShippingAddress)",
                new DslRenderer().render(mixed));
    }

    @Test
    void rendersAFieldOnlyEntityList() {
        Condition fields = Dsl.notAllGroupsFilled(
                m.field("/Order/CustomerName"), m.field("/Order/ShippingAddress/Street"));

        assertEquals(
                "NotAllGroupsFilled(/Order/CustomerName, /Order/ShippingAddress/Street)",
                new DslRenderer().render(fields));
    }

    @Test
    void referencesReportFieldAndGroupOperandsSeparately() {
        Condition mixed = Dsl.allGroupsFilled(
                m.field("/Order/CustomerName"), m.flattenGroup("/Order/ShippingAddress"));

        ConditionReferences refs = ConditionReferences.of(mixed);

        assertTrue(refs.fieldPaths().contains("/Order/CustomerName"), "field operand is a field reference");
        assertTrue(refs.groupPaths().contains("/Order/ShippingAddress"), "group operand is a group reference");
    }

    @Test
    void groupsViewStaysAvailableForGroupOnlyLists() {
        GroupListPredicate predicate = (GroupListPredicate) Dsl.allGroupsFilled(
                m.group("/Order/ShippingAddress"), m.group("/Order/BillingAddress"));

        assertEquals(
                List.of(m.group("/Order/ShippingAddress"), m.group("/Order/BillingAddress")),
                predicate.groups());
    }

    @Test
    void groupsViewFailsFastOnAFieldOperand() {
        GroupListPredicate mixed = (GroupListPredicate) Dsl.allGroupsFilled(
                m.field("/Order/CustomerName"), m.flattenGroup("/Order/ShippingAddress"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, mixed::groups);
        assertTrue(failure.getMessage().contains("/Order/CustomerName"), "names the field operand");
        assertTrue(failure.getMessage().contains("operands()"), "points at the full view");
    }
}
