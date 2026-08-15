package io.github.mbackschat.a12.dm.schemakit;

import java.math.BigDecimal;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;

import java.util.Map;
import java.util.Optional;

/**
 * Tier-A projection (SCHEMAKIT-SPEC §4; JSON-SCHEMA-PROPOSAL §6) — the small subset of a12 rules that have
 * a native JSON Schema home are *also* emitted as native keywords, **for foreign readers**. It is additive:
 * the full rule is still carried via {@code x-a12-rule} (Tier B) for a12-fidelity, so the a12 round-trip is
 * driven by carriage and the native keyword is the foreign-facing projection. This demonstrates the
 * project-or-carry split with one representative shape — a string pattern rule
 * ({@code FieldFilled(F) And [F] PatternViolated "regex"}) → {@code properties[…F…].pattern = "regex"}.
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
            findPatternConstraint(converted.condition()).ifPresent(hit -> {
                ObjectNode field = navigateToField(schema, hit.fieldPath());
                if (field == null) {
                    report.diagnostic("rule '" + entry.getKey() + "' would project a pattern onto '"
                            + hit.fieldPath() + "', but that field is not in the exported structure");
                    return;
                }
                field.put("pattern", hit.regex());
                report.mapped(entry.getKey(), "properties/" + hit.fieldPath() + ".pattern",
                        TranscodeReport.Tier.TIER_A);
            });
            findMinItemsConstraint(converted.condition()).ifPresent(hit -> {
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
                array.put("minItems", hit.bound().intValueExact());
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
                && agg.fields().isEmpty() && agg.groups().size() == 1
                && c.right() instanceof NumberLiteral n) {
            // keep the raw bound; the integer-ness guard + skip-and-report live at the projection site
            return Optional.of(new MinItemsHit(agg.groups().get(0).path().value(), n.value()));
        }
        if (condition instanceof LogicCondition logic && logic.op() == LogicCondition.Op.AND) {
            Optional<MinItemsHit> left = findMinItemsConstraint(logic.left());
            return left.isPresent() ? left : findMinItemsConstraint(logic.right());
        }
        return Optional.empty();
    }

    /** A field must MATCH a regex iff a rule ERRORS on {@code PatternViolated} — find that anywhere in an AND chain. */
    private static Optional<PatternHit> findPatternConstraint(Condition condition) {
        if (condition instanceof PatternCondition p && !p.matched()) {
            return Optional.of(new PatternHit(p.field().path(), p.regex()));
        }
        if (condition instanceof LogicCondition logic && logic.op() == LogicCondition.Op.AND) {
            Optional<PatternHit> left = findPatternConstraint(logic.left());
            return left.isPresent() ? left : findPatternConstraint(logic.right());
        }
        return Optional.empty();
    }

    /** Walk the schema's nested {@code properties}/{@code items} to the field at a (relative or /Root/…) path. */
    private static ObjectNode navigateToField(ObjectNode schema, String fieldPath) {
        boolean absolute = fieldPath.startsWith("/");
        String[] segments = (absolute ? fieldPath.substring(1) : fieldPath).split("/");
        int start = absolute ? 1 : 0;   // an absolute path leads with the root group name — skip it
        JsonNode cursor = schema;
        for (int i = start; i < segments.length - 1; i++) {
            JsonNode child = cursor.path("properties").path(segments[i]);
            cursor = "array".equals(child.path("type").asText()) ? child.path("items") : child;
        }
        JsonNode field = cursor.path("properties").path(segments[segments.length - 1]);
        return field instanceof ObjectNode object ? object : null;
    }
}
