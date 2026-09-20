package io.github.mbackschat.a12.dm.cli.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The single source for a rule's {@code explain} {@code data} payload — shared by {@code rule explain} and the
 * legacy {@code explain --rule} so the two can't drift (a parity test asserts they're byte-identical). It
 * assembles the structural {@link io.github.mbackschat.a12.dm.rulekit.context.RuleExplanation} (or the
 * tolerant Opaque fallback) and adds the two rule-level facts an agent needs to explain the rule <b>to a
 * user</b> without inverting its meaning:
 *
 * <ul>
 *   <li><b>{@code polarity}</b> — the constant A12 truth that a rule's condition is TRUE on a <em>violation</em>
 *       (it models the state to reject, not the requirement). Without this an agent reading the condition can
 *       describe the rule backwards.</li>
 *   <li><b>{@code messages}</b> — the rule's stored error text (locale → text, raw), the modeler's own NL and
 *       the positive statement of the requirement; emitted via the shared {@link RuleMessages} (the same shape
 *       {@code rule read} surfaces, G10).</li>
 * </ul>
 */
public final class RuleExplainData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleExplainData() {
    }

    /**
     * Build the {@code data} payload for explaining the rule at {@code rulePath} (assumed already validated to
     * exist). Covers all three read outcomes — Converted (modeled), Opaque (unmodeled construct) and Refused
     * (a gate rejects it on a measured kernel basis); all carry {@code polarity} + {@code messages}.
     */
    public static ObjectNode build(KernelAdapter adapter, BoundModel model, String rulePath) {
        ReadResult read = adapter.read(rulePath);
        ObjectNode data;
        // Exhaustive over the sealed read: the cast this replaced meant "not Converted is Opaque", which a
        // third arm turns into a ClassCastException on a rule the kernel accepts. The refused arm adds
        // `refusedCode` to the same unexplained shape, strictly additively (DEV-API-GAPS DG1001).
        switch (read) {
            case ReadResult.Converted converted ->
                data = (ObjectNode) parse(new ModelContext(model).explain(converted.condition()).toJson());
            case ReadResult.Opaque opaque -> {
                data = MAPPER.createObjectNode();
                data.put("rule", rulePath);
                data.put("explained", false);
                data.put("reason", opaque.reason());
                data.put("canonicalText", opaque.canonicalText());
            }
            case ReadResult.Refused refused -> {
                data = MAPPER.createObjectNode();
                data.put("rule", rulePath);
                data.put("explained", false);
                data.put("reason", refused.reason());
                data.put("canonicalText", refused.canonicalText());
                data.put("refusedCode", refused.code().name());
            }
        }
        putPolarity(data);
        RuleMessages.putInto(data, adapter.ruleMessages(rulePath));
        return data;
    }

    /**
     * True iff the payload is an unexplained fallback rather than a structural explanation. It answers the
     * shape question — {@code explained:false} — and deliberately does NOT say WHICH fallback set it. Named
     * for the key it reads rather than for one of the states behind it, because the read outcome is sealed
     * and gained an arm; a predicate named after one arm would have quietly started lying about the other.
     */
    public static boolean isUnexplained(ObjectNode data) {
        return data.path("explained").isBoolean() && !data.path("explained").asBoolean();
    }

    /** Every A12 rule condition is TRUE on a violation — state it, so the explanation isn't inverted. */
    private static void putPolarity(ObjectNode data) {
        data.putObject("polarity")
                .put("firesOn", "violation")
                .put("note", "An A12 rule condition is TRUE when the rule is VIOLATED — it models the state to "
                        + "reject, not the requirement. Explain it as the forbidden case; the rule's message "
                        + "states the requirement positively.");
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse the rule explanation JSON", e);
        }
    }
}
