package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    sealed interface Need permits ExclusiveBound, DependentRequired, ConstValue, MultipleOf, DiscriminatedRequired,
            UniqueItems, ForbiddenConst, ForbiddenEnum, ForbiddenPattern, ForbiddenBound {}

    /** {@code exclusiveMinimum}/{@code exclusiveMaximum} on a number field (a12 field bounds are inclusive-only). */
    record ExclusiveBound(String fieldPath, boolean min, String bound) implements Need {}

    /** {@code dependentRequired}: if {@code trigger} is present, {@code dependent} is required (same group). */
    record DependentRequired(String groupPath, String trigger, String dependent) implements Need {}

    /** {@code const}: the field must equal a fixed value (no field-level const in a12 → a rule). */
    record ConstValue(String fieldPath, boolean numeric, String value) implements Need {}

    /** {@code multipleOf}: divisibility, expressed as a modulo rule (a12 has no modulo operator). */
    record MultipleOf(String fieldPath, String divisor) implements Need {}

    /** A conditional requiredness: {@code requiredField} is required when {@code discriminator}
     *  {@code whenEquals ? "=" : "≠"} {@code value}. Serves both the {@code oneOf}/{@code anyOf} discriminator
     *  ({@code whenEquals=true}, keyed on the variant's enum) and {@code if/then} (true) / {@code if/else}
     *  (false, fired on the negated condition — field present, value differs) — SCHEMAKIT-SPEC §4b. */
    record DiscriminatedRequired(String groupPath, String discriminator, String value, String requiredField,
            boolean whenEquals) implements Need {}

    /** {@code not: {pattern}}: the field must NOT match {@code regex} (error fires when it does — a string field). */
    record ForbiddenPattern(String fieldPath, String regex) implements Need {}

    /** {@code not: {minimum/maximum}}: the inverted numeric bound — {@code min} ⇒ value must be {@code < bound}
     *  (error when {@code >= bound}); else value must be {@code > bound} (error when {@code <= bound}). */
    record ForbiddenBound(String fieldPath, boolean min, String bound) implements Need {}

    /** {@code uniqueItems: true} on an array → a {@code RepetitionNotUnique} rule keyed on the repeatable
     *  group's row fields (resolved post-build from {@code groupPath}). */
    record UniqueItems(String groupPath) implements Need {}

    /** {@code not: {const}}: the field must NOT equal {@code value} (error fires when it does; string/number field). */
    record ForbiddenConst(String fieldPath, String value) implements Need {}

    /** {@code not: {enum}}: the field must NOT be one of {@code values} (error fires when it is — a string field). */
    record ForbiddenEnum(String fieldPath, List<String> values) implements Need {}

    static String apply(String dmJson, List<Need> needs, TranscodeReport.Builder report) {
        if (needs.isEmpty()) {
            return dmJson;
        }
        BoundModel m = BoundModel.ofJson(dmJson);
        ModelDocument doc = ModelDocument.read(dmJson);   // to resolve a repeatable group's actual row fields
        for (Need need : needs) {
            Optional<Rule> rule = ruleFor(m, doc, need, report);
            if (rule.isPresent()) {
                m = m.add(rule.get());
                report.mapped(source(need), describe(need), TranscodeReport.Tier.TIER_A_PLUS);
            }
        }
        return m.toJson();
    }

    /** Build the rule for {@code need}, or {@link Optional#empty()} when it cannot be synthesized (reported). */
    private static Optional<Rule> ruleFor(BoundModel m, ModelDocument doc, Need need, TranscodeReport.Builder report) {
        return switch (need) {
            case ExclusiveBound b -> {
                String field = lastSegment(b.fieldPath());
                NumberRef ref = m.num(b.fieldPath());
                String code = (b.min() ? "EXCLUSIVE_MIN_" : "EXCLUSIVE_MAX_") + field.toUpperCase(Locale.ROOT);
                yield Optional.of(m.rule(m.group(parentGroup(b.fieldPath())))
                        .name(field + (b.min() ? "ExclusiveMin" : "ExclusiveMax")).errorField(ref)
                        .when(b.min() ? ref.filled().and(ref.le(b.bound())) : ref.filled().and(ref.ge(b.bound())))
                        .code(code).severity(Severity.ERROR)
                        .message(Dsl.en(field + (b.min() ? " must be greater than " : " must be less than ")
                                + b.bound() + ".")).build());
            }
            case DependentRequired d -> {
                FieldRef trigger = m.field(d.groupPath() + "/" + d.trigger());
                FieldRef dependent = m.field(d.groupPath() + "/" + d.dependent());
                yield Optional.of(m.rule(m.group(d.groupPath()))
                        .name(d.dependent() + "RequiredWith" + d.trigger()).errorField(dependent)
                        .when(Dsl.fieldFilled(trigger).and(Dsl.fieldNotFilled(dependent)))
                        .code("DEPENDENT_REQUIRED_" + d.dependent().toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(d.dependent() + " is required when " + d.trigger() + " is present."))
                        .build());
            }
            case ConstValue c -> {
                String field = lastSegment(c.fieldPath());
                Condition mismatch = c.numeric()
                        ? m.num(c.fieldPath()).filled().and(m.num(c.fieldPath()).ne(c.value()))
                        : m.str(c.fieldPath()).filled().and(m.str(c.fieldPath()).ne(c.value()));
                Rule.Builder b = m.rule(m.group(parentGroup(c.fieldPath()))).name(field + "Const");
                b = c.numeric() ? b.errorField(m.num(c.fieldPath())) : b.errorField(m.str(c.fieldPath()));
                yield Optional.of(b.when(mismatch)
                        .code("CONST_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must equal " + c.value() + ".")).build());
            }
            case MultipleOf mo -> {
                String field = lastSegment(mo.fieldPath());
                NumberRef ref = m.num(mo.fieldPath());
                NumberExpr x = ref.asNumber();
                NumberExpr n = Dsl.number(mo.divisor());
                NumberExpr modulo = x.minus(Dsl.roundDown(x.dividedBy(n), 0).times(n));   // x − floor(x/N)·N
                yield Optional.of(m.rule(m.group(parentGroup(mo.fieldPath())))
                        .name(field + "MultipleOf").errorField(ref)
                        .when(ref.filled().and(modulo.ne(0)))
                        .code("MULTIPLE_OF_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must be a multiple of " + mo.divisor() + ".")).build());
            }
            case DiscriminatedRequired dr -> {
                String condPath = dr.groupPath() + "/" + dr.discriminator();
                Optional<Condition> cond = dr.whenEquals()
                        ? equalsValue(m, doc, condPath, dr.value())
                        : notEqualsValue(m, doc, condPath, dr.value());
                if (cond.isEmpty()) {
                    report.diagnostic("conditional rule for '" + dr.requiredField() + "' skipped — the condition "
                            + "field '" + dr.discriminator() + "' is neither a string nor an enum");
                    yield Optional.empty();
                }
                FieldRef required = m.field(dr.groupPath() + "/" + dr.requiredField());
                String tag = sanitize(dr.value());
                String is = dr.whenEquals() ? "Is" : "IsNot";
                String isText = dr.whenEquals() ? " is " : " is not ";
                String codePrefix = dr.whenEquals() ? "DISCRIMINATED_REQUIRED_" : "ELSE_REQUIRED_";
                yield Optional.of(m.rule(m.group(dr.groupPath()))
                        .name(dr.requiredField() + "RequiredWhen" + capitalize(dr.discriminator()) + is + tag)
                        .errorField(required)
                        .when(cond.get().and(Dsl.fieldNotFilled(required)))
                        .code(codePrefix + dr.requiredField().toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(dr.requiredField() + " is required when " + dr.discriminator()
                                + isText + dr.value() + ".")).build());
            }
            case ForbiddenPattern f -> {
                String field = lastSegment(f.fieldPath());
                if (!doc.fieldKind(f.fieldPath()).map("STRING"::equals).orElse(false)) {
                    report.diagnostic("'not pattern' on '" + field + "' skipped — only string fields supported");
                    yield Optional.empty();
                }
                StringRef r = m.str(f.fieldPath());
                yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath()))).name(field + "NotPattern").errorField(r)
                        .when(r.filled().and(r.matches(f.regex())))
                        .code("FORBIDDEN_PATTERN_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must not match the pattern " + f.regex() + ".")).build());
            }
            case ForbiddenBound f -> {
                String field = lastSegment(f.fieldPath());
                if (!doc.fieldKind(f.fieldPath()).map("NUMBER"::equals).orElse(false)) {
                    report.diagnostic("'not " + (f.min() ? "minimum" : "maximum") + "' on '" + field
                            + "' skipped — only number fields supported");
                    yield Optional.empty();
                }
                NumberRef r = m.num(f.fieldPath());
                Condition violation = f.min() ? r.ge(f.bound()) : r.le(f.bound());   // not{min:N}→err when ≥N; not{max:N}→err when ≤N
                yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath())))
                        .name(field + (f.min() ? "NotMinimum" : "NotMaximum")).errorField(r)
                        .when(r.filled().and(violation))
                        .code("FORBIDDEN_" + (f.min() ? "MIN_" : "MAX_") + field.toUpperCase(Locale.ROOT))
                        .severity(Severity.ERROR)
                        .message(Dsl.en(field + " must be " + (f.min() ? "less than " : "greater than ")
                                + f.bound() + ".")).build());
            }
            case ForbiddenConst f -> {
                String field = lastSegment(f.fieldPath());
                String code = "FORBIDDEN_" + field.toUpperCase(Locale.ROOT);
                yield switch (doc.fieldKind(f.fieldPath()).orElse("")) {
                    case "STRING" -> {
                        StringRef r = m.str(f.fieldPath());
                        yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath()))).name(field + "NotConst")
                                .errorField(r).when(r.filled().and(r.eq(f.value())))
                                .code(code).severity(Severity.ERROR)
                                .message(Dsl.en(field + " must not equal " + f.value() + ".")).build());
                    }
                    case "NUMBER" -> {
                        NumberRef r = m.num(f.fieldPath());
                        yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath()))).name(field + "NotConst")
                                .errorField(r).when(r.filled().and(r.eq(f.value())))
                                .code(code).severity(Severity.ERROR)
                                .message(Dsl.en(field + " must not equal " + f.value() + ".")).build());
                    }
                    default -> {
                        report.diagnostic("'not const' on '" + field + "' skipped — only string/number fields supported");
                        yield Optional.empty();
                    }
                };
            }
            case ForbiddenEnum f -> {
                String field = lastSegment(f.fieldPath());
                if (!doc.fieldKind(f.fieldPath()).map("STRING"::equals).orElse(false)) {
                    report.diagnostic("'not enum' on '" + field + "' skipped — only string fields supported");
                    yield Optional.empty();
                }
                StringRef r = m.str(f.fieldPath());
                String[] rest = f.values().subList(1, f.values().size()).toArray(new String[0]);
                yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath()))).name(field + "NotInList").errorField(r)
                        .when(r.filled().and(r.in(f.values().get(0), rest)))
                        .code("FORBIDDEN_VALUES_" + field.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(field + " must not be one of " + f.values() + ".")).build());
            }
            case UniqueItems u -> {
                List<FieldRef> keys = new ArrayList<>();
                for (String fieldPath : doc.fieldPaths()) {
                    if (isDirectChild(fieldPath, u.groupPath()) && doc.fieldKind(fieldPath).isPresent()) {
                        keys.add(m.field(fieldPath));   // RepetitionNotUnique keys are the row's scalar fields
                    }
                }
                if (keys.isEmpty()) {
                    report.diagnostic("uniqueItems on '" + lastSegment(u.groupPath()) + "' — no scalar row fields "
                            + "to key on; enforce row uniqueness by hand");
                    yield Optional.empty();
                }
                String row = lastSegment(u.groupPath());
                FieldRef[] rest = keys.subList(1, keys.size()).toArray(new FieldRef[0]);
                yield Optional.of(m.rule(m.group(parentGroup(u.groupPath())))
                        .name(row + "RowsUnique").errorField(keys.get(0))
                        .when(Dsl.repetitionNotUnique(keys.get(0), rest))
                        .code("UNIQUE_ITEMS_" + row.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en("Each " + row + " row must be unique.")).build());
            }
        };
    }

    /** The {@code [field] == value} condition for a string or enum field (else empty — no other kind compares). */
    private static Optional<Condition> equalsValue(BoundModel m, ModelDocument doc, String path, String value) {
        return switch (doc.fieldKind(path).orElse("")) {
            case "ENUMERATION" -> Optional.of(m.enumOf(path).is(value));
            case "STRING" -> Optional.of(m.str(path).eq(value));
            default -> Optional.empty();
        };
    }

    /** The {@code if/else} condition: field present AND {@code [field] != value} (the {@code Filled} guard handles
     *  the JSON-Schema vacuous match — {@code else} applies only when the discriminator is present and differs). */
    private static Optional<Condition> notEqualsValue(BoundModel m, ModelDocument doc, String path, String value) {
        return switch (doc.fieldKind(path).orElse("")) {
            case "ENUMERATION" -> Optional.of(m.enumOf(path).filled().and(m.enumOf(path).isNot(value)));
            case "STRING" -> Optional.of(m.str(path).filled().and(m.str(path).ne(value)));
            default -> Optional.empty();
        };
    }

    /** Is {@code path} a direct child element of {@code groupPath} (one segment below, no deeper)? */
    private static boolean isDirectChild(String path, String groupPath) {
        String prefix = groupPath + "/";
        return path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0;
    }

    private static String source(Need need) {
        return switch (need) {
            case ExclusiveBound b -> b.fieldPath();
            case DependentRequired d -> d.groupPath() + "/" + d.dependent();
            case ConstValue c -> c.fieldPath();
            case MultipleOf mo -> mo.fieldPath();
            case DiscriminatedRequired dr -> dr.groupPath() + "/" + dr.requiredField();
            case UniqueItems u -> u.groupPath();
            case ForbiddenConst f -> f.fieldPath();
            case ForbiddenEnum f -> f.fieldPath();
            case ForbiddenPattern f -> f.fieldPath();
            case ForbiddenBound f -> f.fieldPath();
        };
    }

    private static String describe(Need need) {
        return switch (need) {
            case ExclusiveBound b -> "rule " + (b.min() ? "exclusiveMinimum " : "exclusiveMaximum ") + b.bound();
            case DependentRequired d -> "rule dependentRequired(" + d.trigger() + " → " + d.dependent() + ")";
            case ConstValue c -> "rule const = " + c.value();
            case MultipleOf mo -> "rule multipleOf " + mo.divisor();
            case DiscriminatedRequired dr -> "rule " + (dr.whenEquals() ? "discriminated-required(" : "else-required(")
                    + dr.discriminator() + (dr.whenEquals() ? "=" : "≠") + dr.value() + " → " + dr.requiredField() + ")";
            case UniqueItems u -> "rule uniqueItems(" + lastSegment(u.groupPath()) + ")";
            case ForbiddenConst f -> "rule not-const(" + lastSegment(f.fieldPath()) + " ≠ " + f.value() + ")";
            case ForbiddenEnum f -> "rule not-enum(" + lastSegment(f.fieldPath()) + " ∉ " + f.values() + ")";
            case ForbiddenPattern f -> "rule not-pattern(" + lastSegment(f.fieldPath()) + " ∌ /" + f.regex() + "/)";
            case ForbiddenBound f -> "rule not-" + (f.min() ? "minimum(" : "maximum(") + lastSegment(f.fieldPath())
                    + " " + (f.min() ? "<" : ">") + " " + f.bound() + ")";
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
