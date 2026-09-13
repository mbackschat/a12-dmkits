package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * When a BARE field name reaches the field a message parameter means, and when the model makes it ambiguous.
 *
 * <p>{@link MessageParameterMembershipLawsTest} measures which entity a parameter must name; this measures
 * whether the SHORT form addresses it at all. The two interact directly: a checker resolving a bare name
 * through a model-wide fallback picks one candidate and then judges membership against it, so where A12
 * refuses the ADDRESS instead, that checker reports the membership class on a model A12 refused for a reason
 * of its own — an over-rejection, which is the one direction this project forbids.
 *
 * <p>Own domain (a claims policy carrying `Premium` in two groups and `Payout` in two others).
 */
class MessageParameterShortNameLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ADMITTED = KernelLaws.ADMITTED;

    /**
     * `Premium` sits in the root group AND in `Claims`; `Payout` in `Claims` AND `Endorsements`, neither of
     * which is a rule's declaring group here; `Code` sits in exactly one non-local group.
     *
     * <p>The three are what separate the readings: a duplicated name a declaring group OWNS, a duplicated
     * name it does not, and a unique name it does not. A fixture carrying only the first cannot tell a
     * local-first rule from a model-wide one, because the local hit answers both.
     */
    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2))
                    .stringField("Adjuster", f -> { })
                    .group("Claims", claims -> claims
                            .stringField("ClaimNo", f -> { })
                            .numberField("Premium", f -> f.fractionDigits(0, 2))
                            .numberField("Payout", f -> f.fractionDigits(0, 2)))
                    .group("Endorsements", endorsements -> endorsements
                            .stringField("Code", f -> { })
                            .numberField("Payout", f -> f.fractionDigits(0, 2))))
            .build().toJson();

    @Test
    void whenABareNameReachesTheFieldAndWhenTheModelMakesItAmbiguous() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a name the declaring group owns — the control",
                verdict(GROUP, "Reference", "see $Reference$"));
        actual.put("a name the declaring group owns, carried elsewhere too",
                verdict(GROUP, "Premium", "see $Premium$"));
        actual.put("the same name from the OTHER carrier",
                verdict(GROUP + "/Claims", "Premium", "see $Premium$"));
        actual.put("a NON-local name unique in the model",
                verdict(GROUP, "Endorsements/Code", "see $Code$"));
        actual.put("a NON-local name the model carries twice",
                verdict(GROUP, "Claims/Payout", "see $Payout$"));
        actual.put("the same, addressed by its path",
                verdict(GROUP, "Claims/Payout", "see $Claims/Payout$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a name the declaring group owns — the control", ADMITTED);
        expected.put("a name the declaring group owns, carried elsewhere too", ADMITTED);
        expected.put("the same name from the OTHER carrier", ADMITTED);
        expected.put("a NON-local name unique in the model", ADMITTED);
        // The fallback is LOCAL-FIRST and then model-wide, and only the model-wide tier can be ambiguous:
        // the two rows above are admitted because the declaring group owns a candidate, and the unique
        // non-local row shows the second tier exists at all. Without that row the local hit would explain
        // every admission and a local-only rule would fit the set exactly as well.
        expected.put("a NON-local name the model carries twice", "ERROR_TEXT_FIELDNAME_NOT_UNIQUE");
        expected.put("the same, addressed by its path", ADMITTED);
        assertEquals(expected, actual, "when a bare name reaches the field");
    }

    /**
     * …and whether a `..` step is legal at a depth where it does NOT leave the root group.
     *
     * <p>A rule in a root group has no legal `..` at all, so the refusal measured there
     * ({@code ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS}, {@link MessageParameterMembershipLawsTest})
     * cannot say whether the step itself is refused or only the overshoot. A checker that reports on a
     * parameter naming an entity through `..` needs the difference: if every `..` is refused, judging such a
     * parameter at all names a verdict A12 did not give.
     */
    @Test
    void whetherAStepUpIsLegalWhereItDoesNotLeaveTheRootGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a step up that lands inside the root group", fromClaims("see $../Premium$"));
        actual.put("the same entity addressed absolutely — the control",
                fromClaims("see $/Policy/Premium$"));
        // A path that ENDS in the step, naming the ancestor group itself rather than a field in it.
        actual.put("a bare step up, naming the group it lands on", fromClaims("see $..$"));
        actual.put("the same from a ROOT group, where it overshoots", verdict(GROUP, "Reference", "see $..$"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Only the OVERSHOOT is refused, not the step: a `..` landing inside the root group is ordinary, so
        // a checker may judge such a parameter and must report the overshoot under its own class instead.
        expected.put("a step up that lands inside the root group", ADMITTED);
        expected.put("the same entity addressed absolutely — the control", ADMITTED);
        // A path ending in the step is an ORDINARY address naming the group it lands on — an ordinary
        // membership refusal, not a grammar one — so a reader refusing that shape only loses findings.
        expected.put("a bare step up, naming the group it lands on", "INVALID_FIELD");
        expected.put("the same from a ROOT group, where it overshoots",
                "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS");
        assertEquals(expected, actual, "whether a step up is legal below the root group");
    }

    /**
     * Which class an UNRESOLVABLE parameter draws, which differs by the depth the name would have sat at.
     *
     * <p>The condition surface splits this pair — a candidate at root-group level is `MVK_INVALID_ROOT_GROUP`
     * and a deeper one `MVK_INVALID_ENTITY` — and `FehlertextErrorType` carries both spellings too, so a
     * checker mapping every unresolvable parameter to one of them would be wrong for the other depth.
     */
    @Test
    void whichClassAnUnresolvableParameterDrawsAtEachDepth() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a name below the declaring group", verdict(GROUP, "Reference", "see $NoSuchField$"));
        actual.put("a name at ROOT-GROUP level", verdict(GROUP, "Reference", "see $/NoSuchRoot$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a name below the declaring group", "INVALID_ENTITY");
        expected.put("a name at ROOT-GROUP level", "INVALID_ROOT_GROUP");
        assertEquals(expected, actual, "which class an unresolvable parameter draws at each depth");
    }

    /** A rule in `Claims` reading the ROOT group's `Premium`, whose error target is that same field. */
    private static String fromClaims(String text) {
        return KernelLaws.messageVerdict(POLICY, GROUP + "/Claims", "../../Premium",
                "FieldFilled(../Premium)", "en_US", text);
    }

    /** A rule in [group] whose condition reads [read] and whose error target is that same entity. */
    private static String verdict(String group, String read, String text) {
        return KernelLaws.messageVerdict(POLICY, group, "../" + read, "FieldFilled(" + read + ")",
                "en_US", text);
    }
}
