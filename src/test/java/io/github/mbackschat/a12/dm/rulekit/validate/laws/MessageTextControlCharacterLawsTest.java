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
 * Which CONTROL characters an error message may carry, and under which of A12's two classes it may not.
 *
 * <p>{@link RuleMessageAdmissionGateLawsTest#theTemplateRepertoireIsTheBmpRatherThanAscii()} measures the
 * repertoire — which code points are in the charset at all — and answers a different question: every row
 * there is printable, and the refusal it found carries no class at all. A12 has two further classes for a
 * text whose repertoire is fine ({@code INVALID_LINEBREAK} and {@code INVALID_CHARACTERS}, both
 * {@code FehlertextErrorType}), and neither is reachable from a printable character.
 *
 * <p>The pair matters to a portable checker because it is the cheapest check this surface has — no model
 * resolution at all, only the text — and because two classes over one input kind means a reader answering
 * both with one code names a verdict A12 did not give.
 *
 * <p>Own domain (a claims policy).
 */
class MessageTextControlCharacterLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Policy";
    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String READS_REFERENCE = "FieldFilled(Reference)";

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Total", f -> f.fractionDigits(0, 2)))
            .build().toJson();

    @Test
    void whichControlCharactersAMessageMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("printable text — the control", verdict("see the reference"));
        actual.put("a line feed", around(0x0A));
        actual.put("a carriage return", around(0x0D));
        actual.put("a CRLF pair", verdict("see" + (char) 0x0D + (char) 0x0A + "the reference"));
        actual.put("a tab", around(0x09));
        actual.put("a NUL", around(0x00));
        actual.put("a BEL", around(0x07));
        actual.put("a DEL", around(0x7F));
        actual.put("a non-breaking space", around(0xA0));
        // The boundary a checker has to implement: where C0 ends, and whether the C1 block is in or out.
        actual.put("the last C0 control", around(0x1F));
        actual.put("a plain space", around(0x20));
        actual.put("the first C1 control", around(0x80));
        actual.put("the last C1 control", around(0x9F));
        // Both kinds in one text: whether a reader may report each independently, or whether one gate runs
        // first and hides the other — the difference decides what a checker is allowed to emit.
        actual.put("a line break AND a control character",
                verdict("see" + (char) 0x0A + "the" + (char) 0x09 + "reference"));
        // …and the same pair in the OTHER ORDER, which is what separates "the line-break gate runs first"
        // from "the first offending character decides". Both rows read alike when the break comes first.
        actual.put("a control character BEFORE a line break",
                verdict("see" + (char) 0x09 + "the" + (char) 0x0A + "reference"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("printable text — the control", ADMITTED);
        expected.put("a line feed",
                "(no code: Rule with path '/Policy/ProbeRule': Only values without line breaks are allowed.),INVALID_LINEBREAK");
        expected.put("a carriage return",
                "(no code: Rule with path '/Policy/ProbeRule': Only values without line breaks are allowed.),INVALID_LINEBREAK");
        expected.put("a CRLF pair",
                "(no code: Rule with path '/Policy/ProbeRule': Only values without line breaks are allowed.),INVALID_LINEBREAK");
        expected.put("a tab", "INVALID_CHARACTERS");
        expected.put("a NUL", "INVALID_CHARACTERS");
        expected.put("a BEL", "INVALID_CHARACTERS");
        expected.put("a DEL", "INVALID_CHARACTERS");
        expected.put("a non-breaking space", ADMITTED);
        expected.put("the last C0 control", "INVALID_CHARACTERS");
        expected.put("a plain space", ADMITTED);
        expected.put("the first C1 control", "INVALID_CHARACTERS");
        expected.put("the last C1 control", "INVALID_CHARACTERS");
        // ONLY the line-break class: the tab beside it draws nothing, so the two gates are ORDERED rather
        // than independent, and a reader reporting both names a verdict A12 did not give.
        expected.put("a line break AND a control character",
                "(no code: Rule with path '/Policy/ProbeRule': Only values without line breaks are allowed.),INVALID_LINEBREAK");
        // Still the line-break class, so the gate is keyed on the KIND present rather than on which
        // character comes first — the distinction a reader taking "the first offending character" gets
        // wrong, and one no row with the break in front can see.
        expected.put("a control character BEFORE a line break",
                "(no code: Rule with path \'/Policy/ProbeRule\': Only values without line breaks are allowed.),INVALID_LINEBREAK");
        assertEquals(expected, actual, "which control characters a message may carry");
    }

    /**
     * …and whether a COMPUTATION's message meets the same gate, which decides the check's carrier set.
     *
     * <p>A family reporting on every authored message would over-reject if only rules were gated, and
     * under-report if only rules were checked. The membership gate reaches computations
     * ({@link MessageParameterMembershipLawsTest}); that says nothing about this one, which is lexical and
     * could as easily sit on the rule member alone.
     */
    @Test
    void whetherAComputationsMessageMeetsTheSameGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("printable — the control", computing("see the reference"));
        actual.put("a line feed", computing("see" + (char) 0x0A + "the reference"));
        actual.put("a tab", computing("see" + (char) 0x09 + "the reference"));
        actual.put("a paragraph separator", computing("see" + (char) 0x2029 + "the reference"));
        actual.put("a zero width no-break space", computing("see" + (char) 0xFEFF + "the reference"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("printable — the control", ADMITTED);
        // Gated the same way, and WITHOUT the codeless runtime diagnostic the rule rows carry beside the
        // class — that one belongs to the rule member rather than to the text.
        expected.put("a line feed", "INVALID_LINEBREAK");
        expected.put("a tab", "INVALID_CHARACTERS");
        // The wider set of ErrorTextCharacterGateLawsTest, measured on this carrier rather than carried over.
        expected.put("a paragraph separator", "INVALID_LINEBREAK");
        expected.put("a zero width no-break space", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "whether a computation's message meets the same gate");
    }

    /** The policy with one computation into `Total` carrying [text], injected through the parsed tree. */
    private static String computing(String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cmsg").put("name", "TotalCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Total");
            body.putArray("computationAlternatives").addObject().put("operation", "1");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** The text with one code point in the middle, written numerically so the source stays printable. */
    private static String around(int codePoint) {
        return verdict("see" + (char) codePoint + "the reference");
    }

    private static String verdict(String text) {
        return KernelLaws.messageVerdict(POLICY, GROUP, "../Reference", READS_REFERENCE, "en_US", text);
    }
}
