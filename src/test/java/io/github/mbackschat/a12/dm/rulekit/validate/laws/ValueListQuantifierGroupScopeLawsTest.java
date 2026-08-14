package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * {@code DG35} — the three plural value-list quantifiers now author a GROUP scope through the typed surface.
 *
 * <p>The shape was previously <b>type-level unrepresentable</b>: {@code ValueListOperand} permitted only
 * {@code FieldOperand} and {@code EnumCategoryAccess}, so a group could not be passed at all. That reads as
 * "unsupported" rather than "unverified", which is why nobody went looking for a measurement behind it — the
 * same trap as {@code DG33} and {@code DG34}. The kernel admits the shape
 * ({@link EntityListValueListGroupOperandLawsTest}, KERNEL-FINDINGS {@code kf187}).
 *
 * <p>This file locks the authoring surface end to end: the typed call compiles, renders the form the kernel
 * grammar expects, and is ACCEPTED by the real kernel.
 */
class ValueListQuantifierGroupScopeLawsTest {

    private static final BoundModel ORDER = Fixtures.order();

    /** A non-repeatable group whose expansion is String fields. */
    private static final String GROUP = "/Order/ShippingAddress";

    @Test
    void theThreeQuantifiersAuthorAGroupScopeAndTheKernelAcceptsEachOne() {
        record Case(String label, Condition condition) {}
        var group = ORDER.flattenGroup(GROUP);
        List<Case> cases = List.of(
                new Case("AtLeastOne",
                        Dsl.atLeastOneFieldValueIncludedInValueList(List.of("unknown", "n/a"), group)),
                new Case("No", Dsl.noFieldValueIncludedInValueList(List.of("test", "demo"), group)),
                new Case("NotAll", Dsl.notAllFieldValuesIncludedInValueList(List.of("a", "b"), group)));

        for (Case c : cases) {
            // The guard names the rule's ERROR FIELD, which the kernel requires the condition to reference
            // (MVK_ERROR_FIELD_NOT_REFERENCED) — the group operand does not satisfy that for a field outside it.
            String rendered = new DslRenderer().render(
                    ORDER.num("/Order/Quantity").filled().and(c.condition()));
            assertTrue(rendered.contains(GROUP),
                    () -> c.label() + " must render the group scope: " + rendered);
            KernelLaws.acceptsOrder("/Order", "../Quantity", rendered);
        }
    }

    /**
     * <b>The reference-classification row, and it is not incidental.</b> A group scope must register as a GROUP
     * reference, not as a field one. Registering the group's path in {@code fieldPaths} compiles, reads
     * plausibly, and makes {@link ConditionReferences#covers} answer FALSE for every field the kernel expands
     * the group to — the exact exactness-versus-reachability confusion behind {@code CG13}–{@code CG18}, which
     * cost five defects one layer up. This row exists because the first version of the widening did precisely
     * that.
     */
    @Test
    void aGroupScopeRegistersAsAGroupReferenceSoItCoversItsExpandedFields() {
        Condition condition =
                Dsl.noFieldValueIncludedInValueList(List.of("test"), ORDER.flattenGroup(GROUP));
        ConditionReferences refs = ConditionReferences.of(condition);

        assertTrue(refs.groupPaths().contains(GROUP),
                () -> "the group scope must be a GROUP reference, got groups=" + refs.groupPaths()
                        + " fields=" + refs.fieldPaths());
        assertEquals(java.util.Set.of(), refs.fieldPaths(),
                "…and it must NOT masquerade as a field reference");
        assertTrue(refs.covers(GROUP + "/Street"),
                "a field the kernel expands the group to must be COVERED — the reachability question");
        assertTrue(refs.covers(GROUP + "/City"), "…for every contained field, not just the first");
    }
}
