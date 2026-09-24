package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The other two declaration slots a stored RULE carries — {@code errorCode} and {@code severity} — measured,
 * because both are {@code pre-checked} in the MVK ledger: {@code Rule.Builder.build} refuses them, so what
 * A12 itself does had never been asked, and a checker reading stored DM-JSON meets shapes the builder cannot
 * produce.
 *
 * <p><b>The rule carrier is the only one, and that is a shape fact rather than an assumption.</b> A
 * computation's DM-JSON has no {@code errorCode} and no {@code severity} member at all — {@code ModelWriter}
 * writes neither, and {@link ComputationSlotLawsTest#theProbeShapeItselfIsAccepted} admits a computation that
 * carries neither — so there is nothing on that carrier for this family to check. Stated explicitly because
 * the sibling slot law went the other way: {@code MVK_RULE_NAME_INVALID} reads as a rule's law and fires for
 * computations too.
 *
 * <p>Own domain (the shared subscription fixture, whose header declares {@code en_US} and {@code de_DE}).
 */
class RuleCodeAndSeverityLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the writer emits for the probe rule, so each row varies exactly one member away from it. */
    private static final String CANONICAL_CODE = "\"PROBE\"";
    private static final String CANONICAL_SEVERITY = "\"ERROR\"";

    @Test
    void theErrorCodeSlotIsRequiredAndBlankCountsAsAbsent() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("PROBE — the control", verdict("errorCode", CANONICAL_CODE));
        actual.put("absent", verdict("errorCode", null));
        actual.put("empty", verdict("errorCode", "\"\""));
        actual.put("whitespace only", verdict("errorCode", "\"   \""));
        actual.put("a number", verdict("errorCode", "7"));
        actual.put("null", verdict("errorCode", "null"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("PROBE — the control", KernelLaws.ADMITTED);
        expected.put("absent", "MVK_ERROR_CODE_MISSING");
        expected.put("empty", "MVK_ERROR_CODE_MISSING");
        expected.put("whitespace only", "MVK_ERROR_CODE_MISSING");
        // Jackson coerces a scalar to the String member, so `7` is the error code "7" and the model is
        // valid. A reader that refused a non-string here would reject a model A12 accepts.
        expected.put("a number", KernelLaws.ADMITTED);
        // …and an explicit null does NOT behave like an absent member: it refuses the READER, so A12 never
        // judges the model. The pair is this table's discriminator — "missing" and "null" are one shape to
        // most readers and two tiers to A12.
        expected.put("null", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each shape of a rule's errorCode");
    }

    /**
     * Which spaces count as BLANK in the error code. Java's {@code Character.isWhitespace} excludes the three
     * no-break spaces and includes the other three rows, so the rows split exactly where a reader taking
     * "blank" from another platform's definition would diverge.
     */
    @Test
    void whichSpacesMakeTheErrorCodeBlank() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a no-break space", verdict("errorCode", "\"\\u00a0\""));
        actual.put("a figure space", verdict("errorCode", "\"\\u2007\""));
        actual.put("a narrow no-break space", verdict("errorCode", "\"\\u202f\""));
        actual.put("a vertical tab", verdict("errorCode", "\"\\u000b\""));
        actual.put("an em space", verdict("errorCode", "\"\\u2003\""));
        actual.put("an ideographic space", verdict("errorCode", "\"\\u3000\""));

        Map<String, String> expected = new LinkedHashMap<>();
        // Not blank, so the code's CONTENT law answers instead: "The error code is only allowed to contain
        // letters, digits, blanks and underscores.", in prose and without a class key.
        expected.put("a no-break space", KernelLaws.REFUSED_CODELESS);
        expected.put("a figure space", KernelLaws.REFUSED_CODELESS);
        expected.put("a narrow no-break space", KernelLaws.REFUSED_CODELESS);
        expected.put("a vertical tab", "MVK_ERROR_CODE_MISSING");
        expected.put("an em space", "MVK_ERROR_CODE_MISSING");
        expected.put("an ideographic space", "MVK_ERROR_CODE_MISSING");
        assertEquals(expected, actual, "which spaces make a rule's errorCode blank");
    }

    /**
     * The error code's CONTENT law, which the no-break rows above reach: A12 states it in prose alone, so no
     * checker can cite a class for it. Its "blanks" are an ASCII space, padding included, and its "letters"
     * reach past ASCII.
     */
    @Test
    void theErrorCodeContentLawIsStatedInProseAlone() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("A B", verdict("errorCode", "\"A B\""));
        actual.put(" A", verdict("errorCode", "\" A\""));
        actual.put("Ä", verdict("errorCode", "\"Ä\""));
        actual.put("A_1", verdict("errorCode", "\"A_1\""));
        actual.put("A-B", verdict("errorCode", "\"A-B\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("A B", KernelLaws.ADMITTED);
        expected.put(" A", KernelLaws.ADMITTED);
        expected.put("Ä", KernelLaws.ADMITTED);
        expected.put("A_1", KernelLaws.ADMITTED);
        expected.put("A-B", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "which characters a rule's errorCode may hold");

        ModelConsistencyException refused = assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofJson(probe("errorCode", "\"A-B\"")));
        assertTrue(refused.notifications().stream().anyMatch(n -> n.message().contains(
                        "The error code is only allowed to contain letters, digits, blanks and underscores.")),
                () -> "the content law's own sentence, got: " + refused.notifications());
    }

    /**
     * Which members an explicit JSON {@code null} refuses — the mechanism row for the pair above.
     *
     * <p>Without it "an explicit null refuses" is a claim about {@code errorCode} generalized to the shape,
     * and the rival reading is just as plausible: that the two members measured happen to be read by
     * null-hostile accessors. {@code errorCondition} is a third String member on the same element and is
     * refused too, so the refusal follows the null rather than the member — as far as three members show.
     * That is the limit of what these rows establish; a member outside them is unmeasured.
     */
    @Test
    void anExplicitNullRefusesTheReaderWhereAnAbsentMemberDoesNot() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String member : List.of("errorCode", "severity", "errorCondition", "errorEntityRelPath")) {
            actual.put(member + " = null", verdict(member, "null"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("errorCode = null", KernelLaws.UNREADABLE);
        expected.put("severity = null", KernelLaws.UNREADABLE);
        expected.put("errorCondition = null", KernelLaws.UNREADABLE);
        expected.put("errorEntityRelPath = null", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "an explicit null on a rule member");
    }

    @Test
    void theSeveritySlotIsRequiredAndIsAStrictEnum() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ERROR — the control", verdict("severity", CANONICAL_SEVERITY));
        actual.put("WARNING", verdict("severity", "\"WARNING\""));
        actual.put("INFO", verdict("severity", "\"INFO\""));
        actual.put("absent", verdict("severity", null));
        actual.put("null", verdict("severity", "null"));
        actual.put("empty", verdict("severity", "\"\""));
        actual.put("lowercase error", verdict("severity", "\"error\""));
        actual.put("Error", verdict("severity", "\"Error\""));
        actual.put("FATAL", verdict("severity", "\"FATAL\""));
        // The three shapes a consumer of this law has to decide and the rows above did not reach: the
        // sibling `errorCode` slot COERCES a number and reads whitespace as blank, so neither behaviour can
        // be assumed to carry across to an enum-bound member.
        actual.put("whitespace only", verdict("severity", "\"   \""));
        actual.put("a number", verdict("severity", "7"));
        actual.put("a JSON object", verdict("severity", "{\"code\":\"ERROR\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ERROR — the control", KernelLaws.ADMITTED);
        expected.put("WARNING", KernelLaws.ADMITTED);
        expected.put("INFO", KernelLaws.ADMITTED);
        // The row that refutes the ledger's reading: an ABSENT severity is ACCEPTED. `MVK_SEVERITY_MISSING`
        // fires for none of these shapes, so `Rule.Builder`'s refusal of an unset severity is broader than
        // any measured Kernel basis — the over-rejection class, invisible because the Kernel is never asked.
        expected.put("absent", KernelLaws.ADMITTED);
        // An enum binding, so every spelling outside the set — the empty string and an explicit null
        // included — is the READER's refusal rather than a verdict, the tier a checker must not fold into
        // "invalid". Note this differs from the message slot, where the empty string reads as a default.
        expected.put("null", KernelLaws.UNREADABLE);
        expected.put("empty", KernelLaws.UNREADABLE);
        expected.put("lowercase error", KernelLaws.UNREADABLE);
        expected.put("Error", KernelLaws.UNREADABLE);
        expected.put("FATAL", KernelLaws.UNREADABLE);
        expected.put("whitespace only", KernelLaws.UNREADABLE);
        expected.put("a number", KernelLaws.UNREADABLE);
        expected.put("a JSON object", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each shape of a rule's severity");
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
