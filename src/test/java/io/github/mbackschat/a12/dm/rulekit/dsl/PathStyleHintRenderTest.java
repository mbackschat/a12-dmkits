package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.Fixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-reference path-style override (API-FINDINGS AF159). Path style was a renderer-wide switch, so a
 * condition whose error field reads naturally relative while its aggregate reads naturally absolute had no
 * typed form: relativizing rewrote the aggregate and rendering absolute rewrote the error field too.
 *
 * <p>Every {@link PathStyle} value is exercised here on purpose. The enum was declared, documented as
 * produced by the read converter and honoured by the renderer, and until 2026-09-22 <em>neither</em> was
 * true — no main-source site ever built a non-{@code CANONICAL} reference and the renderer never read the
 * hint. An enum whose values do nothing is the same defect as an unenforced invariant, so each value now
 * has behaviour a test can point at.
 */
class PathStyleHintRenderTest {

    private static final String DEEP_GROUP = "/Project/Milestones/Tasks";

    private static DslRenderer renderer(String baseGroup) {
        return new DslRenderer(ConditionLanguage.EN, baseGroup);
    }

    @Test
    void aRelativeErrorFieldBesideAnAbsoluteAggregateRendersAsWritten() {
        // the condition the override exists for; PathLawsTest hands this exact text to the kernel
        BoundModel m = Fixtures.project();
        // tag::path-style-override[]
        // Rendered against /Project/Milestones/Tasks, only the hinted aggregate escapes relativization:
        //   FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0
        Condition mixed = m.field("/Project/Milestones/Tasks/Effort").filled()
                .and(Dsl.sum(m.flatten("/Project/Milestones*/Budget").absolute()).gt(0));
        // end::path-style-override[]

        assertEquals("FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0",
                renderer(DEEP_GROUP).render(mixed));
    }

    @Test
    void theSameConditionWithoutTheHintStaysFullyRelative() {
        // the control: identical AST but for one absolute() call, so the hint is the only variable and the
        // row cannot pass by the renderer having changed its default
        BoundModel m = Fixtures.project();
        Condition canonical = m.field("/Project/Milestones/Tasks/Effort").filled()
                .and(Dsl.sum(m.flatten("/Project/Milestones*/Budget")).gt(0));

        assertEquals("FieldFilled(Effort) And Sum(../../Milestones*/Budget) > 0",
                renderer(DEEP_GROUP).render(canonical));
    }

    @Test
    void anAbsoluteHintOnAPlainFieldOverridesRelativizationForThatReferenceAlone() {
        BoundModel m = Fixtures.project();
        Condition c = m.field("/Project/Milestones/Tasks/Effort").filled()
                .and(m.field("/Project/ProjectName").absolute().filled());

        assertEquals("FieldFilled(Effort) And FieldFilled(/Project/ProjectName)",
                renderer(DEEP_GROUP).render(c));
    }

    @Test
    void aRelativeHintRefusesWhenThereIsNoGroupToRelativizeAgainst() {
        // refusing beats falling back to absolute: a hint exists exactly when the spelling is the point, so
        // silently emitting the other form is the one outcome the caller cannot have wanted
        BoundModel m = Fixtures.project();
        FieldRef plain = m.field("/Project/ProjectName");
        FieldRef hinted = new FieldRef(plain.path().value(), plain.kind(), PathStyle.RELATIVE);

        var e = assertThrows(IllegalArgumentException.class,
                () -> renderer(null).render(hinted.filled()));
        assertTrue(e.getMessage().contains("needs a base group"), e.getMessage());
    }

    @Test
    void aShortHintIsRefusedBecauseTheRendererCannotVerifyItsLegality() {
        // SHORT has no producer and could not be honoured if it did: a bare name is legal only when the
        // model allows short-name refs AND the name is model-wide unique, and the renderer holds a catalog
        BoundModel m = Fixtures.project();
        FieldRef plain = m.field("/Project/ProjectName");
        FieldRef hinted = new FieldRef(plain.path().value(), plain.kind(), PathStyle.SHORT);

        var e = assertThrows(IllegalArgumentException.class,
                () -> renderer(DEEP_GROUP).render(hinted.filled()));
        assertTrue(e.getMessage().contains("cannot be rendered"), e.getMessage());
    }

    @Test
    void theHintSurvivesTheCorrelationVerbAndViceVersa() {
        // absolute() and outer() are both copy-with verbs on the same record; neither may drop the other's
        // bit, which a naive implementation of either does
        BoundModel m = Fixtures.project();
        FieldRef ref = m.field("/Project/ProjectName");

        assertEquals(PathStyle.ABSOLUTE, ref.absolute().outer().styleHint());
        assertTrue(ref.absolute().outer().correlated());
        assertEquals(PathStyle.ABSOLUTE, ref.outer().absolute().styleHint());
        assertTrue(ref.outer().absolute().correlated());
    }

    @Test
    void aFlattenedListDelegatesItsHintToTheFieldItFlattens() {
        BoundModel m = Fixtures.project();
        assertEquals(PathStyle.CANONICAL, m.flatten("/Project/Milestones*/Budget").styleHint());
        assertEquals(PathStyle.ABSOLUTE, m.flatten("/Project/Milestones*/Budget").absolute().styleHint());
    }
}
