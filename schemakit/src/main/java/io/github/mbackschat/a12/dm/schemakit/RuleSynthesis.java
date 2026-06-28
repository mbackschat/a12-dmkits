package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
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
    sealed interface Need permits ExclusiveBound, DependentRequired, ConstValue, MultipleOf, DiscriminatedRequired {}

    /** {@code exclusiveMinimum}/{@code exclusiveMaximum} on a number field (a12 field bounds are inclusive-only). */
    record ExclusiveBound(String fieldPath, boolean min, String bound) implements Need {}

    /** {@code dependentRequired}: if {@code trigger} is present, {@code dependent} is required (same group). */
    record DependentRequired(String groupPath, String trigger, String dependent) implements Need {}

    /** {@code const}: the field must equal a fixed value (no field-level const in a12 → a rule). */
    record ConstValue(String fieldPath, boolean numeric, String value) implements Need {}

    /** {@code multipleOf}: divisibility, expressed as a modulo rule (a12 has no modulo operator). */
    record MultipleOf(String fieldPath, String divisor) implements Need {}

    /** A {@code oneOf}/{@code anyOf} discriminator: {@code requiredField} is required when the discriminator
     *  enum equals {@code value} (the structural enforcement of a variant's {@code required}, keyed on the
     *  discriminator — SCHEMAKIT-SPEC §4b). */
    record DiscriminatedRequired(String groupPath, String discriminator, String value, String requiredField)
            implements Need {}

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
            case ConstValue c -> {
                String field = lastSegment(c.fieldPath());
                Condition mismatch = c.numeric()
                        ? m.num(c.fieldPath()).filled().and(m.num(c.fieldPath()).ne(c.value()))
                        : m.str(c.fieldPath()).filled().and(m.str(c.fieldPath()).ne(c.value()));
                Rule.Builder b = m.rule(m.group(parentGroup(c.fieldPath()))).name(field + "Const");
                b = c.numeric() ? b.errorField(m.num(c.fieldPath())) : b.errorField(m.str(c.fieldPath()));
                yield b.when(mismatch)
                        .code("CONST_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must equal " + c.value() + ".")).build();
            }
            case MultipleOf mo -> {
                String field = lastSegment(mo.fieldPath());
                NumberRef ref = m.num(mo.fieldPath());
                NumberExpr x = ref.asNumber();
                NumberExpr n = Dsl.number(mo.divisor());
                NumberExpr modulo = x.minus(Dsl.roundDown(x.dividedBy(n), 0).times(n));   // x − floor(x/N)·N
                yield m.rule(m.group(parentGroup(mo.fieldPath())))
                        .name(field + "MultipleOf").errorField(ref)
                        .when(ref.filled().and(modulo.ne(0)))
                        .code("MULTIPLE_OF_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must be a multiple of " + mo.divisor() + ".")).build();
            }
            case DiscriminatedRequired dr -> {
                EnumRef discriminator = m.enumOf(dr.groupPath() + "/" + dr.discriminator());
                FieldRef required = m.field(dr.groupPath() + "/" + dr.requiredField());
                String tag = sanitize(dr.value());
                yield m.rule(m.group(dr.groupPath()))
                        .name(dr.requiredField() + "RequiredWhen" + capitalize(dr.discriminator()) + "Is" + tag)
                        .errorField(required)
                        .when(discriminator.is(dr.value()).and(Dsl.fieldNotFilled(required)))
                        .code("DISCRIMINATED_REQUIRED_" + dr.requiredField().toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(dr.requiredField() + " is required when " + dr.discriminator()
                                + " is " + dr.value() + ".")).build();
            }
        };
    }

    private static String source(Need need) {
        return switch (need) {
            case ExclusiveBound b -> b.fieldPath();
            case DependentRequired d -> d.groupPath() + "/" + d.dependent();
            case ConstValue c -> c.fieldPath();
            case MultipleOf mo -> mo.fieldPath();
            case DiscriminatedRequired dr -> dr.groupPath() + "/" + dr.requiredField();
        };
    }

    private static String describe(Need need) {
        return switch (need) {
            case ExclusiveBound b -> "rule " + (b.min() ? "exclusiveMinimum " : "exclusiveMaximum ") + b.bound();
            case DependentRequired d -> "rule dependentRequired(" + d.trigger() + " → " + d.dependent() + ")";
            case ConstValue c -> "rule const = " + c.value();
            case MultipleOf mo -> "rule multipleOf " + mo.divisor();
            case DiscriminatedRequired dr -> "rule discriminated-required(" + dr.discriminator() + "="
                    + dr.value() + " → " + dr.requiredField() + ")";
        };
    }

    private static String parentGroup(String fieldPath) {
        return fieldPath.substring(0, fieldPath.lastIndexOf('/'));
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Strip a discriminator value to an identifier-safe token for a unique rule name/code suffix. */
    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
