package io.github.mbackschat.a12.dm.rulekit.write;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;

/**
 * Keeps the KIND of a parsed number token through a write-back. The model codec reads a floating-point token as a
 * {@code BigDecimal}, which prints {@code 1e0} as {@code 1}, so writing the tree back would turn a token A12 refuses
 * in a boolean member into the integer it reads as set (KERNEL-FINDINGS KF569, measured 2026-10-22).
 *
 * <p>{@link #keep} marks, right after a parse, each decimal whose plain form reads as an integer; every decimal node
 * then came from a floating-point token, since integers parse to integer nodes. The marked node still reads as the
 * decimal it is, and {@link #forWrite} prints it with an exponent, {@code 1e0} as {@code 1E+0}. Jackson declares
 * {@code DecimalNode.serialize} final, hence the swap on a copy rather than an override. A decimal an edit adds is
 * unmarked and prints as before. Kit-private like {@code DmJson}, because a Jackson type may not cross a published kit boundary, so {@code modelkit.FloatTokens} is its sibling.
 */
final class FloatTokens {

    private FloatTokens() {
    }

    /** [root], each decimal of scale 0 replaced by the same decimal marked as written with a fraction or an exponent. */
    static JsonNode keep(JsonNode root) {
        return replace(root, value -> value instanceof DecimalNode && !(value instanceof Marked)
                && value.decimalValue().scale() == 0 ? new Marked(value.decimalValue()) : null);
    }

    /** A copy of [root] to serialize, each marked decimal written as a floating-point token. */
    static JsonNode forWrite(JsonNode root) {
        return replace(root.deepCopy(), value -> value instanceof Marked
                ? JsonNodeFactory.instance.rawValueNode(new RawValue(value.decimalValue().toPlainString() + "E+0"))
                : null);
    }

    /** Replaces, in place, each descendant [replacement] maps to a node; a {@code null} result descends instead. */
    private static JsonNode replace(JsonNode root, Function<JsonNode, JsonNode> replacement) {
        if (root instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode replaced = replacement.apply(object.get(name));
                if (replaced != null) {
                    object.set(name, replaced);
                } else {
                    replace(object.get(name), replacement);
                }
            }
        } else if (root instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode replaced = replacement.apply(array.get(i));
                if (replaced != null) {
                    array.set(i, replaced);
                } else {
                    replace(array.get(i), replacement);
                }
            }
        }
        return root;
    }

    /** A decimal parsed from a floating-point token that a plain print would turn into an integer. */
    private static final class Marked extends DecimalNode {

        Marked(BigDecimal value) {
            super(value);
        }
    }
}
