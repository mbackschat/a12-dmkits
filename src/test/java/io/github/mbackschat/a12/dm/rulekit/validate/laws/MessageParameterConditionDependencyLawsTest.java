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
 * What A12 says about a message PARAMETER when the rule's own condition is invalid.
 *
 * <p>`FehlertextErrorType` carries a class for exactly this — {@code INVALID_CONDITION}, "a text with a
 * parameter can only be checked on rules with valid conditions" — which means the parameter's own verdict is
 * REPLACED rather than merely accompanied. A checker judging the two members independently would report a
 * parameter class A12 withheld, on every model that has a broken condition and a message beside it.
 *
 * <p>Own domain (a claims policy). The model is built whole rather than through
 * {@link KernelLaws#messageVerdict} because that helper asserts the condition is admitted first, which is
 * precisely the premise this file removes.
 */
class MessageParameterConditionDependencyLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2)))
            .build().toJson();

    @Test
    void whatAParameterDrawsWhenTheConditionBesideItIsInvalid() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid condition, a parameter it does not read — the control",
                verdict("FieldFilled(Reference)", "../Reference", "see $Premium$"));
        actual.put("a condition naming nothing, a parameter it does not read",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see $Premium$"));
        actual.put("a condition naming nothing, a plain text — the control",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see the reference"));
        actual.put("a condition that does not PARSE, a parameter beside it",
                verdict("FieldFilled(", "../Reference", "see $Premium$"));
        // The sweep the parameter rows demand: does a broken condition withhold the message's CHARACTER
        // verdict too, or only the one that needs the condition's references? The two checks differ in
        // whether they depend on the condition at all, so the answer cannot be carried over.
        actual.put("a valid condition, a control character in the text — the control",
                verdict("FieldFilled(Reference)", "../Reference", "see" + (char) 0x09 + "the reference"));
        actual.put("a condition naming nothing, a control character in the text",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see" + (char) 0x09 + "the reference"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid condition, a parameter it does not read — the control", "INVALID_FIELD");
        // The parameter's own class is WITHHELD, not merely accompanied: the condition's class is the whole
        // verdict, identical to the plain-text row beside it. So a checker judging the two members
        // independently reports a class A12 gave no sign of, on every model whose condition is half-typed —
        // which is the ordinary state of a model being edited, and exactly when a checker is asked.
        expected.put("a condition naming nothing, a parameter it does not read", "MVK_INVALID_ENTITY");
        expected.put("a condition naming nothing, a plain text — the control", "MVK_INVALID_ENTITY");
        // …and the same for a condition that does not parse, under that failure's own class.
        expected.put("a condition that does not PARSE, a parameter beside it", "MVK_INCOMPLETE_INPUT");
        // The character verdict is withheld by the SAME broken condition, so this is A12 declining to
        // examine the message member at all rather than a rule about parameters — which means every family
        // reading that member answers to it, not only the one that needs the condition's references.
        expected.put("a valid condition, a control character in the text — the control", "INVALID_CHARACTERS");
        expected.put("a condition naming nothing, a control character in the text", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "what a parameter draws when the condition beside it is invalid");
    }

    /**
     * …and the same question on a COMPUTATION, whose unsound member is an operation rather than a condition.
     *
     * <p>The gate this file justifies reads a computation's members too, so those rows are a claim the rule
     * rows cannot support: a computation has no condition, and nothing says A12 stops examining its message
     * when its operation fails. Measured rather than carried across the carrier boundary.
     */
    @Test
    void whetherAComputationsMessageIsWithheldByItsOwnBrokenMember() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid operation, a control character in the text — the control",
                computing("1", "see" + (char) 0x09 + "the total"));
        actual.put("an operation naming nothing, a control character in the text",
                computing("[NoSuchField] + 1", "see" + (char) 0x09 + "the total"));
        actual.put("an operation naming nothing, a plain text — the control",
                computing("[NoSuchField] + 1", "see the total"));
        actual.put("a valid operation, a parameter it does not read — the control",
                computing("1", "see $Reference$"));
        actual.put("an operation naming nothing, a parameter beside it",
                computing("[NoSuchField] + 1", "see $Reference$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid operation, a control character in the text — the control", "INVALID_CHARACTERS");
        // The same withholding, on a carrier with no condition at all: the broken member is an OPERATION,
        // and both error-text verdicts give way to its class exactly as a rule's do. So the gate is "the
        // declaration's own DSL is sound", not anything about conditions.
        expected.put("an operation naming nothing, a control character in the text", "MVK_INVALID_ENTITY");
        expected.put("an operation naming nothing, a plain text — the control", "MVK_INVALID_ENTITY");
        expected.put("a valid operation, a parameter it does not read — the control", "INVALID_FIELD");
        expected.put("an operation naming nothing, a parameter beside it", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a computation's message is withheld by its own broken member");
    }

    /** The drafted policy with one computation into `Premium`, carrying [operation] and [text]. */
    private static String computing(String operation, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "PremiumCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Premium");
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** The drafted policy with one rule injected, so the model is whole in every way but the condition. */
    private static String verdict(String condition, String errorField, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", errorField)
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
