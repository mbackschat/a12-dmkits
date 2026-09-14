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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * KERNEL-FINDINGS §11 — how many divisions and powers one arithmetic expression may carry, and what an
 * exponent has to be.
 *
 * <p>{@code CheckOperationImpl.check} runs three gates over every operation context: the type walk (which
 * carries the exponent's own demand), then at most one division per multiplicative chain, then no power
 * directly under a power. The typed builder cannot author any of the refused shapes — {@code DslRenderer}
 * brackets a left power child and a left division under division — so every row here is a shape that reaches
 * a checker only as stored DM-JSON.
 *
 * <p>Each row wraps its operation in {@code RoundDown(…, 2)} into the scale-2 {@code EffectiveFee}. Without
 * it a power or a division leaves the operation's scale undeterminable and {@code
 * MVK_INVALID_COMPARE_DEC_PLACES} answers every row, which measures the target's declaration rather than the
 * operator's arity.
 */
class CalculationOperatorArityLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INVALID_EXPONENT = "MVK_INVALID_NUMBER_FOR_EXP";

    private static final String TOO_MANY_POW = "MVK_TOO_MANY_POW_FOR_CALC";

    private static final String TOO_MANY_DIV = "MVK_TOO_MANY_DIV_FOR_CALC";

    @Test
    void howManyDivisionsAndPowersOneExpressionMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a product — the control", verdict("RoundDown([BaseFee] * 2, 2)"));
        actual.put("a whole-number literal exponent", verdict("RoundDown([BaseFee] ^ 2, 2)"));
        actual.put("a DECIMAL literal exponent", verdict("RoundDown([BaseFee] ^ 2.5, 2)"));
        actual.put("a FIELD exponent declaring 2 fractional digits",
                verdict("RoundDown([BaseFee] ^ [BaseFee], 2)"));
        actual.put("a nested power", verdict("RoundDown([BaseFee] ^ 2 ^ 2, 2)"));
        actual.put("a bracketed nested power", verdict("RoundDown({[BaseFee] ^ 2} ^ 2, 2)"));
        actual.put("a nested power whose own exponent is decimal",
                verdict("RoundDown([BaseFee] ^ 2 ^ 2.5, 2)"));
        actual.put("two divisions in one chain", verdict("RoundDown([BaseFee] / 2 / 4, 2)"));
        actual.put("two divisions separated by a plus",
                verdict("RoundDown([BaseFee] / 2 + [BaseFee] / 4, 2)"));
        actual.put("two divisions, the inner bracketed",
                verdict("RoundDown([BaseFee] / {[BaseFee] / 4}, 2)"));
        actual.put("a division then a multiplication", verdict("RoundDown([BaseFee] / 2 * 4, 2)"));
        actual.put("a nested power beside two divisions",
                verdict("RoundDown([BaseFee] ^ 2 ^ 2 + [BaseFee] / 2 / 4, 2)"));
        actual.put("the same two, the divisions written FIRST",
                verdict("RoundDown([BaseFee] / 2 / 4 + [BaseFee] ^ 2 ^ 2, 2)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a product — the control", KernelLaws.ADMITTED);
        expected.put("a whole-number literal exponent", KernelLaws.ADMITTED);
        expected.put("a DECIMAL literal exponent", INVALID_EXPONENT);
        // The exponent is judged by its declared SCALE, not by the value it could take, so a field the
        // author will only ever fill with whole numbers is refused all the same.
        expected.put("a FIELD exponent declaring 2 fractional digits", INVALID_EXPONENT);
        expected.put("a nested power", TOO_MANY_POW);
        // Brackets are what the typed renderer emits for exactly this shape, and they admit it — so the
        // gate is about a power DIRECTLY under a power rather than about two powers in one expression.
        expected.put("a bracketed nested power", KernelLaws.ADMITTED);
        // Inside one operation context the type walk runs before the arity gates, so the exponent's own
        // demand pre-empts the nesting the same expression also violates.
        expected.put("a nested power whose own exponent is decimal", INVALID_EXPONENT);
        expected.put("two divisions in one chain", TOO_MANY_DIV);
        // A plus starts a fresh count, which is the kernel's own documented example.
        expected.put("two divisions separated by a plus", KernelLaws.ADMITTED);
        // …and so does a bracket, deliberately: the kernel's comment says the nested case COULD be caught
        // and is accepted because the old parser accepted it.
        expected.put("two divisions, the inner bracketed", KernelLaws.ADMITTED);
        expected.put("a division then a multiplication", KernelLaws.ADMITTED);
        // Source order puts the division gate before the power gate, and the verdict follows the TEXT
        // instead: `check` runs per operation context as the expression is parsed, so whichever violation
        // is written first is the one reported. Measured in both orders, because one order alone reads as
        // "the power gate wins".
        expected.put("a nested power beside two divisions", TOO_MANY_POW);
        expected.put("the same two, the divisions written FIRST", TOO_MANY_DIV);
        assertEquals(expected, actual, "how many divisions and powers one expression may carry");
    }

    /** The RULE carrier: the same gates, on an operation inside a condition rather than a computation. */
    @Test
    void theSameGatesAnswerInsideARuleCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a product — the control", ruleVerdict("[BaseFee] * 2 > 0"));
        actual.put("two divisions in one chain", ruleVerdict("[BaseFee] / 2 / 4 > 0"));
        actual.put("a nested power", ruleVerdict("[BaseFee] ^ 2 ^ 2 > 0"));
        actual.put("a DECIMAL literal exponent", ruleVerdict("[BaseFee] ^ 2.5 > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a product — the control", KernelLaws.ADMITTED);
        expected.put("two divisions in one chain", TOO_MANY_DIV);
        expected.put("a nested power", TOO_MANY_POW);
        expected.put("a DECIMAL literal exponent", INVALID_EXPONENT);
        assertEquals(expected, actual, "the same gates answer inside a rule condition");
    }

    private static String ruleVerdict(String condition) {
        return KernelLaws.verdictModel(Fixtures.subscriptionJson(), "/Subscription/Billing",
                "../BaseFee", condition);
    }

    private static String verdict(String operation) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath("/Subscription/Billing").name("ArityProbe")
                .computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, "42.00")))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode body = computationBody(root);
            ((ObjectNode) body.get("computationAlternatives").get(0)).put("operation", operation);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output must parse", unreadable);
        }
    }

    private static ObjectNode computationBody(JsonNode node) {
        if (node.isObject() && "Computation".equals(node.path("type").asText())) {
            return (ObjectNode) node.get("Computation");
        }
        for (JsonNode child : node) {
            ObjectNode found = child.isContainerNode() ? computationBody(child) : null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
