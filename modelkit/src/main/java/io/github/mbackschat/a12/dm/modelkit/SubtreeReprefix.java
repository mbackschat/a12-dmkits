package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.core.EntityPaths;

/** Re-bases a subtree's ids and absolute condition operands when the subtree moves to a new path. */
final class SubtreeReprefix {

    private SubtreeReprefix() {
    }

    /** Re-prefix the id of {@code element} and every descendant from {@code oldPrefix} to {@code newPrefix}. */
    static void ids(JsonNode element, String oldPrefix, String newPrefix) {
        if (!(element instanceof ObjectNode obj)) {
            return;
        }
        String id = obj.path(DmKeys.ID).asText(null);
        if (id != null && EntityPaths.isInside(id, oldPrefix)) {
            obj.put(DmKeys.ID, newPrefix + id.substring(oldPrefix.length()));
        }
        if (DmNodes.is(ElementType.GROUP, obj)) {
            for (JsonNode child : obj.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                ids(child, oldPrefix, newPrefix);
            }
        } else if (DmNodes.is(ElementType.RULE, obj) && obj.path(ElementType.RULE.wireName()) instanceof ObjectNode rule) {
            // a carried rule's ABSOLUTE condition operands must re-base to the new root too (probe 01-de
            // Finding 6) — else the sub-model references the old foreign root (MVK_INVALID_ROOT_GROUP). The
            // errorEntityRelPath is relative ('../X') so it rides along unchanged.
            rebaseText(rule, "errorCondition", oldPrefix, newPrefix);
        } else if (DmNodes.is(ElementType.COMPUTATION, obj) && obj.path(ElementType.COMPUTATION.wireName()) instanceof ObjectNode comp) {
            rebaseText(comp, "commonPrecondition", oldPrefix, newPrefix);
            for (JsonNode alt : comp.path("computationAlternatives")) {
                if (alt instanceof ObjectNode altObj) {
                    rebaseText(altObj, "precondition", oldPrefix, newPrefix);
                    rebaseText(altObj, "operation", oldPrefix, newPrefix);
                }
            }
        }
    }

    /** Re-prefix every ABSOLUTE path operand in {@code holder.field} from {@code oldPrefix} to {@code newPrefix},
     *  only at a path-segment boundary (so {@code /Order/ShippingExtra} is never matched by {@code /Order/Shipping}). */
    private static void rebaseText(ObjectNode holder, String field, String oldPrefix, String newPrefix) {
        String text = holder.path(field).asText(null);
        if (text == null || !text.contains(oldPrefix)) {
            return;
        }
        String rebased = text.replaceAll(
                java.util.regex.Pattern.quote(oldPrefix) + "(?=[/)\\],\\s]|$)",
                java.util.regex.Matcher.quoteReplacement(newPrefix));
        holder.put(field, rebased);
    }
}
