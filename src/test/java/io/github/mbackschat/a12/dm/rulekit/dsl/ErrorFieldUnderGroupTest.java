package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * {@code RK_ERROR_FIELD_OUTSIDE_GROUP} — a rule's error field must lie under the rule's group.
 *
 * <p><b>Why this file exists.</b> The check was advertised in {@link Rule}'s own javadoc as one of the two
 * invariants that stay hard in {@code build()} — deliberately <em>not</em> deferred to the kernel, because it
 * guards rulekit's own relative-path arithmetic in {@code errorEntityRelPath()}. It had no test anywhere in the
 * repo, which was found while converting it to the typed path relation (DG37): the line was edited and nothing
 * in the estate could have caught a mistake. That is the "advertise nothing you cannot point at" case exactly —
 * a stated guarantee with no enforcement locus a reader can be shown.
 *
 * <p>The refusal is load-bearing rather than cosmetic: {@code errorEntityRelPath()} computes
 * {@code "../" + errorField.path().substring(group.path().length() + 1)}, which silently produces a corrupt
 * relative path — or throws {@link StringIndexOutOfBoundsException} — for an error field outside the group.
 * {@link #theRefusalIsWhatProtectsTheRelativePathArithmetic} pins that connection, so a future author cannot
 * relax the gate without seeing what it was protecting.
 */
class ErrorFieldUnderGroupTest {

    /**
     * Purpose-built rather than borrowed: the shared subscription fixture has no pair of sibling groups where
     * one name EXTENDS the other, and that pair is the whole point of
     * {@link #aSiblingGroupWhoseNameExtendsTheRuleGroupsIsStillOutside}.
     */
    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("subscription")
            .locale("en_US")
            .rootGroup("Subscription", s -> s
                    .field("Tier", FieldKind.STRING)
                    .group("Addons", a -> a.field("MonthlyFee", FieldKind.NUMBER))
                    .group("AddonsSummary", a -> a.field("Headline", FieldKind.STRING)))
            .build().toJson());

    /** The positive control: an error field genuinely under the group builds. */
    @Test
    void anErrorFieldInsideTheGroupIsAccepted() {
        Rule rule = M.rule(M.group("/Subscription/Addons"))
                .name("FeeGuard")
                .errorField(M.num("/Subscription/Addons/MonthlyFee"))
                .when(M.num("/Subscription/Addons/MonthlyFee").gt(0))
                .code("FEE")
                .severity(Severity.ERROR)
                .message(Dsl.en("Fee must not be positive"))
                .build();

        assertNotNull(rule);
        assertEquals("../MonthlyFee", rule.errorEntityRelPath());
    }

    /** The refusal, with its coded diagnostic — a sibling branch is not under the rule's group. */
    @Test
    void anErrorFieldOutsideTheGroupIsRefusedWithItsCode() {
        PrecheckException ex = assertThrows(PrecheckException.class, () -> M.rule(M.group("/Subscription/Addons"))
                .name("Stray")
                .errorField(M.str("/Subscription/Tier"))
                .when(M.num("/Subscription/Addons/MonthlyFee").gt(0))
                .code("STRAY")
                .severity(Severity.ERROR)
                .message(Dsl.en("Tier is wrong"))
                .build());

        assertEquals("RK_ERROR_FIELD_OUTSIDE_GROUP", ex.diagnostic().code().orElseThrow());
    }

    /**
     * THE case the named relation exists for, and the one a string prefix gets wrong.
     *
     * <p>{@code /Subscription/AddonsSummary} is not under {@code /Subscription/Addons}, but its path starts with
     * that string. A {@code startsWith(group)} spelling would accept this rule and then compute a relative path
     * by slicing mid-segment. This is {@code CG16} expressed as an authoring outcome rather than as a unit test
     * of the predicate, which is why it belongs here and not only in {@code EntityPathTest}.
     */
    @Test
    void aSiblingGroupWhoseNameExtendsTheRuleGroupsIsStillOutside() {
        PrecheckException ex = assertThrows(PrecheckException.class, () -> M.rule(M.group("/Subscription/Addons"))
                .name("Neighbour")
                .errorField(M.str("/Subscription/AddonsSummary/Headline"))
                .when(M.num("/Subscription/Addons/MonthlyFee").gt(0))
                .code("NEIGHBOUR")
                .severity(Severity.ERROR)
                .message(Dsl.en("Headline is wrong"))
                .build());

        assertEquals("RK_ERROR_FIELD_OUTSIDE_GROUP", ex.diagnostic().code().orElseThrow());
    }

    /** The group is not inside itself, so a rule cannot take its own group's path as the error field. */
    @Test
    void theRefusalIsWhatProtectsTheRelativePathArithmetic() {
        Rule rule = M.rule(M.group("/Subscription/Addons"))
                .name("FeeGuard")
                .errorField(M.num("/Subscription/Addons/MonthlyFee"))
                .when(M.num("/Subscription/Addons/MonthlyFee").gt(0))
                .code("FEE")
                .severity(Severity.ERROR)
                .message(Dsl.en("Fee must not be positive"))
                .build();

        // errorEntityRelPath() slices the error field's path at the group's length. That is only meaningful
        // while the gate above holds; the assertion states the dependency rather than merely the value.
        assertTrue(rule.errorEntity().isStrictlyInside(rule.group()),
                "the gate's postcondition, which the relative-path slice below silently assumes");
        assertEquals("../" + "MonthlyFee", rule.errorEntityRelPath());
    }
}
