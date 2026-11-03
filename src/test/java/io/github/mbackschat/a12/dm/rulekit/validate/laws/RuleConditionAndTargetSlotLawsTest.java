package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The two slots a stored RULE cannot work without — {@code errorCondition} and {@code errorEntityRelPath} —
 * measured for PRESENCE, which no lock covers.
 *
 * <p>{@code Rule.Builder.build} requires both, so a checker reading stored DM-JSON meets shapes the typed
 * route cannot author. The discriminating question is whether a BLANK one counts as absent: it does for
 * {@code errorCode} and a message {@code text} ({@link RuleCodeAndSeverityLawsTest},
 * {@link MessageSlotRawShapeLawsTest}) and does not for a computation alternative's precondition, which
 * reaches the condition parser whatever it spells ({@link ComputationAlternativeSlotLawsTest}). Two sibling
 * families answering opposite ways is why this one is measured rather than inferred.
 *
 * <p><b>Both slots answer a third way, and it inverts the question.</b> A blank one is what draws a verdict
 * here; an ABSENT one produces none at all — the model stops being buildable, or readable. So for these two
 * members the shape a checker would report as "missing" is precisely the shape about which A12 says nothing,
 * and a finding there would present the checker's own reading as A12's.
 *
 * <p>Own domain (the shared subscription fixture, whose header declares {@code en_US} and {@code de_DE}).
 */
class RuleConditionAndTargetSlotLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the writer emits for the probe rule, so each row varies exactly one member away from it. */
    private static final String CANONICAL_CONDITION = "\"FieldNotFilled(Tier)\"";
    private static final String CANONICAL_TARGET = "\"../Tier\"";

    /**
     * A BLANK condition draws a verdict; an ABSENT one stops A12 from building the model at all.
     *
     * <p>That is the inversion this family exists to record. Three sibling slots read a blank string as
     * absent, so a checker carrying the law across would report one code for both shapes — and here the two
     * shapes sit in different TIERS, not merely under different codes: the blank one is A12's verdict about
     * the rule and the absent one is A12 saying it never got a model to judge.
     */
    @Test
    void anAbsentConditionStopsTheBuildWhereABlankOneDrawsAVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldNotFilled(Tier) — the control", verdict("errorCondition", CANONICAL_CONDITION));
        actual.put("absent", verdict("errorCondition", null));
        actual.put("empty", verdict("errorCondition", "\"\""));
        actual.put("whitespace only", verdict("errorCondition", "\"   \""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FieldNotFilled(Tier) — the control", KernelLaws.ADMITTED);
        // A rejection carrying no MVK_ code at all: the Kernel refuses to BUILD the model from inside
        // checkConsistency, so there is no verdict about this rule to report.
        expected.put("absent", KernelLaws.NOT_BUILDABLE);
        // Present-but-blank IS a verdict, and it carries two codes — the condition is missing AND the input
        // is incomplete. A one-code assertion here would not have seen the second gate.
        expected.put("empty", "MVK_CONDITION_MISSING,MVK_INCOMPLETE_INPUT");
        expected.put("whitespace only", "MVK_CONDITION_MISSING,MVK_INCOMPLETE_INPUT");
        assertEquals(expected, actual, "what A12 does with each shape of a rule's errorCondition");
    }

    /**
     * The same inversion on the error target, one tier further out still.
     *
     * <p>An absent {@code errorEntityRelPath} refuses A12's READER — the model never loads — while a blank
     * one is judged and draws two codes. The pair also names two codes a reader will mistake for each other:
     * {@code MVK_ERROR_FIELD_MISSING} and {@code MVK_MISSING_ERROR_FIELD} are different codes that fire
     * together, so quoting either alone under-reports what A12 said.
     */
    @Test
    void anAbsentErrorTargetRefusesTheReaderWhereABlankOneDrawsAVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("../Tier — the control", verdict("errorEntityRelPath", CANONICAL_TARGET));
        actual.put("absent", verdict("errorEntityRelPath", null));
        actual.put("empty", verdict("errorEntityRelPath", "\"\""));
        actual.put("whitespace only", verdict("errorEntityRelPath", "\"   \""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("../Tier — the control", KernelLaws.ADMITTED);
        // Read on kernel 31.1.1, and then dereferenced while the model is built ("errorEntity is null",
        // KERNEL-FINDINGS KF654); 30.8.1's reader refused it.
        expected.put("absent", KernelLaws.NOT_BUILDABLE);
        expected.put("empty", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("whitespace only", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        assertEquals(expected, actual, "what A12 does with each shape of a rule's errorEntityRelPath");
    }

    /**
     * Which spaces make the condition and the error target BLANK — the two members whose blank verdict
     * {@link #anAbsentConditionStopsTheBuildWhereABlankOneDrawsAVerdict} and its sibling measure on ASCII
     * spaces. Java's {@code Character.isWhitespace} excludes the three no-break spaces and includes the rest.
     */
    @Test
    void whichSpacesMakeTheConditionAndTheTargetBlank() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("errorCondition: a no-break space", verdict("errorCondition", "\"\\u00a0\""));
        actual.put("errorCondition: a figure space", verdict("errorCondition", "\"\\u2007\""));
        actual.put("errorCondition: a narrow no-break space", verdict("errorCondition", "\"\\u202f\""));
        actual.put("errorCondition: a vertical tab", verdict("errorCondition", "\"\\u000b\""));
        actual.put("errorCondition: an em space", verdict("errorCondition", "\"\\u2003\""));
        actual.put("errorCondition: an ideographic space", verdict("errorCondition", "\"\\u3000\""));
        actual.put("errorEntityRelPath: a no-break space", verdict("errorEntityRelPath", "\"\\u00a0\""));
        actual.put("errorEntityRelPath: a figure space", verdict("errorEntityRelPath", "\"\\u2007\""));
        actual.put("errorEntityRelPath: a narrow no-break space", verdict("errorEntityRelPath", "\"\\u202f\""));
        actual.put("errorEntityRelPath: a vertical tab", verdict("errorEntityRelPath", "\"\\u000b\""));
        actual.put("errorEntityRelPath: an em space", verdict("errorEntityRelPath", "\"\\u2003\""));
        actual.put("errorEntityRelPath: an ideographic space", verdict("errorEntityRelPath", "\"\\u3000\""));
        actual.put("errorEntityRelPath: an unresolvable path", verdict("errorEntityRelPath", "\"../Nowhere\""));
        actual.put("errorEntityRelPath: a no-break space before the target", verdict("errorEntityRelPath", "\"\\u00a0../Tier\""));
        actual.put("errorEntityRelPath: an ASCII space before the target", verdict("errorEntityRelPath", "\" ../Tier\""));
        actual.put("errorEntityRelPath: an em space before the target", verdict("errorEntityRelPath", "\"\\u2003../Tier\""));

        Map<String, String> expected = new LinkedHashMap<>();
        // The missing-condition test counts what Java's Character.isWhitespace counts, and the parse then
        // fails in the LEXER, because none of these is one of its five whitespace characters.
        expected.put("errorCondition: a no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("errorCondition: a figure space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("errorCondition: a narrow no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("errorCondition: a vertical tab", "MVK_CONDITION_MISSING,MVK_LEXER_STANDARD_ERROR");
        expected.put("errorCondition: an em space", "MVK_CONDITION_MISSING,MVK_LEXER_STANDARD_ERROR");
        expected.put("errorCondition: an ideographic space", "MVK_CONDITION_MISSING,MVK_LEXER_STANDARD_ERROR");
        // The target has no blank class of its own: every text that does not resolve draws the same pair,
        // an unresolvable path and a padded valid one included.
        expected.put("errorEntityRelPath: a no-break space", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: a figure space", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: a narrow no-break space", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: a vertical tab", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: an em space", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: an ideographic space", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: an unresolvable path", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: a no-break space before the target", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: an ASCII space before the target", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("errorEntityRelPath: an em space before the target", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        assertEquals(expected, actual, "which spaces make a rule's condition and target blank");
    }

    /** What A12 says about the probe model with one member of the rule replaced, or removed when null. */
    private static String verdict(String member, String rawJson) {
        return KernelLaws.verdictOf(probe(member, rawJson));
    }

    private static String probe(String member, String rawJson) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "SlotProbe",
                "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode rule = ruleBody(root);
            if (!rule.has(member)) {
                throw new IllegalStateException("the writer no longer emits `" + member + "`");
            }
            if (rawJson == null) {
                rule.remove(member);
            } else {
                rule.set(member, MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    /** The probe rule's body, refusing rather than silently returning a model the substitution never reached. */
    private static ObjectNode ruleBody(ObjectNode root) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("SlotProbe".equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Rule");
            }
        }
        throw new IllegalStateException("the probe rule is no longer where this test writes it");
    }
}
