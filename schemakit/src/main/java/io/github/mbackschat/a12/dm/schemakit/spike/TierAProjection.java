package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
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
 * <p>Reuse-first (§1): the stored rule is read into a typed rulekit {@link Condition} through the project's
 * mediated kernel boundary, {@code :adapter}'s {@link KernelAdapter#read} — no parser is reimplemented here.
 */
final class TierAProjection {

    private TierAProjection() {}

    static void project(String dmJson, ObjectNode schema, TranscodeReport.Builder report) {
        KernelAdapter adapter = KernelAdapter.ofJson(dmJson);
        for (Map.Entry<String, ReadResult> entry : adapter.readAll().entrySet()) {
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
        }
    }

    private record PatternHit(String fieldPath, String regex) {}

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
