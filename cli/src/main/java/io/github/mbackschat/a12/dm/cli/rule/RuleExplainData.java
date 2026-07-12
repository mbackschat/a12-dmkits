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
     * exist). Covers both the Converted (modeled) and Opaque (unmodeled construct) cases; both carry
     * {@code polarity} + {@code messages}.
     */
    public static ObjectNode build(KernelAdapter adapter, BoundModel model, String rulePath) {
        ReadResult read = adapter.read(rulePath);
        ObjectNode data;
        if (read instanceof ReadResult.Converted converted) {
            data = (ObjectNode) parse(new ModelContext(model).explain(converted.condition()).toJson());
        } else {
            ReadResult.Opaque opaque = (ReadResult.Opaque) read;
            data = MAPPER.createObjectNode();
            data.put("rule", rulePath);
            data.put("explained", false);
            data.put("reason", opaque.reason());
            data.put("canonicalText", opaque.canonicalText());
        }
        putPolarity(data);
        RuleMessages.putInto(data, adapter.ruleMessages(rulePath));
        return data;
    }

    /** True iff the payload is the Opaque fallback (the converter didn't model the construct). */
    public static boolean isOpaque(ObjectNode data) {
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
