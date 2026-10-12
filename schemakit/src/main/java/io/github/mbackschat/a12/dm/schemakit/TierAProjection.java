package io.github.mbackschat.a12.dm.schemakit;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackends;
import io.github.mbackschat.a12.dm.rulekit.dsl.CompareOp;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Tier-A projection (SCHEMAKIT-SPEC §4; JSON-SCHEMA-PROPOSAL §6) — the small subset of a12 rules that have
 * a native JSON Schema home are *also* emitted as native keywords, **for foreign readers**. It is additive:
 * the full rule is still carried via {@code x-a12-rule} (Tier B) for a12-fidelity, so the a12 round-trip is
 * driven by carriage and the native keyword is the foreign-facing projection. This demonstrates the
 * project-or-carry split for whole unguarded root-array lower bounds. Rule patterns remain carried because
 * a single native pattern does not preserve their whole predicate and empty-string applicability.
 *
 * <p>Reuse-first (§1): the stored rule is read into a typed rulekit {@link Condition} through the
 * {@link ConditionReadBack} port (kernel-decoupling stage 3) — the kernel backend provides the parse; no
 * parser is reimplemented here, and schemakit names no kernel type.
 */
final class TierAProjection {

    private TierAProjection() {}

    static void project(String dmJson, ObjectNode schema, TranscodeReport.Builder report) {
        Map<String, ReadResult> read = ReadBackends.require().readAll(dmJson, ReferenceResolvers.selfContained());
        for (Map.Entry<String, ReadResult> entry : read.entrySet()) {
            if (!(entry.getValue() instanceof ReadResult.Converted converted)) {
                continue;   // an Opaque (unmodeled) rule stays carriage-only — never silently lost
            }
            findPatternConstraint(converted.condition()).ifPresent(hit -> report.diagnostic(
                    "rule '" + entry.getKey() + "' has a pattern that remains carried — the single native pattern "
                            + "does not preserve the whole predicate and present empty-string applicability"));
            findMinItemsConstraint(converted.condition()).ifPresent(hit -> {
                if (!(converted.condition() instanceof ComparisonCondition)) {
                    report.diagnostic("rule '" + entry.getKey() + "' remains carried — minItems cannot represent its whole predicate");
                    return;
                }
                if (hit.bound().stripTrailingZeros().scale() > 0) {
                    // a JSON Schema minItems is an integer row count; a non-integer bound has no minItems form —
                    // skip the projection (never crash on intValueExact) and report it, the rule stays carried
                    report.diagnostic("rule '" + entry.getKey() + "' bounds a group row-count by a non-integer ("
                            + hit.bound().toPlainString() + ") — not projected as JSON Schema minItems; it remains "
                            + "carried as an a12 rule");
                    return;
                }
                ObjectNode array = navigateToField(schema, hit.groupPath());   // a property node — here the array
                if (array == null || !"array".equals(array.path("type").asText())) {
                    report.diagnostic("rule '" + entry.getKey() + "' would project minItems onto '" + hit.groupPath()
                            + "', but that array is not in the exported structure");
                    return;
                }
                String groupPath = hit.groupPath();
                int slash = groupPath.lastIndexOf('/');
                String parentPath = groupPath.substring(0, slash);
                String ownerPath = entry.getKey().substring(0, entry.getKey().lastIndexOf('/'));
                JsonNode root = slash > 0 ? Carriage.rootCarriers(schema).get(parentPath.substring(1)) : null;
                if (!parentPath.equals(ownerPath) || root == null || !"object".equals(root.path("type").asText())) {
                    report.diagnostic("rule '" + entry.getKey() + "' remains carried — minItems needs a fixed declaring root array property");
                    return;
                }
                BigInteger minimum = hit.bound().toBigIntegerExact();
                if (minimum.signum() <= 0) return;
                array.put("minItems", minimum.max(array.path("minItems").bigIntegerValue()));
                TreeSet<String> names = new TreeSet<>();
                root.path("required").forEach(n -> names.add(n.asText()));
                names.add(groupPath.substring(slash + 1));
                var required = ((ObjectNode) root).putArray("required");
                names.forEach(required::add);
                report.mapped(entry.getKey(), "properties/" + hit.groupPath() + ".minItems",
                        TranscodeReport.Tier.TIER_A);
            });
        }
    }

    private record PatternHit(String fieldPath, String regex) {}

    private record MinItemsHit(String groupPath, BigDecimal bound) {}

    /** A group must have {@code ≥ N} rows iff a rule ERRORS on {@code NumberOfFilledGroups(G*) < N} — the
     *  synthesized minItems shape (a single starred repeatable group, unguarded; SCHEMAKIT-SPEC §4f). */
    private static Optional<MinItemsHit> findMinItemsConstraint(Condition condition) {
        if (condition instanceof ComparisonCondition c && c.operator() == CompareOp.LT
                && c.left() instanceof CountAggregate agg
                && agg.op() == CountAggregate.CountOp.FILLED_GROUPS
                && agg.operands().size() == 1
                && agg.operands().getFirst() instanceof GroupListRef scope
                // G* alone: a star on an ancestor counts G across that ancestor's rows, which is no bound on G
                && scope.flattened().equals(List.of(scope.group()))
                && c.right() instanceof NumberLiteral n) {
            // keep the raw bound; the integer-ness guard + skip-and-report live at the projection site
            return Optional.of(new MinItemsHit(scope.path(), n.value()));
        }
        if (condition instanceof LogicCondition logic && logic.op() == LogicCondition.Op.AND) {
            Optional<MinItemsHit> left = findMinItemsConstraint(logic.left());
            return left.isPresent() ? left : findMinItemsConstraint(logic.right());
        }
        return Optional.empty();
    }

    /** Locate the formerly projected candidate so the unsupported native projection is explicitly reported. */
    private static Optional<PatternHit> findPatternConstraint(Condition condition) {
        // SCHEMAKIT-SPEC §4f: a row selected by `For` cannot become a constraint on every array item.
        if (condition instanceof PatternCondition p && !p.matched() && !p.regex().isEmpty() && p.field() instanceof FieldRef field) {
            return Optional.of(new PatternHit(field.path().value(), p.regex()));
        }
        if (condition instanceof LogicCondition logic && logic.op() == LogicCondition.Op.AND) {
            Optional<PatternHit> left = findPatternConstraint(logic.left());
            return left.isPresent() ? left : findPatternConstraint(logic.right());
        }
        return Optional.empty();
    }

    /** Root annotations settle the absolute path before nested {@code properties}/{@code items} traversal. */
    private static ObjectNode navigateToField(ObjectNode schema, String fieldPath) {
        boolean absolute = fieldPath.startsWith("/");
        String[] segments = (absolute ? fieldPath.substring(1) : fieldPath).split("/");
        int start = absolute ? 1 : 0;
        JsonNode cursor = schema;
        if (absolute) {
            Map<String, JsonNode> roots = Carriage.rootCarriers(schema);
            if (!roots.isEmpty()) {
                JsonNode carrier = roots.get(segments[0]);
                if (carrier == null) return null;
                if (segments.length == 1) return carrier instanceof ObjectNode object ? object : null;
                cursor = Carriage.groupObject(carrier);
            }
        }
        for (int i = start; i < segments.length - 1; i++) {
            JsonNode child = cursor.path("properties").path(segments[i]);
            cursor = "array".equals(child.path("type").asText()) ? child.path("items") : child;
        }
        JsonNode field = cursor.path("properties").path(segments[segments.length - 1]);
        return field instanceof ObjectNode object ? object : null;
    }
}
