package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.DATE_FIELD;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.EQUATABLE_FIELD;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.FIELD;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.FIELD_FLATTEN;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.FIELD_LIST;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.GROUP;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.NAME;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.NUMBER_FIELD;
import static io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam.Kind.VALUE;

/**
 * The built-in validation idioms (FEATURE-GAPS G2) — each produces its violation condition through the
 * <b>typed DSL</b>, so the scaffolded rule is correct by construction (right polarity, a referenced error
 * field). Metadata (id/summary/params/skeleton/example) rides the enum constructor; {@link #instantiate}
 * builds + renders the condition and names the error field.
 *
 * <p>Polarity is the through-line: every condition is TRUE on the <em>violation</em> (an A12 rule fires on its
 * error scenario), so e.g. mutually-exclusive fires when BOTH fields are filled, at-least-one-of when NONE is.
 */
public enum BuiltInPattern implements PatternEntry {

    DATE_ORDER("date-order",
            "one date must not fall before another (e.g. delivery on/after order date)",
            "fires on the VIOLATION: `later` is strictly before `earlier`",
            "AllFieldsFilled(${earlier}, ${later}) And DifferenceInDays(${earlier}, ${later}) < 0",
            List.of(param("earlier", DATE_FIELD, "the date that must come first"),
                    param("later", DATE_FIELD, "the date that must fall on/after `earlier` (the error field)")),
            Map.of("earlier", "/Order/OrderDate", "later", "/Order/DeliveryDate")) {
        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            var earlier = m.date(req(a, "earlier"));
            var later = m.date(req(a, "later"));
            Condition c = Dsl.allFieldsFilled(earlier.field(), later.field())
                    .and(Dsl.differenceInDays(earlier, later).lt(0));
            return new RuleInstantiation(render(c), req(a, "later"), "DATE_ORDER");
        }
    },

    MUTUALLY_EXCLUSIVE("mutually-exclusive",
            "two fields must not both be filled",
            "fires on the VIOLATION: BOTH fields are filled",
            "AllFieldsFilled(${a}, ${b})",
            List.of(param("a", FIELD, "the first field (the error field)"),
                    param("b", FIELD, "the second field")),
            Map.of("a", "/Order/CustomerName", "b", "/Order/ProductCode")) {
        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            Condition c = Dsl.allFieldsFilled(m.field(req(a, "a")), m.field(req(a, "b")));
            return new RuleInstantiation(render(c), req(a, "a"), "MUTUALLY_EXCLUSIVE");
        }
    },

    AT_LEAST_ONE_OF("at-least-one-of",
            "at least one of a set of fields must be filled",
            "fires on the VIOLATION: NONE of the fields is filled",
            "NoFieldFilled(${fields})",
            List.of(param("fields", FIELD_LIST, "the fields, comma-separated; at least one must be filled (the first is the error field)")),
            Map.of("fields", "/Order/CustomerName,/Order/ProductCode")) {
        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            List<String> paths = list(req(a, "fields"));
            if (paths.size() < 2) {
                throw new IllegalArgumentException("at-least-one-of needs at least two comma-separated fields");
            }
            FieldRef first = m.field(paths.get(0));
            FieldRef[] rest = paths.subList(1, paths.size()).stream().map(m::field).toArray(FieldRef[]::new);
            Condition c = Dsl.noFieldFilled(first, rest);
            return new RuleInstantiation(render(c), paths.get(0), "AT_LEAST_ONE_OF");
        }
    },

    REQUIRED_WHEN("required-when",
            "a field is required when another field equals a given value",
            "fires on the VIOLATION: the trigger equals the value AND the required field is empty",
            "${trigger} == \"${value}\" And FieldNotFilled(${required})",
            List.of(param("trigger", EQUATABLE_FIELD, "the field whose value triggers the requirement (STRING or ENUMERATION)"),
                    param("value", VALUE, "the trigger value that makes `required` mandatory"),
                    param("required", FIELD, "the field that becomes required (the error field)")),
            Map.of("trigger", "/Order/Priority", "value", "EXPRESS", "required", "/Order/CustomerName")) {
        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            String triggerPath = req(a, "trigger");
            String value = req(a, "value");
            Condition triggered = switch (m.field(triggerPath).kind()) {
                case ENUMERATION -> m.enumOf(triggerPath).is(value);
                case STRING -> m.str(triggerPath).eq(value);
                default -> throw new IllegalArgumentException(
                        "required-when's trigger must be a STRING or ENUMERATION field, was "
                                + m.field(triggerPath).kind() + " at " + triggerPath);
            };
            Condition c = triggered.and(Dsl.fieldNotFilled(m.field(req(a, "required"))));
            return new RuleInstantiation(render(c), req(a, "required"), "REQUIRED_WHEN");
        }
    },

    SUM_OF_LINE_ITEMS("sum-of-line-items",
            "the sum of a repeated line-item amount must not exceed a cap field",
            "fires on the VIOLATION: the summed amount is greater than the cap",
            "Sum(${items}) > ${cap}",
            List.of(param("items", FIELD_FLATTEN, "the line-item amount as a flattened list, e.g. /Order/Items*/Count"),
                    param("cap", NUMBER_FIELD, "the number field the sum must not exceed (the error field)")),
            Map.of("items", "/Order/Items*/Count", "cap", "/Order/Quantity")) {
        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            Condition c = Dsl.sum(m.flatten(req(a, "items"))).gt(m.num(req(a, "cap")).asNumber());
            return new RuleInstantiation(render(c), req(a, "cap"), "SUM_OF_LINE_ITEMS");
        }
    },

    TIERED_AMOUNT("tiered-amount",
            "compute a number field as one value below a threshold, another at/above it",
            "the two preconditions partition the driver — `< threshold` vs `>= threshold` — so they are mutually"
                    + " exclusive AND exhaustive by construction (no overlap for the kernel to reject); guarded by"
                    + " FieldFilled(driver) so an empty driver computes nothing rather than the empty-as-0 band",
            "computed=${computed}; common FieldFilled(${driver}); when [${driver}] < ${threshold} => ${below};"
                    + " when [${driver}] >= ${threshold} => ${atOrAbove}",
            List.of(param("computed", NUMBER_FIELD, "the number field the computation assigns (must differ from the driver)"),
                    param("driver", NUMBER_FIELD, "the number field whose value selects the band"),
                    param("threshold", VALUE, "the band boundary; driver `< threshold` vs `>= threshold`"),
                    param("below", VALUE, "the value assigned when driver < threshold (same scale as `computed`)"),
                    param("atOrAbove", VALUE, "the value assigned when driver >= threshold (same scale as `computed`)")),
            Map.of("computed", "/Order/BackorderQuantity", "driver", "/Order/Quantity",
                    "threshold", "10", "below", "0", "atOrAbove", "5")) {
        @Override
        public PatternKind kind() {
            return PatternKind.COMPUTATION;
        }

        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            NumberRef computed = m.num(req(a, "computed"));
            NumberRef driver = m.num(req(a, "driver"));
            String threshold = req(a, "threshold");
            Computation c = m.computation(computed)
                    .name(computedName(computed))
                    .commonPrecondition(driver.filled())
                    .when(driver.lt(threshold)).calc(NumberLiteral.of(req(a, "below")))
                    .when(driver.ge(threshold)).calc(NumberLiteral.of(req(a, "atOrAbove")))
                    .message(placeholderMessages(m))
                    .build();
            return new ComputationInstantiation(c);
        }
    },

    BOUNDED_NUMBER("bounded-number",
            "add a NUMBER field constrained to a [min, max] range (the field-level alternative to a comparison rule)",
            "the bound lives in the field config (number.min / number.max), not a rule — see `operators LessThan`'s seeAlso",
            "field ${name} in ${group}: NUMBER with number.min=${min}, number.max=${max}",
            List.of(param("group", GROUP, "the existing group to add the field to (e.g. /Order)"),
                    param("name", NAME, "the new field's name"),
                    param("min", VALUE, "the minimum value (number.min)"),
                    param("max", VALUE, "the maximum value (number.max)")),
            Map.of("group", "/Order", "name", "RiskScore", "min", "0", "max", "100")) {
        @Override
        public PatternKind kind() {
            return PatternKind.FIELD;
        }

        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("min", new BigDecimal(req(a, "min")));
            config.put("max", new BigDecimal(req(a, "max")));
            return new FieldInstantiation(req(a, "group"), req(a, "name"), "NUMBER", config);
        }
    },

    FORMATTED_STRING("formatted-string",
            "add a STRING field constrained to a regex format (the field-level alternative to a pattern rule)",
            "the format lives in the field config (string.pattern + patternMessage), not a rule — see `operators PatternViolated`'s seeAlso",
            "field ${name} in ${group}: STRING with string.pattern=${pattern} (message ${message})",
            List.of(param("group", GROUP, "the existing group to add the field to (e.g. /Order)"),
                    param("name", NAME, "the new field's name"),
                    param("pattern", VALUE, "the anchored regex the value must match (string.pattern)"),
                    param("message", VALUE, "the error text shown when the value does not match (patternMessage)")),
            Map.of("group", "/Order", "name", "PostalCode", "pattern", "[0-9]{5}", "message", "must be five digits")) {
        @Override
        public PatternKind kind() {
            return PatternKind.FIELD;
        }

        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pattern", req(a, "pattern"));
            config.put("patternMessage", req(a, "message"));
            return new FieldInstantiation(req(a, "group"), req(a, "name"), "STRING", config);
        }
    },

    VALUE_SET_ENUM("value-set-enum",
            "add an ENUM field restricted to a fixed value set (the field-level alternative to a value-list rule)",
            "the allowed set lives in the field config (enum.values), not a rule — see `operators FieldValueIncludedInValueList`'s seeAlso",
            "field ${name} in ${group}: ENUM with enum.values=${values}",
            List.of(param("group", GROUP, "the existing group to add the field to (e.g. /Order)"),
                    param("name", NAME, "the new field's name"),
                    param("values", VALUE, "the allowed values, comma-separated (enum.values)")),
            Map.of("group", "/Order", "name", "Channel", "values", "WEB,PHONE,STORE")) {
        @Override
        public PatternKind kind() {
            return PatternKind.FIELD;
        }

        @Override
        public Instantiation instantiate(BoundModel m, Map<String, String> a) {
            List<Map<String, Object>> values = list(req(a, "values")).stream()
                    .map(v -> Map.<String, Object>of("value", v))
                    .toList();
            if (values.isEmpty()) {
                throw new IllegalArgumentException("value-set-enum needs at least one value in --arg values=A,B,C");
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("values", values);
            return new FieldInstantiation(req(a, "group"), req(a, "name"), "ENUM", config);
        }
    };

    private final String id;
    private final String summary;
    private final String polarityNote;
    private final String skeleton;
    private final List<PatternParam> params;
    private final Map<String, String> example;

    BuiltInPattern(String id, String summary, String polarityNote, String skeleton,
            List<PatternParam> params, Map<String, String> example) {
        this.id = id;
        this.summary = summary;
        this.polarityNote = polarityNote;
        this.skeleton = skeleton;
        this.params = List.copyOf(params);
        this.example = Map.copyOf(example);
    }

    @Override public String id() { return id; }
    @Override public String summary() { return summary; }
    @Override public PatternKind kind() { return PatternKind.RULE; }
    @Override public List<PatternParam> params() { return params; }
    @Override public String polarityNote() { return polarityNote; }
    @Override public String skeleton() { return skeleton; }
    @Override public Map<String, String> example() { return example; }

    private static PatternParam param(String name, PatternParam.Kind kind, String doc) {
        return new PatternParam(name, kind, doc);
    }

    /** Fetch a required arg or fail with a corrective message naming the missing parameter. */
    static String req(Map<String, String> args, String name) {
        String v = args.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing --arg " + name + "=<value>");
        }
        return v;
    }

    /** Split a comma-separated FIELD_LIST value into trimmed, non-empty paths. */
    static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Render a built condition to canonical EN DSL text (absolute paths). */
    static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN).render(condition);
    }

    /** A computation's default name — {@code <Field>Comp}, mirroring the CLI computation-spec default. */
    static String computedName(NumberRef computed) {
        String path = computed.field().path().value();
        return path.substring(path.lastIndexOf('/') + 1) + "Comp";
    }

    /**
     * Placeholder self-validation text per the model's declared locales — a computation needs ≥1 message to
     * build, but the verb replaces these with the caller's {@code --message} (or its own placeholder) on scaffold.
     */
    static LocalizedText[] placeholderMessages(BoundModel m) {
        List<String> locales = m.locales().isEmpty() ? List.of("en_US") : m.locales();
        return locales.stream()
                .map(locale -> new LocalizedText(locale, "computed value mismatch"))
                .toArray(LocalizedText[]::new);
    }
}
