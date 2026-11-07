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
 * A computation's {@code errorCode}, new in document-model format 29.5.0 (A12K-4095): the code of the validation rule A12
 * generates from the computation, which falls back to the computation's name when the member is absent
 * ({@code ValidationModelConverter.createRuleFromComputation}, kernel 31.3.1). Each row is one value of the member
 * on an otherwise admitted computation, so a refusal can only be the member's own.
 *
 * <p>The fallback applies only to an absent or {@code null} member: an empty or blank code is kept and then judged
 * as the generated rule's code, so it draws {@code MVK_ERROR_CODE_MISSING} rather than taking the name. A hyphen is
 * refused without a class, and a number is coerced to its text.
 *
 * <p>Own domain (an insurance premium quote).
 */
class ComputationErrorCodeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUOTE = DocumentModelDraft.named("premium-quote").locale("en_US")
            .rootGroup("Quote", quote -> quote
                    .numberField("Rate", n -> n.fractionDigits(2))
                    .numberField("Premium", n -> n.fractionDigits(2)))
            .build().toJson();

    @Test
    void whatA12DoesWithEachErrorCode() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", withErrorCode(null));
        actual.put("a code", withErrorCode("\"PREMIUM_MISMATCH\""));
        actual.put("a code with a blank inside", withErrorCode("\"PREMIUM MISMATCH\""));
        actual.put("a code with a hyphen", withErrorCode("\"PREMIUM-MISMATCH\""));
        actual.put("a code with an umlaut", withErrorCode("\"PRÄMIE\""));
        actual.put("the computation's own name", withErrorCode("\"PremiumCalc\""));
        actual.put("empty", withErrorCode("\"\""));
        actual.put("blank", withErrorCode("\"  \""));
        actual.put("an explicit null", withErrorCode("null"));
        actual.put("a number", withErrorCode("7"));
        actual.put("a list", withErrorCode("[\"PREMIUM_MISMATCH\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("a code", KernelLaws.ADMITTED);
        expected.put("a code with a blank inside", KernelLaws.ADMITTED);
        expected.put("a code with a hyphen", KernelLaws.REFUSED_CODELESS);
        expected.put("a code with an umlaut", KernelLaws.ADMITTED);
        expected.put("the computation's own name", KernelLaws.ADMITTED);
        expected.put("empty", "MVK_ERROR_CODE_MISSING");
        expected.put("blank", "MVK_ERROR_CODE_MISSING");
        expected.put("an explicit null", KernelLaws.ADMITTED);
        expected.put("a number", KernelLaws.ADMITTED);
        expected.put("a list", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "a computation's errorCode");
    }

    /** The quote with one admitted computation, its {@code errorCode} set to [rawJson] or absent when null. */
    private static String withErrorCode(String rawJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(QUOTE);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cpremium").put("name", "PremiumCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Premium");
            if (rawJson != null) {
                body.set("errorCode", MAPPER.readTree(rawJson));
            }
            body.putArray("computationAlternatives").addObject().put("operation", "[Rate]");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the premium as quoted");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
