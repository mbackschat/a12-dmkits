package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

import java.util.List;
import java.util.Locale;

/**
 * Tier A+ rule synthesis (SCHEMAKIT-SPEC §4, proposal §12.2): JSON Schema validation keywords that have no
 * field-config home become real a12 <em>rules</em>, authored through the typed rulekit surface (the dogfood
 * rule) after the structure is built. The importer collects {@link Need}s during its walk; this pass binds
 * the structural DM-JSON to a {@link BoundModel} and adds each synthesized rule.
 *
 * <p>Polarity: a rule FIRES on the error condition, so a constraint {@code field > N} (exclusiveMinimum) is
 * the error condition {@code [field] <= N}; the {@code filled()} guard applies the bound only when present
 * (matching JSON Schema, where a keyword constrains the value only if it is there).
 */
final class RuleSynthesis {

    private RuleSynthesis() {}

    /** One synthesized-rule request, collected during the import walk. */
    record Need(String fieldPath, Kind kind, String param) {}

    enum Kind { EXCLUSIVE_MIN, EXCLUSIVE_MAX }

    static String apply(String dmJson, List<Need> needs, TranscodeReport.Builder report) {
        if (needs.isEmpty()) {
            return dmJson;
        }
        BoundModel m = BoundModel.ofJson(dmJson);
        for (Need need : needs) {
            m = m.add(rule(m, need));
            report.mapped(need.fieldPath(), describe(need), TranscodeReport.Tier.TIER_A_PLUS);
        }
        return m.toJson();
    }

    private static Rule rule(BoundModel m, Need need) {
        String group = parentGroup(need.fieldPath());
        String field = lastSegment(need.fieldPath());
        NumberRef ref = m.num(need.fieldPath());
        String code = need.kind().name() + "_" + field.toUpperCase(Locale.ROOT);
        return switch (need.kind()) {
            case EXCLUSIVE_MIN -> m.rule(m.group(group))
                    .name(field + "ExclusiveMin").errorField(ref)
                    .when(ref.filled().and(ref.le(need.param())))
                    .code(code).severity(Severity.ERROR)
                    .message(Dsl.en(field + " must be greater than " + need.param() + ".")).build();
            case EXCLUSIVE_MAX -> m.rule(m.group(group))
                    .name(field + "ExclusiveMax").errorField(ref)
                    .when(ref.filled().and(ref.ge(need.param())))
                    .code(code).severity(Severity.ERROR)
                    .message(Dsl.en(field + " must be less than " + need.param() + ".")).build();
        };
    }

    private static String describe(Need need) {
        return "rule " + need.kind() + " " + need.param() + " (Tier A+)";
    }

    private static String parentGroup(String fieldPath) {
        return fieldPath.substring(0, fieldPath.lastIndexOf('/'));
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
