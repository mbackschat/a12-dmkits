package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether ONE error message carrying two defects draws both classes or stops at the first.
 *
 * <p>The question {@link ConditionMultipleDefectLawsTest} and {@link ConditionClassWithholdingLawsTest}
 * asked of a condition, asked of the member beside it. Three checker families read one message text — its
 * characters, its parameters' addresses and their membership — and the answer is neither of the two shapes
 * already measured elsewhere: the message LAYERS them. The character check is a gate that wins wherever the
 * offending character sits, and past it every parameter defect is reported, in the order the parameters
 * appear.
 *
 * <p><b>Every pair is measured in BOTH orders</b>, which is the control the condition grid lacked: a set
 * that always puts the same defect first cannot tell "this class wins" from "the earlier defect wins", and
 * on conditions the answer turned out to be the second ([KERNEL-FINDINGS KF342]).
 *
 * <p>Own domain (a claims policy).
 */
class MessageMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .stringField("Adjuster", f -> { }))
            .build().toJson();

    /** The condition reads `Reference` and nothing else, so a parameter naming `Adjuster` is unread. */
    private static final String READS_REFERENCE = "FieldFilled(Reference)";

    private static final String TAB = String.valueOf((char) 0x09);
    private static final String BREAK = String.valueOf((char) 0x0A);
    private static final String UNREAD = "$Adjuster$";
    private static final String UNRESOLVABLE = "$NoSuchField$";

    @Test
    void aControlCharacterWithholdsEveryParameterClassWhereverItSits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("plain — the control", verdict("see it"));
        actual.put("a tab alone", verdict("see" + TAB + "it"));
        actual.put("an unread parameter alone", verdict("see " + UNREAD));
        actual.put("an unresolvable parameter alone", verdict("see " + UNRESOLVABLE));
        actual.put("an unterminated parameter alone", verdict("see $Adjuster"));
        actual.put("a tab BEFORE an unread parameter", verdict("see" + TAB + " " + UNREAD));
        actual.put("an unread parameter BEFORE a tab", verdict("see " + UNREAD + TAB));
        actual.put("a tab BEFORE an unresolvable parameter", verdict("see" + TAB + " " + UNRESOLVABLE));
        actual.put("an unresolvable parameter BEFORE a tab", verdict("see " + UNRESOLVABLE + TAB));
        actual.put("a tab BEFORE an unterminated parameter", verdict("see" + TAB + " $Adjuster"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("plain — the control", KernelLaws.ADMITTED);
        expected.put("a tab alone", "INVALID_CHARACTERS");
        expected.put("an unread parameter alone", "INVALID_FIELD");
        expected.put("an unresolvable parameter alone", "INVALID_ENTITY");
        expected.put("an unterminated parameter alone", "WRONG_NUMBER_OF_DOLLARS");
        // The rows with teeth. Each parameter class is gone beside a tab whether its own defect comes first
        // or second, so this is a GATE and not a race — the shape the condition member does not have.
        expected.put("a tab BEFORE an unread parameter", "INVALID_CHARACTERS");
        expected.put("an unread parameter BEFORE a tab", "INVALID_CHARACTERS");
        expected.put("a tab BEFORE an unresolvable parameter", "INVALID_CHARACTERS");
        expected.put("an unresolvable parameter BEFORE a tab", "INVALID_CHARACTERS");
        // …and it covers the parameter SYNTAX class too, not only the two that resolve a name.
        expected.put("a tab BEFORE an unterminated parameter", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "a control character withholds every parameter class");
    }

    /**
     * …and past that gate the message reports EVERY parameter defect, in the order they appear.
     *
     * <p>The opposite of the condition member, where the earlier defect withholds the later one. Both rows
     * are needed: one alone would leave "reports both" and "reports them in a fixed order" indistinguishable.
     */
    @Test
    void everyParameterDefectIsReportedInTheOrderTheParametersAppear() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unread parameter BEFORE an unresolvable one",
                verdict("see " + UNREAD + " and " + UNRESOLVABLE));
        actual.put("an unresolvable parameter BEFORE an unread one",
                verdict("see " + UNRESOLVABLE + " and " + UNREAD));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unread parameter BEFORE an unresolvable one", "INVALID_FIELD,INVALID_ENTITY");
        expected.put("an unresolvable parameter BEFORE an unread one", "INVALID_ENTITY,INVALID_FIELD");
        assertEquals(expected, actual, "every parameter defect is reported in the order it appears");
    }

    /**
     * A line break draws a SECOND, codeless refusal — but only where trimming cannot remove it.
     *
     * <p>Separated from the grid above because it is a property of line breaks alone, and asserting it there
     * would make every break row carry prose. The trailing and leading rows are what identify the mechanism
     * as trimming rather than interior position; the two tab rows are the negative control that keeps it from
     * being read as a rule about control characters generally.
     */
    @Test
    void anInteriorLineBreakDrawsASecondCodelessRefusal() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a break with text on both sides", verdict("see" + BREAK + "it"));
        actual.put("a TRAILING break", verdict("see" + BREAK));
        actual.put("a LEADING break", verdict(BREAK + "see"));
        actual.put("a break followed by whitespace only", verdict("see" + BREAK + "  "));
        actual.put("whitespace only before a break", verdict("  " + BREAK + "see"));
        actual.put("a carriage return with text on both sides", verdict("see" + (char) 0x0D + "it"));
        actual.put("a tab with text on both sides — the control", verdict("see" + TAB + "it"));
        actual.put("a TRAILING tab — the control", verdict("see" + TAB));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a break with text on both sides", BOTH_BREAK_REFUSALS);
        // Trimmed away before the value check sees it, so only the error-text class remains. These two rows
        // are the discriminator: an "interior position" account predicts the same thing for the two below,
        // where the break is interior to the RAW text and trailing/leading once trimmed.
        expected.put("a TRAILING break", "INVALID_LINEBREAK");
        expected.put("a LEADING break", "INVALID_LINEBREAK");
        expected.put("a break followed by whitespace only", "INVALID_LINEBREAK");
        expected.put("whitespace only before a break", "INVALID_LINEBREAK");
        expected.put("a carriage return with text on both sides", BOTH_BREAK_REFUSALS);
        // The negative control: a tab is interior in exactly the same way and draws ONE refusal, so the
        // second one is about line breaks rather than about where a control character sits.
        expected.put("a tab with text on both sides — the control", "INVALID_CHARACTERS");
        expected.put("a TRAILING tab — the control", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "an interior line break draws a second codeless refusal");
    }

    /**
     * …and WHICH text the character gate covers: this declaration's, or the one message carrying it.
     *
     * <p>A rule declares one message per locale, so the gate's granularity is a separate claim from its
     * existence — the condition member's equivalent turned out to be per DECLARATION rather than per text
     * ([KF340]), which is why this is measured here rather than carried over. It is the other answer: a
     * sibling locale's message is judged on its own, so a checker gating at declaration scope would delete a
     * finding A12 gives.
     */
    @Test
    void theCharacterGateCoversOneMessageRatherThanTheDeclaration() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two sound messages — the control", bilingual("see it", "sieh es"));
        actual.put("a tab in EN · sound DE — the control", bilingual("see" + TAB + "it", "sieh es"));
        actual.put("an unread parameter in DE · sound EN — the control",
                bilingual("see it", "sieh " + UNREAD));
        actual.put("a tab in EN · an unread parameter in DE",
                bilingual("see" + TAB + "it", "sieh " + UNREAD));
        actual.put("an unread parameter in EN · a tab in DE",
                bilingual("see " + UNREAD, "sieh" + TAB + "es"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two sound messages — the control", KernelLaws.ADMITTED);
        expected.put("a tab in EN · sound DE — the control", "INVALID_CHARACTERS");
        expected.put("an unread parameter in DE · sound EN — the control", "INVALID_FIELD");
        // Both classes, so the gate stops at the message carrying the character. The order follows the
        // locales' own, which is why the two rows are mirror images rather than one row twice.
        expected.put("a tab in EN · an unread parameter in DE", "INVALID_CHARACTERS,INVALID_FIELD");
        expected.put("an unread parameter in EN · a tab in DE", "INVALID_FIELD,INVALID_CHARACTERS");
        assertEquals(expected, actual, "the character gate covers one message rather than the declaration");
    }

    /** A12's whole-value line-break refusal carries no class, so it is identified by its text. */
    private static final String BOTH_BREAK_REFUSALS =
            "(no code: Rule with path '/Policy/ProbeRule': Only values without line breaks are allowed.),"
                    + "INVALID_LINEBREAK";

    private static final String BILINGUAL = DocumentModelDraft.named("claims-policy")
            .locale("en_US").locale("de_DE")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .stringField("Adjuster", f -> { }))
            .build().toJson();

    /** One rule carrying a message in each declared locale, so only the two texts vary. */
    private static String bilingual(String english, String german) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(BILINGUAL);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "ProbeRule");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", READS_REFERENCE).put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR");
            ArrayNode messages = body.putArray("errorMessage");
            messages.addObject().put("locale", "en_US").put("text", english);
            messages.addObject().put("locale", "de_DE").put("text", german);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static String verdict(String messageText) {
        return KernelLaws.messageVerdict(POLICY, "/Policy", "../Reference", READS_REFERENCE, "en_US",
                messageText);
    }
}
