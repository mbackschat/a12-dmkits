package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * What the kernel actually admits for a repeatable GROUP's {@code repeatability} — the measured basis our own
 * {@code max ≥ 2} refusal never had.
 *
 * <p><b>Why this exists.</b> {@code DocumentModelDraft.GroupBuilder.repeatable} and
 * {@code ModelDocument.addRepeatableGroup} both throw below 2, and on 2026-08-31 that refusal had no test and
 * no recorded kernel basis anywhere in this repository. A client-side refusal we invent can be wrong and is
 * invisible precisely because the kernel is never asked — the standing rule's exact shape.
 *
 * <p><b>Both gates, because they are different questions and the answer differs.</b> A model passes
 * DESERIALIZE + EXPAND first and the MVK consistency check second; a value the reader tolerates can still be
 * refused by `checkConsistency`, so a table taken at one gate cannot speak for the other. This measures both
 * from one call: an exception is a load refusal, a notification is a consistency refusal, and neither is
 * {@code ADMITTED}.
 *
 * <p><b>Why the group carrier needs its own rows.</b> {@code WorkspaceIncludeStructureKernelTest} already
 * characterizes {@code repeatability} on a MOUNTED single-root component and finds the kernel tolerant there.
 * That is a neighbouring carrier, and reusing its verdict here would be the scope transfer this project keeps
 * measuring: the reuse needs no new branch, so nothing prompts the question. a12-kernel-lean's
 * {@code SPEC-2026-08-31-07} states the opposite for the group carrier — that {@code null} and {@code 0} are
 * refused — so telling the two carriers apart is the whole point, and neither project's rows could.
 *
 * <p>Own domain (order lines).
 */
class GroupRepeatabilityDomainLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LINES = "/Order/Lines";
    private static final String ADMITTED = "ADMITTED";

    /** A declared-repeatable group, so the value under test is the one a repeatable group carries. */
    private static String baseModel() {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order.group("Lines",
                        lines -> lines.repeatable(2).field("Sku", FieldKind.STRING)))
                .build()
                .toJson();
    }

    @Test
    void theKernelAdmitsEveryNumericRepeatabilityIncludingTheOnesOurBuilderRefuses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("2 — canonical, control", verdictFor(MAPPER.getNodeFactory().numberNode(2)));
        actual.put("3", verdictFor(MAPPER.getNodeFactory().numberNode(3)));
        actual.put("1 — our builder refuses", verdictFor(MAPPER.getNodeFactory().numberNode(1)));
        actual.put("0 — our builder refuses", verdictFor(MAPPER.getNodeFactory().numberNode(0)));
        actual.put("-1 — our builder refuses", verdictFor(MAPPER.getNodeFactory().numberNode(-1)));
        actual.put("null", verdictFor(MAPPER.getNodeFactory().nullNode()));
        actual.put("absent — key removed", verdictForAbsent());
        actual.put("\"2\" — canonical value as text", verdictFor(MAPPER.getNodeFactory().textNode("2")));
        actual.put("true — a non-numeric kind", verdictFor(MAPPER.getNodeFactory().booleanNode(true)));

        Map<String, String> expected = new LinkedHashMap<>();
        for (String admitted : List.of("2 — canonical, control", "3", "1 — our builder refuses",
                "absent — key removed", "\"2\" — canonical value as text")) {
            expected.put(admitted, ADMITTED);
        }
        expected.put("0 — our builder refuses", invalidMaxima("Only values greater than 0 are allowed."));
        expected.put("-1 — our builder refuses", invalidMaxima("Only values without a sign are allowed."));
        expected.put("null", invalidMaxima("Only values greater than 0 are allowed."));
        expected.put("true — a non-numeric kind", "RuntimeException");

        assertEquals(expected, actual,
                "the kernel's own domain for a repeatable group's repeatability — what any client-side refusal "
                        + "has to be no broader than");
    }

    /**
     * The consistency gate's refusal, verbatim. It reports the invalid maxima **per element** — the group and
     * every field beneath it — and then one message naming the actual domain violation, which is where the two
     * distinct wordings live: a zero is "greater than 0", a negative is "without a sign".
     */
    private static String invalidMaxima(String domainMessage) {
        return "ERROR: Group with path '" + LINES + "': The maximal indices for '" + LINES
                + "' are invalid. [MVK_MAX_VALUES_INVALID], "
                + "ERROR: Field with path '" + LINES + "/Sku': The maximal indices for '" + LINES
                + "/Sku' are invalid. [MVK_MAX_VALUES_INVALID], "
                + "ERROR: Group with path '" + LINES + "': " + domainMessage;
    }

    /**
     * {@link #ADMITTED}, the exception class from the load gate, or the consistency notifications. Both gates
     * ride one call: {@code consistencyMessages} deserializes, expands, then runs {@code checkConsistency}.
     */
    private static String verdictFor(JsonNode repeatability) {
        return verdict(model -> groupBody(model).set("repeatability", repeatability));
    }

    private static String verdictForAbsent() {
        return verdict(model -> groupBody(model).remove("repeatability"));
    }

    private static String verdict(java.util.function.Consumer<JsonNode> mutation) {
        JsonNode model = tree(baseModel());
        mutation.accept(model);
        List<String> notifications;
        try {
            notifications = RuntimeLaws.consistencyMessages(model.toString());
        } catch (RuntimeException e) {
            return "RuntimeException";
        }
        return notifications.isEmpty() ? ADMITTED : String.join(", ", notifications);
    }

    /** The {@code Group} body of {@link #LINES} — located by its declared id, so a shape change fails loudly. */
    private static ObjectNode groupBody(JsonNode model) {
        JsonNode node = find(model);
        if (node == null) {
            throw new IllegalStateException("no group node with id " + LINES + " in the emitted model");
        }
        return (ObjectNode) node.path("Group");
    }

    private static JsonNode find(JsonNode node) {
        if (node.isObject() && LINES.equals(node.path("id").asText(null))) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode hit = find(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
