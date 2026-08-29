package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A computation's <b>declaring group</b> is independent of its computed field's parent — the A12 shape
 * {@code Computation.Builder.group(...)} exposes.
 *
 * <p><b>What the accepts below do and do not establish.</b> They show that PLACEMENT alone never refuses a
 * computation. They do <em>not</em> show a rule/computation asymmetry: {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}
 * turns out to gate ITERATION rather than geometry, and it reaches both constructs alike — the tables are
 * {@code validate.laws.ComputationDeclaringGroupLawsTest} and [KF212]. Every operation here is a plain field
 * copy with no per-row operand, which is what keeps that axis out of these rows.
 *
 * <p>The stored consequence is the {@code computedFieldRelPath}, which stops being {@code "../<Field>"} and
 * becomes a walk out of the declaring group and back down — the form
 * {@code adapter/.../laws/CrossGroupConstantComputeScopeDiffTest} pins on the runtime side.
 *
 * <p>{@code RuleValidator.check} is the oracle here: it injects the draft and runs the kernel's own
 * {@code checkConsistency}, so an accept below is the kernel's verdict and not this file's reasoning.
 */
class ComputationDeclaringGroupTest {

    private static BoundModel m;
    private static String modelJson;

    @BeforeAll
    static void bind() throws Exception {
        try (InputStream in = ComputationDeclaringGroupTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        m = BoundModel.of(ModelIndex.ofSelfContained(modelJson));
    }

    private Computation.Builder effectiveFee(String name) {
        return m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name(name)
                .calc(m.num("/Subscription/Billing/BaseFee").value())
                .message(en("EffectiveFee must equal BaseFee."),
                        de("Die effektive Gebühr muss der Basisgebühr entsprechen."));
    }

    /**
     * Criterion 3's library half: omitting the group leaves the placement and the stored reference untouched —
     * and a {@code null} argument means the same thing, which is the project's builder convention rather than
     * an accident of this setter.
     */
    @Test
    void withoutAGroupTheComputationStillLandsBesideItsComputedField() {
        Computation comp = effectiveFee("EffectiveFeeComp").build();

        assertEquals("/Subscription/Billing", comp.groupPath());
        assertEquals("../EffectiveFee", comp.computedFieldRelPath());

        Computation nulled = effectiveFee("EffectiveFeeComp").group(null).build();
        assertEquals(comp.groupPath(), nulled.groupPath(), "group(null) leaves the aspect unset");
        assertEquals(comp.computedFieldRelPath(), nulled.computedFieldRelPath());
    }

    @Test
    void anExplicitGroupPlacesTheComputationThereAndRebasesTheStoredReference() {
        Computation comp = effectiveFee("EffectiveFeeComp")
                .group(m.group("/Subscription"))
                .build();

        assertEquals("/Subscription", comp.groupPath());
        assertEquals("../Billing/EffectiveFee", comp.computedFieldRelPath(),
                "the reference is relative to the computation ELEMENT, so one '..' climbs off the element itself");

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "the kernel should accept a computation declared outside its computed field's"
                + " parent, got: " + vr.diagnostics());
    }

    /**
     * The declaring group is also the base the operand paths render against, so moving the computation moves
     * the condition text with it — the same relativization a rule's group drives.
     */
    @Test
    void theDeclaringGroupIsAlsoTheRenderingBaseForOperands() {
        assertEquals("[BaseFee]", effectiveFee("Beside").build()
                .draft().alternatives().get(0).operation());
        assertEquals("[Billing/BaseFee]", effectiveFee("Above").group(m.group("/Subscription")).build()
                .draft().alternatives().get(0).operation());
    }

    /**
     * The degenerate case the {@code relativize} javadoc names: a computation whose ELEMENT path equals its
     * computed field's, which yields the empty reference.
     *
     * <p>{@code build()} does not refuse it and the raw {@code ModelWriter.addComputation} static would persist
     * it — what actually stands in the way is the validator's name-collision precheck, since the element and
     * the field would have to share a name inside one group. This pins that division of labour, because "never
     * reached" was the original claim and it is only true of the GATED path.
     */
    @Test
    void anElementSharingItsComputedFieldsPathYieldsTheEmptyReferenceAndIsRefusedByTheNameGate() {
        Computation degenerate = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFee")
                .group(m.group("/Subscription/Billing"))
                .calc(m.num("/Subscription/Billing/BaseFee").value())
                .message(en("Degenerate."), de("Degeneriert."))
                .build();

        assertEquals("", degenerate.computedFieldRelPath(), "the element IS the target, so nothing to walk");

        ValidationResult vr = new RuleValidator().check(modelJson, degenerate.draft());
        assertFalse(vr.ok(), "the gated path must refuse it");
        assertTrue(vr.diagnostics().stream().anyMatch(d -> d.code().filter("RK_NAME_EXISTS"::equals).isPresent()),
                () -> "refused for the name collision, which is what makes it unreachable: " + vr.diagnostics());
    }

    /**
     * A group whose subtree does not contain the computed field at all — the true sibling-subtree walk, and
     * here the declaring group is REPEATABLE (Addons, repeatability 20) while the target is not.
     *
     * <p>Accepted, which is a static verdict and nothing more: {@code checkConsistency} settles whether the
     * model loads, not how often the computation fires or at which context. That runtime question is
     * {@code adapter/.../laws/CrossGroupConstantComputeScopeDiffTest}'s, and it is not answered here.
     */
    @Test
    void aRepeatableSiblingSubtreeGroupIsAcceptedAndWalksOutAndBackDown() {
        Computation comp = effectiveFee("EffectiveFeeComp")
                .group(m.group("/Subscription/Addons"))
                .build();

        assertEquals("/Subscription/Addons", comp.groupPath());
        assertEquals("../../Billing/EffectiveFee", comp.computedFieldRelPath());

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "the kernel should accept it statically, got: " + vr.diagnostics());
    }
}
