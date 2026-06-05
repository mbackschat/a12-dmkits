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
    record StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage)
            implements FieldTypeSpec {
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
        }

        @Override
        public String jsonType() {
            return "StringType";
        }
    }

    /** A number field: {@code fractionDigits} is the decimal scale (§5b); optional value bounds. */
    record NumberSpec(Integer fractionDigits, BigDecimal minValue, BigDecimal maxValue) implements FieldTypeSpec {
        public NumberSpec {
            if (fractionDigits != null && fractionDigits < 0) {
                throw new IllegalArgumentException("fractionDigits must be ≥ 0: " + fractionDigits);
            }
            if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
                throw new IllegalArgumentException("minValue " + minValue + " > maxValue " + maxValue);
            }
        }

        @Override
        public String jsonType() {
            return "NumberType";
        }
    }

    /** A date field with a constant format (day-first constants, §6). */
    record DateSpec(String format) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "DateType";
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

    /** An enumeration: at least one stored value; comparisons use the stored value, not the label (§8). */
    record EnumSpec(List<EnumValue> values) implements FieldTypeSpec {
        public EnumSpec {
            values = List.copyOf(values);
        }

        @Override
        public String jsonType() {
            return "EnumerationType";
        }
    }

    /** One enumeration entry: a stored {@code value} and optional per-locale display labels. */
    record EnumValue(String value, Map<String, String> labels) {
        public EnumValue {
            labels = Map.copyOf(labels);
        }
    }
}
