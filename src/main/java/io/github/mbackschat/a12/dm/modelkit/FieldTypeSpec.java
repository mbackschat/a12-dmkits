package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The typed configuration of a field — one record per kind, carrying only the slots that kind
 * actually has, so a string can't be given a numeric range and vice versa (illegal config is
 * unrepresentable). Built by the type-specific field builders in {@link DocumentModelDraft}; the
 * emitter pattern-switches over this sealed set to write the DM-JSON {@code fieldType} block.
 *
 * <p>{@code null} fields mean "slot not set" (the kernel's default applies). Value-range sanity
 * (min ≤ max) is checked where the builder constructs the record; full validity is the kernel's call.
 */
public sealed interface FieldTypeSpec {

    /** The kernel DM-JSON {@code fieldType.type} discriminator (e.g. {@code "StringType"}). */
    String jsonType();

    /** A date range with a constant format and a range separator (both required by the kernel, §6). */
    record DateRangeSpec(String format, String separator) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "DateRangeType";
        }
    }

    /** A date fragment (e.g. year-month) with its fragment format (§6). */
    record DateFragmentSpec(String fragmentFormat) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "DateFragmentType";
        }
    }

    /** A reference to a named {@code typeDefinitions[]} entry — the field's type is defined once, reused. */
    record TypeDefRef(String typeDefinitionId) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "TypeDefType";
        }
    }

    /**
     * A string field: optional length bounds and an anchored pattern (KERNEL-SEMANTICS §7). A pattern
     * is inseparable from its {@code patternMessage} — the kernel rejects a pattern with no error text
     * ({@code MVK_ERROR_MESSAGE_AND_PATTERN_INVALID}), so the two are set or unset together.
     */
    record StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage,
            boolean lineBreaksPermitted, boolean alphabeticalSorting, Map<String, List<String>> suggestions,
            boolean noValueValidation, Map<String, String> patternMessages) implements FieldTypeSpec {
        public StringSpec {
            if (minLength != null && minLength < 0) {
                throw new IllegalArgumentException("minLength must be ≥ 0: " + minLength);
            }
            if (minLength != null && maxLength != null && minLength > maxLength) {
                throw new IllegalArgumentException("minLength " + minLength + " > maxLength " + maxLength);
            }
            if ((pattern == null) != (patternMessage == null)) {
                throw new IllegalArgumentException("a pattern and its error message must be set together");
            }
            // a LinkedHashMap copy keeps the per-locale hint-list emit deterministic (idempotent toJson)
            suggestions = suggestions == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(suggestions));
            // per-locale override for the pattern error text (G23, the string sibling of the enum's
            // invalidValueMessages) — empty means the single patternMessage covers every declared locale.
            patternMessages = patternMessages == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(patternMessages));
        }

        /** No per-locale override: the single {@code patternMessage} is applied to every declared locale. */
        public StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage,
                boolean lineBreaksPermitted, boolean alphabeticalSorting, Map<String, List<String>> suggestions,
                boolean noValueValidation) {
            this(minLength, maxLength, pattern, patternMessage, lineBreaksPermitted, alphabeticalSorting,
                    suggestions, noValueValidation, Map.of());
        }

        /** Pre-attachment form: no {@code noValueValidation} (the value-skipping flag the attachment {@code content} field uses). */
        public StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage,
                boolean lineBreaksPermitted, boolean alphabeticalSorting, Map<String, List<String>> suggestions) {
            this(minLength, maxLength, pattern, patternMessage, lineBreaksPermitted, alphabeticalSorting,
                    suggestions, false);
        }

        /** The pre-§3.4 form: length bounds + optional anchored pattern, no hint-list/line-break/sorting slots. */
        public StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage) {
            this(minLength, maxLength, pattern, patternMessage, false, false, Map.of());
        }

        @Override
        public String jsonType() {
            return "StringType";
        }
    }

    /**
     * A number field: the decimal scale as a {@code [minFractionalDigits, maxFractionalDigits]} range (the
     * single-arg form fixes only the max, min defaulting to 0 — FEATURE-GAPS §3.4), optional value bounds,
     * the two kernel flags {@code zeroNotAllowed} (the engine emits a dedicated zero-not-allowed error) and
     * {@code leadingZerosAllowed}, and the optional unit {@code trait} (amount/percent/permille).
     */
    record NumberSpec(Integer minFractionalDigits, Integer maxFractionalDigits, BigDecimal minValue,
            BigDecimal maxValue, boolean zeroNotAllowed, boolean leadingZerosAllowed, NumberTrait trait)
            implements FieldTypeSpec {
        public NumberSpec {
            if (minFractionalDigits != null && minFractionalDigits < 0) {
                throw new IllegalArgumentException("minFractionalDigits must be ≥ 0: " + minFractionalDigits);
            }
            if (maxFractionalDigits != null && maxFractionalDigits < 0) {
                throw new IllegalArgumentException("maxFractionalDigits must be ≥ 0: " + maxFractionalDigits);
            }
            if (minFractionalDigits != null && maxFractionalDigits != null && minFractionalDigits > maxFractionalDigits) {
                throw new IllegalArgumentException("minFractionalDigits " + minFractionalDigits
                        + " > maxFractionalDigits " + maxFractionalDigits);
            }
            if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
                throw new IllegalArgumentException("minValue " + minValue + " > maxValue " + maxValue);
            }
        }

        /** The pre-§3.4 form: a max-scale (0..{@code fractionDigits}) number with an optional value range. */
        public NumberSpec(Integer fractionDigits, BigDecimal minValue, BigDecimal maxValue) {
            this(null, fractionDigits, minValue, maxValue, false, false, null);
        }

        @Override
        public String jsonType() {
            return "NumberType";
        }
    }

    /**
     * A date field with a constant format (day-first constants, §6) and an optional <em>date precision</em>.
     * A precision other than {@code FULL} makes it a <em>partially known date</em> (a day/month/year part may
     * be unknown, e.g. {@code 00.02.2024}) — the field {@code ValueAsDate} resolves to a concrete date.
     */
    record DateSpec(String format, String datePrecision) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "DateType";
        }
    }

    /** A time-of-day field with a constant format (§6). */
    record TimeSpec(String format) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "TimeType";
        }
    }

    /** A date-time field with a constant format (§6). */
    record DateTimeSpec(String format) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "DateTimeType";
        }
    }

    /** A boolean (three-state, §1). */
    record BooleanSpec() implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "BooleanType";
        }
    }

    /** A confirm (unspecified = False, §1). */
    record ConfirmSpec() implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "ConfirmType";
        }
    }

    /**
     * An enumeration: at least one stored value; comparisons use the stored value, not the label (§8).
     * Optional {@code categories} group the values — each category maps positionally onto the values
     * ({@code categories[c].values[i]} classifies {@code values[i]}), so every category must classify
     * every value (length checked here).
     */
    record EnumSpec(List<EnumValue> values, List<EnumCategory> categories, boolean alphabeticalSorting,
            String invalidValueMessage, Map<String, String> invalidValueMessages) implements FieldTypeSpec {
        public EnumSpec {
            values = List.copyOf(values);
            categories = List.copyOf(categories);
            invalidValueMessages = invalidValueMessages == null ? Map.of() : Map.copyOf(invalidValueMessages);
            for (EnumCategory category : categories) {
                if (category.values().size() != values.size()) {
                    throw new IllegalArgumentException("category '" + category.name() + "' classifies "
                            + category.values().size() + " values but the enum has " + values.size()
                            + " (a category must classify every value)");
                }
            }
        }

        /**
         * The §3.4 form: a single invalid-value message expanded across every declared locale (G23 added
         * the {@code invalidValueMessages} per-locale map below). Empty per-locale map.
         */
        public EnumSpec(List<EnumValue> values, List<EnumCategory> categories, boolean alphabeticalSorting,
                String invalidValueMessage) {
            this(values, categories, alphabeticalSorting, invalidValueMessage, Map.of());
        }

        /** The pre-§3.4 form: values + categories, no alphabetical-sort or invalid-value-message slot. */
        public EnumSpec(List<EnumValue> values, List<EnumCategory> categories) {
            this(values, categories, false, null, Map.of());
        }

        @Override
        public String jsonType() {
            return "EnumerationType";
        }
    }

    /**
     * A <b>custom</b> field type: a named kernel custom type ({@code CustomFieldType} discriminator + its
     * technical {@code name}) with optional length bounds. Validation of custom-typed values needs a custom
     * validator registered with the engine, so on a bare model only the structural carry round-trips
     * (KERNEL-FINDINGS); the name + bounds are what authoring records.
     */
    record CustomSpec(String name, Integer minLength, Integer maxLength) implements FieldTypeSpec {
        public CustomSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a custom field type needs a non-blank type name");
            }
            if (minLength != null && minLength < 0) {
                throw new IllegalArgumentException("minLength must be ≥ 0: " + minLength);
            }
            if (minLength != null && maxLength != null && minLength > maxLength) {
                throw new IllegalArgumentException("minLength " + minLength + " > maxLength " + maxLength);
            }
        }

        @Override
        public String jsonType() {
            return "CustomFieldType";
        }
    }

    /** One enumeration entry: a stored {@code value} and optional per-locale display labels. */
    record EnumValue(String value, Map<String, String> labels) {
        public EnumValue {
            labels = Map.copyOf(labels);
        }
    }

    /**
     * A named classification of the enum's values: {@code values[i]} is the category of enum value {@code i}.
     * The optional {@code description} is an editor-level note (the kernel core doesn't model it — it tolerates
     * and preserves it; KERNEL-FINDINGS §8); {@code null} for none.
     */
    record EnumCategory(String name, List<String> values, String description) {
        public EnumCategory {
            values = List.copyOf(values);
        }

        /** A category with no description (the common case). */
        public EnumCategory(String name, List<String> values) {
            this(name, values, null);
        }
    }
}
