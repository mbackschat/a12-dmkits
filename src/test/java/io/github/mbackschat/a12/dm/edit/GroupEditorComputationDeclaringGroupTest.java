package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * Where a computation authored off a {@link GroupEditor} LIVES — the declaring group — as distinct from the
 * field it writes. The two are independent in A12 ({@link Computation.Builder#group}), so the editor-scoped
 * overloads have to answer the question, and this pins both answers: the default follows the computed field's
 * parent, and the editor's own group is reached by handing the editor itself to {@code group(...)}, since
 * {@link GroupEditor} is a {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupReference}. The path is
 * therefore written once, as a ref, with no second string for the group.
 *
 * <p>The default deliberately does <em>not</em> mirror {@link GroupEditor#addRule}'s scope-to-this-group. For a
 * rule the group IS the iteration scope and the editor is the natural one; for a computation the group decides
 * whether it runs once or per row, so defaulting to the editor would move a computation authored over a
 * descendant field ({@code editor.num("Sub/Total")}) out of a repeatable {@code Sub} and silently turn a
 * per-row computation into a single one. The asymmetry is the safe answer, not an oversight (was
 * DEV-API-GAPS DG41).
 */
class GroupEditorComputationDeclaringGroupTest {

    @Test
    void theDeclaringGroupDefaultsToTheComputedFieldsParent() {
        EditSession session = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor root = session.group("/Subscription");

        Computation built = root.computation(root.num("Billing/EffectiveFee"), copyOfBaseFee(root));

        assertEquals("/Subscription/Billing", built.groupPath(),
                "the editor's group is not imposed — the computation files beside the field it writes");
        assertEquals("../EffectiveFee", built.computedFieldRelPath());
    }

    @Test
    void theEditorItselfIsAcceptedAsTheDeclaringGroup() {
        EditSession session = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor root = session.group("/Subscription");

        NumberRef baseFee = root.num("Billing/BaseFee");
        Computation built = root.computation(root.num("Billing/EffectiveFee"), computation -> computation
                .name("EffectiveFeeComp")
                .group(root)
                .calc(baseFee.value())
                .message(Dsl.en("The effective fee follows the base fee."),
                        Dsl.de("Das effektive Entgelt folgt dem Grundentgelt."))
                .build());

        assertEquals("/Subscription", built.groupPath());
        assertEquals("../Billing/EffectiveFee", built.computedFieldRelPath(),
                "relative to the computation ELEMENT, so it steps out of it and back down to the target");
    }

    @Test
    void aComputationDeclaredInTheEditorsGroupPersistsThere() {
        EditSession session = EditSession.read(Fixtures.subscriptionJson());
        GroupEditor root = session.group("/Subscription");
        NumberRef baseFee = root.num("Billing/BaseFee");

        root.addComputation(root.num("Billing/EffectiveFee"), computation -> computation
                .name("EffectiveFeeComp")
                .group(root)
                .calc(baseFee.value())
                .message(Dsl.en("The effective fee follows the base fee."),
                        Dsl.de("Das effektive Entgelt folgt dem Grundentgelt."))
                .build());
        session.validate();

        assertEquals("/Subscription/EffectiveFeeComp",
                session.computation("/Subscription/EffectiveFeeComp").name(),
                "it is addressable at the declaring group, not at the computed field's parent");
    }

    /** The computation body both declaring-group cases share: copy {@code Billing/BaseFee} into the target. */
    private static Function<Computation.Builder, Computation> copyOfBaseFee(GroupEditor root) {
        NumberRef baseFee = root.num("Billing/BaseFee");
        return computation -> computation.name("EffectiveFeeComp")
                .calc(baseFee.value())
                .message(Dsl.en("The effective fee follows the base fee."),
                        Dsl.de("Das effektive Entgelt folgt dem Grundentgelt."))
                .build();
    }
}
