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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's {@code errorCodesToSuppress} — a stored LIST that A12 can only ever honour one entry of.
 *
 * <p><b>It is not a set the Kernel inspects; it is TEXT the Kernel renders and then re-parses.</b>
 * {@code ComputationAlternativesJoiner.createPreamble} does
 * {@code String.format("@SuppressWarning(%s)\n", String.join(",", errorCodesToSuppress))}, so the member is
 * comma-joined into one inline pragma on the generated rule condition. Every row below follows from that and
 * from nothing else, which is why reading the member as a collection gets it wrong.
 *
 * <p><b>A second entry is a PARSE error whatever it spells — the pragma takes one code.</b> The control
 * carries the supported code TWICE and still fails with {@code MVK_UNEXPECTED_TOKEN}, so the failure cannot
 * be attributed to an invalid member; the comma is the defect. An empty entry renders
 * {@code @SuppressWarning()} and a numeric entry renders {@code @SuppressWarning(7)}, both the same parse
 * failure. So a plural-looking member is single-valued in practice, and a checker treating it as a set would
 * admit lists A12 refuses.
 *
 * <p><b>Our builder's allowlist is CLEARED, which is the opposite of what this test was written to catch.</b>
 * {@code Computation.Builder.errorCodesToSuppress} throws for any code outside
 * {@code MVK_INVALID_COMPARE_DEC_PLACES}, and a client-side refusal must never be broader than its measured
 * basis — so the question was whether A12 admits the others. It does not: an unsupported code draws
 * {@code MVK_INVALID_SUPPRESSED_WARNING}, matching the Kernel grammar's own statement that only
 * {@code MVK_INVALID_COMPARE_DEC_PLACES} is suppressible. The refusal has its basis and is recorded as
 * audited rather than left in the unaudited bucket.
 *
 * <p>Note the diagnostic ECHOES the offending code, so {@code KernelLaws.verdictOf} — which lifts every
 * {@code MVK_*} token out of the message — reports two tokens for those rows; the verdict is the second.
 *
 * <p>Own domain (the shared subscription fixture); the operation is a scale-2 constant into the scale-2
 * {@code EffectiveFee}, so no assignment-scale law fires and every row is about the member under test.
 */
class ComputationSuppressionLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Subscription/Billing";

    private static final String OPERATION = "42.00";

    /** The one code our builder admits — the Kernel's static assignment-scale gate (IF92). */
    private static final String SUPPORTED = "MVK_INVALID_COMPARE_DEC_PLACES";

    @Test
    void whatA12DoesWithEachSuppressionListShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", verdict(null));
        actual.put("empty list", verdict("[]"));
        actual.put("the supported code", verdict("[\"" + SUPPORTED + "\"]"));
        actual.put("an unsupported but real MVK code", verdict("[\"MVK_ERROR_CODE_MISSING\"]"));
        actual.put("a code that does not exist at all", verdict("[\"MVK_TOTALLY_BOGUS\"]"));
        actual.put("the supported code TWICE — the control that isolates the comma",
                verdict("[\"" + SUPPORTED + "\",\"" + SUPPORTED + "\"]"));
        actual.put("the supported code beside a bogus one", verdict("[\"" + SUPPORTED + "\",\"MVK_TOTALLY_BOGUS\"]"));
        actual.put("an empty string entry", verdict("[\"\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("empty list", KernelLaws.ADMITTED);
        expected.put("the supported code", KernelLaws.ADMITTED);
        // The rows that decide whether our builder's single-member allowlist has a Kernel basis: it does.
        // Both rows read as the AUTHOR's own code followed by the verdict until the harness stopped scanning
        // the message text for `MVK_` tokens — the diagnostic echoes the offending code back, so the second
        // row's expectation once stated `MVK_TOTALLY_BOGUS`, a code A12 does not have, as A12's answer.
        expected.put("an unsupported but real MVK code", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("a code that does not exist at all", "MVK_INVALID_SUPPRESSED_WARNING");
        // The law the LIST shape hides: two entries are comma-joined into one `@SuppressWarning(a,b)` pragma
        // and the grammar takes a single code, so a second entry is a PARSE error whatever it spells. This
        // control carries the supported code twice, so nothing about validity can explain it.
        expected.put("the supported code TWICE — the control that isolates the comma", "MVK_UNEXPECTED_TOKEN");
        expected.put("the supported code beside a bogus one", "MVK_UNEXPECTED_TOKEN");
        // …and an empty entry renders `@SuppressWarning()`, which is the same parse failure.
        expected.put("an empty string entry", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what A12 does with each shape of errorCodesToSuppress");
    }

    /** The shapes that are not a list of strings — the tier question, separate for the usual reason. */
    @Test
    void aSuppressionListThatIsNotAListOfStrings() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an explicit null", verdict("null"));
        actual.put("a bare string", verdict("\"" + SUPPORTED + "\""));
        actual.put("a number element", verdict("[7]"));
        actual.put("an object", verdict("{\"code\":\"" + SUPPORTED + "\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        // An explicit null REFUSES the reader here, where the rounding pair took it as absence (KF297): the
        // member is bound `@NonNull List<String>` rather than through an Optional accessor, so the tier is a
        // property of the binding and not of DM-JSON. Measured rather than carried across.
        expected.put("an explicit null", KernelLaws.UNREADABLE);
        expected.put("a bare string", KernelLaws.UNREADABLE);
        // A number IS coerced into the list, then rendered into the pragma as `7`, which is not an identifier.
        expected.put("a number element", "MVK_UNEXPECTED_TOKEN");
        expected.put("an object", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "a errorCodesToSuppress that is not a list of strings");
    }

    /** What A12 says about the probe model with `errorCodesToSuppress` set to [rawJson], or absent when null. */
    private static String verdict(String rawJson) {
        return KernelLaws.verdictOf(probe(rawJson));
    }

    private static String probe(String rawJson) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("SuppressProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, OPERATION)))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode computation = computationBody(root);
            if (rawJson != null) {
                computation.set("errorCodesToSuppress", MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    private static ObjectNode computationBody(ObjectNode root) {
        for (var top : root.path("content").path("modelRoot").path("rootGroups")) {
            ObjectNode found = findProbe(top);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("the probe computation is no longer where this test writes it");
    }

    private static ObjectNode findProbe(JsonNode node) {
        if ("SuppressProbe".equals(node.path("name").asText()) && node.has("Computation")) {
            return (ObjectNode) node.path("Computation");
        }
        for (var child : node.path("Group").path("elements")) {
            ObjectNode found = findProbe(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
