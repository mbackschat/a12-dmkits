package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

import java.util.List;
import java.util.Locale;

/**
 * Tier A+ rule synthesis (SCHEMAKIT-SPEC §4, proposal §12.2): JSON Schema validation keywords with no
 * field-config home become real a12 <em>rules</em>, authored through the typed rulekit surface (the dogfood
 * rule) after the structure is built. The importer collects {@link Need}s during its walk; this pass binds
 * the structural DM-JSON to a {@link BoundModel} and adds each synthesized rule.
 *
 * <p>Polarity: a rule FIRES on the error condition, so a constraint {@code field > N} (exclusiveMinimum) is
 * the error condition {@code [field] <= N}; a {@code filled()} guard applies the constraint only when the
 * value is present (matching JSON Schema, where a keyword constrains a value only if it is there).
 */
final class RuleSynthesis {

    private RuleSynthesis() {}

    /** A synthesized-rule request, collected during the import walk. Sealed so the synthesizer is exhaustive. */
    sealed interface Need permits ExclusiveBound, DependentRequired {}

    /** {@code exclusiveMinimum}/{@code exclusiveMaximum} on a number field (a12 field bounds are inclusive-only). */
    record ExclusiveBound(String fieldPath, boolean min, String bound) implements Need {}

    /** {@code dependentRequired}: if {@code trigger} is present, {@code dependent} is required (same group). */
    record DependentRequired(String groupPath, String trigger, String dependent) implements Need {}

    static String apply(String dmJson, List<Need> needs, TranscodeReport.Builder report) {
        if (needs.isEmpty()) {
            return dmJson;
        }
        BoundModel m = BoundModel.ofJson(dmJson);
        for (Need need : needs) {
            m = m.add(rule(m, need));
            report.mapped(source(need), describe(need), TranscodeReport.Tier.TIER_A_PLUS);
        }
        return m.toJson();
    }

    private static Rule rule(BoundModel m, Need need) {
        return switch (need) {
            case ExclusiveBound b -> {
                String field = lastSegment(b.fieldPath());
                NumberRef ref = m.num(b.fieldPath());
                String code = (b.min() ? "EXCLUSIVE_MIN_" : "EXCLUSIVE_MAX_") + field.toUpperCase(Locale.ROOT);
                yield m.rule(m.group(parentGroup(b.fieldPath())))
                        .name(field + (b.min() ? "ExclusiveMin" : "ExclusiveMax")).errorField(ref)
                        .when(b.min() ? ref.filled().and(ref.le(b.bound())) : ref.filled().and(ref.ge(b.bound())))
                        .code(code).severity(Severity.ERROR)
                        .message(Dsl.en(field + (b.min() ? " must be greater than " : " must be less than ")
                                + b.bound() + ".")).build();
            }
            case DependentRequired d -> {
                FieldRef trigger = m.field(d.groupPath() + "/" + d.trigger());
                FieldRef dependent = m.field(d.groupPath() + "/" + d.dependent());
                yield m.rule(m.group(d.groupPath()))
                        .name(d.dependent() + "RequiredWith" + d.trigger()).errorField(dependent)
                        .when(Dsl.fieldFilled(trigger).and(Dsl.fieldNotFilled(dependent)))
                        .code("DEPENDENT_REQUIRED_" + d.dependent().toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(d.dependent() + " is required when " + d.trigger() + " is present."))
                        .build();
            }
        };
    }

    private static String source(Need need) {
        return switch (need) {
            case ExclusiveBound b -> b.fieldPath();
            case DependentRequired d -> d.groupPath() + "/" + d.dependent();
        };
    }

    private static String describe(Need need) {
        return switch (need) {
            case ExclusiveBound b -> "rule " + (b.min() ? "exclusiveMinimum " : "exclusiveMaximum ") + b.bound();
            case DependentRequired d -> "rule dependentRequired(" + d.trigger() + " → " + d.dependent() + ")";
        };
    }

    private static String parentGroup(String fieldPath) {
        return fieldPath.substring(0, fieldPath.lastIndexOf('/'));
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
