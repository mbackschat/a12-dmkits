package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.core.ModelResolver;
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
import java.util.stream.Collectors;

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
            MultiConditionalRequired, ThenConstrained, ElseConstrained, ContainsCount, MinItemsCount, UniqueItems,
            ForbiddenConst, ForbiddenEnum, ForbiddenPattern, ForbiddenBound, ForbiddenLength, ForbiddenAllPresent {}

    /** One {@code if}-condition conjunct: a field pinned to a value ({@code field == value}). */
    record FieldValue(String field, String value) {}

    /** A multi-property {@code if}: {@code requiredField} is required when ALL {@code conditions} hold
     *  ({@code if {a=X, b=Y} then required:[…]} → the conjunction; the else of a conjunction is a disjunction,
     *  so multi-property {@code else} stays reported — SCHEMAKIT-SPEC §4). */
    record MultiConditionalRequired(String groupPath, List<FieldValue> conditions, String requiredField)
            implements Need {}

    /** A {@code then} VALUE-constraint: when ALL {@code conditions} hold, {@code targetField} must satisfy
     *  {@code constraint} ({@code if {f=v} then {properties:{g:{minimum:N}}}} → error when {@code f=v And g<N}).
     *  SCHEMAKIT-SPEC §4f. */
    record ThenConstrained(String groupPath, List<FieldValue> conditions, String targetField, ValueConstraint constraint)
            implements Need {}

    /** An {@code else} VALUE-constraint: when the {@code if} did NOT hold, {@code targetField} must satisfy
     *  {@code constraint}. The negation of the conjunction is a disjunction — an OR of the per-condition negations
     *  ({@code Filled(f) And f≠value}); a single condition is the one-element case — SCHEMAKIT-SPEC §4f. */
    record ElseConstrained(String groupPath, List<FieldValue> conditions, String targetField, ValueConstraint constraint)
            implements Need {}

    /** A simple value constraint on a field (the tractable {@code then}-keyword set), with the requirement text. */
    enum ConstraintKind { CONST, MINIMUM, MAXIMUM, EXCLUSIVE_MIN, EXCLUSIVE_MAX, ENUM, PATTERN }

    record ValueConstraint(ConstraintKind kind, String value, List<String> values) {
        static ValueConstraint scalar(ConstraintKind kind, String value) {
            return new ValueConstraint(kind, value, List.of());
        }
        static ValueConstraint ofEnum(List<String> values) {
            return new ValueConstraint(ConstraintKind.ENUM, null, List.copyOf(values));
        }
        String requirement(String field) {
            return field + switch (kind) {
                case CONST -> " must equal " + value;
                case MINIMUM -> " must be at least " + value;
                case MAXIMUM -> " must be at most " + value;
                case EXCLUSIVE_MIN -> " must be greater than " + value;
                case EXCLUSIVE_MAX -> " must be less than " + value;
                case ENUM -> " must be one of " + values;
                case PATTERN -> " must match the pattern " + value;
            };
        }
    }

    /** {@code exclusiveMinimum}/{@code exclusiveMaximum} on a number field (a12 field bounds are inclusive-only). */
    record ExclusiveBound(String fieldPath, boolean min, String bound) implements Need {}

    /** {@code dependentRequired}: if {@code trigger} is present, {@code dependent} is required (same group). */
    record DependentRequired(String groupPath, String trigger, String dependent) implements Need {}

    /** {@code const}: the field must equal a fixed value (no field-level const in a12 → a rule). The field kind is
     *  resolved at synthesis time (like {@code not{const}} — {@link ForbiddenConst}), so a temporal/custom-typed
     *  {@code const} is skipped-and-reported rather than forced through a string handle (SCHEMAKIT-SPEC §4a). */
    record ConstValue(String fieldPath, String value) implements Need {}

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

    /** {@code not: {minLength/maxLength}}: the inverted string-length bound — {@code min} ⇒ length must be
     *  {@code < bound} (error when {@code length >= bound}); else length must be {@code > bound} (error when
     *  {@code length <= bound}). A string field. */
    record ForbiddenLength(String fieldPath, boolean min, int bound) implements Need {}

    /** Object-level {@code not: {required:[…]}}: the {@code fields} must NOT all be present together — error fires
     *  when they all are ({@code AllFieldsFilled}, or {@code FieldFilled} for one). SCHEMAKIT-SPEC §4f. */
    record ForbiddenAllPresent(String groupPath, List<String> fields) implements Need {}

    /** {@code uniqueItems: true} on an array → a {@code RepetitionNotUnique} rule keyed on the repeatable
     *  group's row fields (resolved post-build from {@code groupPath}). */
    record UniqueItems(String groupPath) implements Need {}

    /** {@code contains}/{@code minContains}/{@code maxContains}: the repeatable group must have {@code ≥ bound}
     *  ({@code min}) or {@code ≤ bound} (not {@code min}) rows matching ALL {@code clauses} — a {@code Having}-
     *  filtered {@code NumberOfFilledFields} count over the conjunction of positive match conditions
     *  (equality, range, enum, or pattern per clause — SCHEMAKIT-SPEC §4f). */
    record ContainsCount(String groupPath, List<Clause> clauses, boolean min, int bound) implements Need {}

    /** One {@code contains} predicate clause: a row field must satisfy a value constraint (positive match). */
    record Clause(String field, ValueConstraint constraint) {}

    /** {@code minItems}: the repeatable group must have at least {@code min} rows — a {@code NumberOfFilledGroups(G*)
     *  < min} rule (KERNEL-FINDINGS §9 "group cardinality"). Anchored IN-GROUP + unguarded (SCHEMAKIT-SPEC §4f):
     *  it fires at 0…{@code min}-1 rows unconditionally, where a guarded outside anchoring would be suppressed
     *  when its guard field is empty (a minItems false-negative). {@code maxItems} stays structural (repeatability). */
    record MinItemsCount(String groupPath, int min) implements Need {}

    /** {@code not: {const}}: the field must NOT equal {@code value} (error fires when it does; string/number field). */
    record ForbiddenConst(String fieldPath, String value) implements Need {}

    /** {@code not: {enum}}: the field must NOT be one of {@code values} (error fires when it is — a string field). */
    record ForbiddenEnum(String fieldPath, List<String> values) implements Need {}

    /** The synthesized model plus the gate that cleared it — empty when nothing was synthesized, so nothing gated. */
    record Applied(String dmJson, Optional<io.github.mbackschat.a12.dm.core.Verification> consistencyGate) {}

    static String apply(String dmJson, List<Need> needs, TranscodeReport.Builder report,
                        ModelResolver resolver) {
        return applied(dmJson, needs, report, resolver).dmJson();
    }

    static Applied applied(String dmJson, List<Need> needs, TranscodeReport.Builder report,
                        ModelResolver resolver) {
        if (needs.isEmpty()) {
            // Nothing to synthesize, so no bind and no gate — the structural transcode alone is ungated.
            return new Applied(dmJson, Optional.empty());
        }
        // a bundle component may mount a sibling (an include) — then loading it to author the typed rule needs a
        // resolver for that sibling; a single self-contained model passes resolver == null.
        BoundModel m = resolver == null ? BoundModel.ofJson(dmJson) : BoundModel.ofJson(dmJson, resolver);
        ModelDocument doc = ModelDocument.read(dmJson);   // to resolve a repeatable group's actual row fields
        for (Need need : needs) {
            Optional<Rule> rule = ruleFor(m, doc, need, report);
            if (rule.isPresent()) {
                m = m.add(rule.get());
                report.mapped(source(need), describe(need), TranscodeReport.Tier.TIER_A_PLUS);
            }
        }
        return new Applied(m.toJson(), m.consistencyGate());
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
                String code = "CONST_" + field.toUpperCase(Locale.ROOT);
                String message = field + " must equal " + c.value() + ".";
                yield switch (doc.fieldKind(c.fieldPath()).orElse("")) {
                    case "STRING" -> {
                        StringRef r = m.str(c.fieldPath());
                        yield Optional.of(m.rule(m.group(parentGroup(c.fieldPath()))).name(field + "Const")
                                .errorField(r).when(r.filled().and(r.ne(c.value())))
                                .code(code).severity(Severity.ERROR).message(Dsl.en(message)).build());
                    }
                    case "NUMBER" -> {
                        NumberRef r = m.num(c.fieldPath());
                        yield Optional.of(m.rule(m.group(parentGroup(c.fieldPath()))).name(field + "Const")
                                .errorField(r).when(r.filled().and(r.ne(c.value())))
                                .code(code).severity(Severity.ERROR).message(Dsl.en(message)).build());
                    }
                    default -> {
                        report.diagnostic("'const' on '" + field + "' skipped — only string/number fields supported");
                        yield Optional.empty();
                    }
                };
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
            case MultiConditionalRequired mc -> {
                List<Condition> conds = new ArrayList<>();
                for (FieldValue fv : mc.conditions()) {
                    Optional<Condition> eq = equalsValue(m, doc, mc.groupPath() + "/" + fv.field(), fv.value());
                    if (eq.isEmpty()) {
                        report.diagnostic("multi-condition rule for '" + mc.requiredField() + "' skipped — condition "
                                + "field '" + fv.field() + "' is neither a string nor an enum");
                        yield Optional.empty();
                    }
                    conds.add(eq.get());
                }
                Condition all = conds.get(0);
                for (int i = 1; i < conds.size(); i++) {
                    all = all.and(conds.get(i));
                }
                FieldRef required = m.field(mc.groupPath() + "/" + mc.requiredField());
                String tag = mc.conditions().stream()
                        .map(fv -> capitalize(fv.field()) + sanitize(fv.value())).collect(Collectors.joining());
                String when = mc.conditions().stream()
                        .map(fv -> fv.field() + " is " + fv.value()).collect(Collectors.joining(" and "));
                yield Optional.of(m.rule(m.group(mc.groupPath()))
                        .name(mc.requiredField() + "RequiredWhen" + tag).errorField(required)
                        .when(all.and(Dsl.fieldNotFilled(required)))
                        .code("CONDITIONAL_REQUIRED_" + mc.requiredField().toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(mc.requiredField() + " is required when " + when + ".")).build());
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
            case ThenConstrained tc -> {
                List<Condition> conds = new ArrayList<>();
                for (FieldValue fv : tc.conditions()) {
                    Optional<Condition> eq = equalsValue(m, doc, tc.groupPath() + "/" + fv.field(), fv.value());
                    if (eq.isEmpty()) {
                        report.diagnostic("then-constraint on '" + tc.targetField() + "' skipped — condition field '"
                                + fv.field() + "' is neither a string nor an enum");
                        yield Optional.empty();
                    }
                    conds.add(eq.get());
                }
                String targetPath = tc.groupPath() + "/" + tc.targetField();
                Optional<Condition> violation = violationCondition(m, doc, targetPath, tc.constraint(), report);
                if (violation.isEmpty()) {
                    yield Optional.empty();   // violationCondition reported the kind mismatch
                }
                Condition all = conds.get(0);
                for (int i = 1; i < conds.size(); i++) {
                    all = all.and(conds.get(i));
                }
                FieldRef errorField = m.field(targetPath);
                String tag = tc.conditions().stream()
                        .map(fv -> capitalize(fv.field()) + sanitize(fv.value())).collect(Collectors.joining());
                String when = tc.conditions().stream()
                        .map(fv -> fv.field() + " is " + fv.value()).collect(Collectors.joining(" and "));
                yield Optional.of(m.rule(m.group(tc.groupPath()))
                        .name(tc.targetField() + "ConstrainedWhen" + tag).errorField(errorField)
                        .when(all.and(violation.get()))
                        .code("THEN_CONSTRAINT_" + tc.targetField().toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(tc.constraint().requirement(tc.targetField()) + " when " + when + ".")).build());
            }
            case ForbiddenLength f -> {
                String field = lastSegment(f.fieldPath());
                if (!doc.fieldKind(f.fieldPath()).map("STRING"::equals).orElse(false)) {
                    report.diagnostic("'not " + (f.min() ? "minLength" : "maxLength") + "' on '" + field
                            + "' skipped — only string fields supported");
                    yield Optional.empty();
                }
                StringRef r = m.str(f.fieldPath());
                Condition violation = f.min() ? r.length().ge(f.bound()) : r.length().le(f.bound());
                yield Optional.of(m.rule(m.group(parentGroup(f.fieldPath())))
                        .name(field + (f.min() ? "NotMinLength" : "NotMaxLength")).errorField(r)
                        .when(r.filled().and(violation))
                        .code("FORBIDDEN_" + (f.min() ? "MINLEN_" : "MAXLEN_") + field.toUpperCase(Locale.ROOT))
                        .severity(Severity.ERROR)
                        .message(Dsl.en(field + " length must be " + (f.min() ? "less than " : "greater than ")
                                + f.bound() + ".")).build());
            }
            case ForbiddenAllPresent f -> {
                List<FieldRef> refs = new ArrayList<>();
                for (String fieldName : f.fields()) {
                    String fieldPath = f.groupPath() + "/" + fieldName;
                    if (doc.fieldKind(fieldPath).isEmpty()) {
                        report.diagnostic("not{required} on '" + lastSegment(f.groupPath()) + "' skipped — '"
                                + fieldName + "' is not a scalar field (only field presence is enforceable here)");
                        yield Optional.empty();
                    }
                    refs.add(m.field(fieldPath));
                }
                Condition allPresent = refs.size() == 1
                        ? Dsl.fieldFilled(refs.get(0))
                        : Dsl.allFieldsFilled(List.copyOf(refs));
                String tag = f.fields().stream().map(RuleSynthesis::capitalize).collect(Collectors.joining());
                yield Optional.of(m.rule(m.group(f.groupPath())).name("Not" + tag + "Together").errorField(refs.get(0))
                        .when(allPresent)
                        .code("FORBIDDEN_TOGETHER_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en("These must not all be present together: " + f.fields() + ".")).build());
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
            case ElseConstrained ec -> {
                List<Condition> negs = new ArrayList<>();
                for (FieldValue fv : ec.conditions()) {
                    Optional<Condition> n = notEqualsValue(m, doc, ec.groupPath() + "/" + fv.field(), fv.value());
                    if (n.isEmpty()) {
                        report.diagnostic("else-constraint on '" + ec.targetField() + "' skipped — condition field '"
                                + fv.field() + "' is neither a string nor an enum");
                        yield Optional.empty();
                    }
                    negs.add(n.get());
                }
                Condition negated = negs.get(0);
                for (int i = 1; i < negs.size(); i++) {
                    negated = negated.or(negs.get(i));   // NOT(a=X And b=Y) = (a≠X) Or (b≠Y)
                }
                String targetPath = ec.groupPath() + "/" + ec.targetField();
                Optional<Condition> violation = violationCondition(m, doc, targetPath, ec.constraint(), report);
                if (violation.isEmpty()) {
                    yield Optional.empty();
                }
                FieldRef errorField = m.field(targetPath);
                String tag = ec.conditions().stream()
                        .map(fv -> capitalize(fv.field()) + sanitize(fv.value())).collect(Collectors.joining());
                String when = ec.conditions().stream()
                        .map(fv -> fv.field() + " is not " + fv.value()).collect(Collectors.joining(" or "));
                yield Optional.of(m.rule(m.group(ec.groupPath()))
                        .name(ec.targetField() + "ConstrainedWhenNot" + tag).errorField(errorField)
                        .when(negated.and(violation.get()))
                        .code("ELSE_CONSTRAINT_" + ec.targetField().toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en(ec.constraint().requirement(ec.targetField()) + " when " + when + ".")).build());
            }
            case ContainsCount cc -> {
                List<Condition> matches = new ArrayList<>();
                for (Clause cl : cc.clauses()) {
                    Optional<Condition> mc = matchCondition(m, doc, cc.groupPath() + "/" + cl.field(), cl.constraint(), report);
                    if (mc.isEmpty()) {
                        report.diagnostic("contains on '" + lastSegment(cc.groupPath()) + "' skipped — clause field '"
                                + cl.field() + "' cannot carry the predicate (kind mismatch, or wasn't imported)");
                        yield Optional.empty();
                    }
                    matches.add(mc.get());
                }
                Condition having = matches.get(0);
                for (int i = 1; i < matches.size(); i++) {
                    having = having.and(matches.get(i));
                }
                String row = lastSegment(cc.groupPath());
                String anchor = cc.clauses().get(0).field();   // flatten on a clause field; Having filters by all
                NumberExpr count = Dsl.numberOfFilledFields(m.flatten(cc.groupPath() + "*/" + anchor).having(having));
                Condition violation = cc.min() ? count.lt(cc.bound()) : count.gt(cc.bound());
                String tag = cc.clauses().stream().map(cl -> fieldTag(cl.field())).collect(Collectors.joining());
                String desc = cc.clauses().stream()
                        .map(cl -> cl.constraint().requirement(cl.field())).collect(Collectors.joining(" and "));
                yield Optional.of(m.rule(m.group(parentGroup(cc.groupPath())))
                        .name(row + (cc.min() ? "MinContains" : "MaxContains") + tag)
                        .errorField(m.field(cc.groupPath() + "/" + anchor))
                        .when(violation)
                        .code((cc.min() ? "MIN_CONTAINS_" : "MAX_CONTAINS_") + row.toUpperCase(Locale.ROOT)
                                + "_" + tag.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en("At " + (cc.min() ? "least " : "most ") + cc.bound() + " " + row
                                + " must match: " + desc + ".")).build());
            }
            case MinItemsCount mi -> {
                String group = lastSegment(mi.groupPath());
                if (!doc.isRepeating(mi.groupPath())) {
                    report.diagnostic("minItems on '" + group + "' skipped — the array did not import as a "
                            + "repeatable group (no maxItems / default cap)");
                    yield Optional.empty();
                }
                // Anchor the error on the FIRST scalar field inside the (possibly nested) row, IN-GROUP and
                // UNGUARDED — the count covers the error field, so it fires at 0…min-1 rows unconditionally
                // (KERNEL-FINDINGS §9; a guarded outside anchoring would suppress when its guard field is empty).
                Optional<String> anchor = doc.fieldPaths().stream()
                        .filter(p -> p.startsWith(mi.groupPath() + "/") && doc.fieldKind(p).isPresent())
                        .findFirst();
                if (anchor.isEmpty()) {
                    report.diagnostic("minItems on '" + group + "' skipped — the row has no scalar field to anchor "
                            + "the error on");
                    yield Optional.empty();
                }
                yield Optional.of(m.rule(m.group(parentGroup(mi.groupPath())))
                        .name(group + "MinItems").errorField(m.field(anchor.get()))
                        .when(Dsl.numberOfFilledGroups(m.group(mi.groupPath())).lt(mi.min()))
                        .code("MIN_ITEMS_" + group.toUpperCase(Locale.ROOT)).severity(Severity.ERROR)
                        .message(Dsl.en("At least " + mi.min() + " " + group + " "
                                + (mi.min() == 1 ? "is" : "are") + " required.")).build());
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

    /** The {@code [field] == value} condition for a string, enum, or number field (else empty — no other kind
     *  compares). Powers if/then discriminators, conjunctions, and the {@code contains} predicate. */
    private static Optional<Condition> equalsValue(BoundModel m, ModelDocument doc, String path, String value) {
        return switch (doc.fieldKind(path).orElse("")) {
            case "ENUMERATION" -> Optional.of(m.enumOf(path).is(value));
            case "STRING" -> Optional.of(m.str(path).eq(value));
            case "NUMBER" -> Optional.of(m.num(path).eq(value));
            default -> Optional.empty();
        };
    }

    /** The {@code if/else} condition: field present AND {@code [field] != value} (the {@code Filled} guard handles
     *  the JSON-Schema vacuous match — {@code else} applies only when the discriminator is present and differs). */
    private static Optional<Condition> notEqualsValue(BoundModel m, ModelDocument doc, String path, String value) {
        return switch (doc.fieldKind(path).orElse("")) {
            case "ENUMERATION" -> Optional.of(m.enumOf(path).filled().and(m.enumOf(path).isNot(value)));
            case "STRING" -> Optional.of(m.str(path).filled().and(m.str(path).ne(value)));
            case "NUMBER" -> Optional.of(m.num(path).filled().and(m.num(path).ne(value)));   // mirror equalsValue
            default -> Optional.empty();
        };
    }

    /** The condition true when {@code path} VIOLATES {@code c} (with a filled guard), or empty when the field's
     *  kind cannot carry the constraint (reported). Powers then-value-constraints (SCHEMAKIT-SPEC §4f). */
    private static Optional<Condition> violationCondition(BoundModel m, ModelDocument doc, String path,
                                                          ValueConstraint c, TranscodeReport.Builder report) {
        String kind = doc.fieldKind(path).orElse("");
        String field = lastSegment(path);
        return switch (c.kind()) {
            case CONST -> switch (kind) {
                case "STRING" -> Optional.of(m.str(path).filled().and(m.str(path).ne(c.value())));
                case "NUMBER" -> Optional.of(m.num(path).filled().and(m.num(path).ne(c.value())));
                case "ENUMERATION" -> Optional.of(m.enumOf(path).filled().and(m.enumOf(path).isNot(c.value())));
                default -> reportUnsupported(report, field, "const");
            };
            case MINIMUM, MAXIMUM, EXCLUSIVE_MIN, EXCLUSIVE_MAX -> {
                if (!"NUMBER".equals(kind)) {
                    yield reportUnsupported(report, field, "numeric bound");
                }
                NumberRef r = m.num(path);
                Condition v = switch (c.kind()) {
                    case MINIMUM -> r.lt(c.value());          // must be ≥ N → violation < N
                    case MAXIMUM -> r.gt(c.value());          // must be ≤ N → violation > N
                    case EXCLUSIVE_MIN -> r.le(c.value());    // must be > N → violation ≤ N
                    case EXCLUSIVE_MAX -> r.ge(c.value());    // must be < N → violation ≥ N
                    default -> throw new IllegalStateException();
                };
                yield Optional.of(r.filled().and(v));
            }
            case ENUM -> switch (kind) {
                case "STRING" -> {
                    StringRef r = m.str(path);
                    String[] rest = c.values().subList(1, c.values().size()).toArray(new String[0]);
                    yield Optional.of(r.filled().and(r.notIn(c.values().get(0), rest)));
                }
                case "ENUMERATION" -> {
                    EnumRef r = m.enumOf(path);
                    String[] rest = c.values().subList(1, c.values().size()).toArray(new String[0]);
                    yield Optional.of(r.filled().and(r.notIn(c.values().get(0), rest)));
                }
                default -> reportUnsupported(report, field, "enum");
            };
            case PATTERN -> "STRING".equals(kind)   // PatternViolated fires when the value does NOT match → the violation
                    ? Optional.of(m.str(path).filled().and(m.str(path).violates(c.value())))
                    : reportUnsupported(report, field, "pattern");
        };
    }

    private static Optional<Condition> reportUnsupported(TranscodeReport.Builder report, String field, String what) {
        report.diagnostic("then-constraint (" + what + ") on '" + field + "' skipped — the field's kind cannot carry it");
        return Optional.empty();
    }

    /** The condition true when {@code path} MATCHES {@code c} (the positive form — the inverse of
     *  {@link #violationCondition}). Powers the {@code contains} {@code Having} filter (SCHEMAKIT-SPEC §4f). */
    private static Optional<Condition> matchCondition(BoundModel m, ModelDocument doc, String path,
                                                      ValueConstraint c, TranscodeReport.Builder report) {
        String kind = doc.fieldKind(path).orElse("");
        String field = lastSegment(path);
        return switch (c.kind()) {
            case CONST -> equalsValue(m, doc, path, c.value());   // eq on string/enum/number, else empty
            case MINIMUM, MAXIMUM, EXCLUSIVE_MIN, EXCLUSIVE_MAX -> {
                if (!"NUMBER".equals(kind)) {
                    yield reportUnsupported(report, field, "numeric bound");
                }
                NumberRef r = m.num(path);
                yield Optional.of(switch (c.kind()) {
                    case MINIMUM -> r.ge(c.value());
                    case MAXIMUM -> r.le(c.value());
                    case EXCLUSIVE_MIN -> r.gt(c.value());
                    case EXCLUSIVE_MAX -> r.lt(c.value());
                    default -> throw new IllegalStateException();
                });
            }
            case ENUM -> switch (kind) {
                case "STRING" -> {
                    String[] rest = c.values().subList(1, c.values().size()).toArray(new String[0]);
                    yield Optional.of(m.str(path).in(c.values().get(0), rest));
                }
                case "ENUMERATION" -> {
                    String[] rest = c.values().subList(1, c.values().size()).toArray(new String[0]);
                    yield Optional.of(m.enumOf(path).in(c.values().get(0), rest));
                }
                default -> reportUnsupported(report, field, "enum");
            };
            case PATTERN -> "STRING".equals(kind)
                    ? Optional.of(m.str(path).matches(c.value()))
                    : reportUnsupported(report, field, "pattern");
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
            case MultiConditionalRequired mc -> mc.groupPath() + "/" + mc.requiredField();
            case ThenConstrained tc -> tc.groupPath() + "/" + tc.targetField();
            case ElseConstrained ec -> ec.groupPath() + "/" + ec.targetField();
            case ContainsCount cc -> cc.groupPath();
            case MinItemsCount mi -> mi.groupPath();
            case UniqueItems u -> u.groupPath();
            case ForbiddenConst f -> f.fieldPath();
            case ForbiddenEnum f -> f.fieldPath();
            case ForbiddenPattern f -> f.fieldPath();
            case ForbiddenBound f -> f.fieldPath();
            case ForbiddenLength f -> f.fieldPath();
            case ForbiddenAllPresent f -> f.groupPath();
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
            case MultiConditionalRequired mc -> "rule conditional-required(" + mc.conditions().stream()
                    .map(fv -> fv.field() + "=" + fv.value()).collect(Collectors.joining(" and "))
                    + " → " + mc.requiredField() + ")";
            case ThenConstrained tc -> "rule then-constraint(" + tc.conditions().stream()
                    .map(fv -> fv.field() + "=" + fv.value()).collect(Collectors.joining(" and "))
                    + " → " + tc.constraint().requirement(tc.targetField()) + ")";
            case ElseConstrained ec -> "rule else-constraint(not[" + ec.conditions().stream()
                    .map(fv -> fv.field() + "=" + fv.value()).collect(Collectors.joining(" and "))
                    + "] → " + ec.constraint().requirement(ec.targetField()) + ")";
            case ContainsCount cc -> "rule " + (cc.min() ? "minContains(" : "maxContains(") + cc.bound() + " × "
                    + lastSegment(cc.groupPath()) + "[" + cc.clauses().stream()
                    .map(cl -> cl.constraint().requirement(cl.field())).collect(Collectors.joining(", ")) + "])";
            case MinItemsCount mi -> "rule minItems(" + mi.min() + " × " + lastSegment(mi.groupPath()) + ")";
            case UniqueItems u -> "rule uniqueItems(" + lastSegment(u.groupPath()) + ")";
            case ForbiddenConst f -> "rule not-const(" + lastSegment(f.fieldPath()) + " ≠ " + f.value() + ")";
            case ForbiddenEnum f -> "rule not-enum(" + lastSegment(f.fieldPath()) + " ∉ " + f.values() + ")";
            case ForbiddenPattern f -> "rule not-pattern(" + lastSegment(f.fieldPath()) + " ∌ /" + f.regex() + "/)";
            case ForbiddenBound f -> "rule not-" + (f.min() ? "minimum(" : "maximum(") + lastSegment(f.fieldPath())
                    + " " + (f.min() ? "<" : ">") + " " + f.bound() + ")";
            case ForbiddenLength f -> "rule not-" + (f.min() ? "minLength(" : "maxLength(") + lastSegment(f.fieldPath())
                    + " length " + (f.min() ? "<" : ">") + " " + f.bound() + ")";
            case ForbiddenAllPresent f -> "rule not-all-present(" + f.fields() + ")";
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
    /** A name/code-safe camel tag for a (possibly nested) clause field: {@code payment/method} → {@code PaymentMethod}. */
    private static String fieldTag(String field) {
        StringBuilder sb = new StringBuilder();
        for (String seg : field.split("/")) {
            sb.append(capitalize(sanitize(seg)));
        }
        return sb.toString();
    }
}
