package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.core.KernelPatternPolicy;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.predicates.NumberBoundPredicatesKt;

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

    /**
     * A12's own encoding of "no bound": an absent member, or the sentinel {@code -1}.
     *
     * <p>{@code IFieldType.UNDEFINED} is {@code -1}, so a model may spell an unset length either way and A12
     * admits both — measured on every combination by
     * {@code rulekit.validate.laws.StringTypeParameterLawsTest}. Treating {@code -1} as a real bound reads
     * "a minimum of 2 and no maximum" as an inverted range, which refused a model A12 accepts and, because
     * the reader builds one of these records for every declared field, made that model impossible to
     * inspect through the kit.
     *
     * <p>The sentinel is preserved rather than normalized to {@code null}: it is what the model declares, and
     * re-encoding a value on the way in is the deviation {@code CLAUDE.md}'s JSON I/O rule names.
     *
     * <p>Private: it is an invariant of the records nested here, not a fact this kit publishes.
     */
    private static boolean declaresLength(Integer bound) {
        return bound != null && bound >= 0;
    }

    /**
     * A date range with a constant format and a range separator (both required by the kernel, §6), plus the
     * {@code interpretationOfYear} that decides which endpoint of a <b>wrapping yearless</b> range keeps the
     * base year — see {@link RangeYearInterpretation}. The interpretation is never {@code null}: an absent
     * declaration means {@link RangeYearInterpretation#STANDARD}, which is emitted by omitting the key.
     *
     * <p>{@code rejectBefore1900} is the kernel's {@code DateRangeType.youngerThan1900Check} — the same opt-in
     * additional check {@link DateSpec} carries, applied to the range's <b>start</b> date
     * ({@code FormatDefinitionDatumBereich extends FormatDefinitionDatum}, which passes {@code zusatzPruefung}
     * up; end ≥ start covers the end). Off by default.
     *
     * <p>Author through {@code DateRangeFieldBuilder}. The shorter constructors are retained as delegating
     * overloads, so existing <em>construction</em> sites keep compiling and their binary descriptors are
     * unchanged. <b>Record DECONSTRUCTION is NOT compatible</b>: a record pattern's component list must have the
     * record's arity, so {@code case DateRangeSpec(String f, String s)} no longer compiles and
     * {@code getRecordComponents()} returns four — the same honest classification API-FINDINGS 96 recorded for
     * {@code EnumSpec}'s identical change. A delegating constructor cannot help with either.
     */
    record DateRangeSpec(String format, String separator, RangeYearInterpretation interpretationOfYear,
            boolean rejectBefore1900) implements FieldTypeSpec {

        public DateRangeSpec {
            interpretationOfYear = interpretationOfYear == null
                    ? RangeYearInterpretation.STANDARD : interpretationOfYear;
        }

        public DateRangeSpec(String format, String separator, RangeYearInterpretation interpretationOfYear) {
            this(format, separator, interpretationOfYear, false);
        }

        public DateRangeSpec(String format, String separator) {
            this(format, separator, RangeYearInterpretation.STANDARD, false);
        }

        @Override
        public String jsonType() {
            return "DateRangeType";
        }
    }

    /**
     * A date fragment (e.g. year-month) with its fragment format (§6), plus the opt-in
     * {@code rejectBefore1900} ({@code DateFragmentType.youngerThan1900Check}). The kernel converts a fragment
     * declaration into an ordinary date type carrying that flag
     * ({@code TypeConverter.convertDateFragment} → {@code new DateTypeImpl(format, isYoungerThan1900Check(),
     * FULL)}), so the fragment's additional check is the date one. Off by default.
     *
     * <p>The 1-arg constructor is a delegating overload; record deconstruction sees two components, as for
     * {@link DateRangeSpec}.
     */
    record DateFragmentSpec(String fragmentFormat, boolean rejectBefore1900) implements FieldTypeSpec {

        public DateFragmentSpec(String fragmentFormat) {
            this(fragmentFormat, false);
        }

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
     * A string field: optional length bounds and an anchored pattern (KERNEL-SEMANTICS §7).
     *
     * <p>A non-empty pattern is inseparable from its {@code patternMessage}, and the refusal below is measured in BOTH
     * directions rather than only the obvious one ([KERNEL-FINDINGS KF318], locked by
     * {@code rulekit.validate.laws.StringTypeMessageAndPatternLawsTest}): a pattern with no error text draws
     * {@code MVK_ERROR_MESSAGE_AND_PATTERN_INVALID} and a type-level message with no pattern draws
     * {@code MVK_ERROR_MESSAGE_AND_NO_PATTERN}, so active patterns require their message. An empty pattern is inactive and may be stored without a message
     * (SPEC-2026-10-10-13; the same law test locks that boundary).
     *
     * <p>Where this check is WEAKER than A12 it under-rejects, which is the safe direction: A12 asks whether
     * the message reaches a declared locale with a non-blank text, and a blank {@code patternMessage} passes
     * here and is refused there.
     */
    record StringSpec(Integer minLength, Integer maxLength, String pattern, String patternMessage,
            boolean lineBreaksPermitted, boolean alphabeticalSorting, Map<String, List<String>> suggestions,
            boolean noValueValidation, Map<String, String> patternMessages) implements FieldTypeSpec {
        public StringSpec {
            if (declaresLength(minLength) && declaresLength(maxLength) && minLength > maxLength) {
                throw new IllegalArgumentException("minLength " + minLength + " > maxLength " + maxLength);
            }
            if ((pattern == null && patternMessage != null)
                    || (pattern != null && !pattern.isEmpty() && patternMessage == null)) {
                throw new IllegalArgumentException("a pattern and its error message must be set together");
            }
            if (pattern != null) {
                KernelPatternPolicy.requireAccepted(pattern);
            }
            // KERNEL-FINDINGS IF29: a no-value-validation string MUST permit line breaks — the kernel rejects
            // the pair otherwise (MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK). Fail fast with our own message.
            if (noValueValidation && !lineBreaksPermitted) {
                throw Refusals.of(KernelCode.MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK,
                        "noValueValidation requires line breaks to be permitted");
            }
            // a LinkedHashMap copy keeps the per-locale hint-list emit deterministic (idempotent toJson)
            suggestions = suggestions == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(suggestions));
            // per-locale override for the pattern error text (G23, the string sibling of the enum's
            // invalidValueMessages) — empty means the single patternMessage covers every declared locale.
            patternMessages = patternMessages == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(patternMessages));
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

        /** The earlier form: length bounds + optional anchored pattern, no hint-list/line-break/sorting slots. */
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
     * single-arg form fixes only the max, min defaulting to 0 — FEATURE-GAPS), optional value bounds,
     * the two kernel flags {@code zeroNotAllowed} (the engine emits a dedicated zero-not-allowed error) and
     * {@code leadingZerosAllowed}, the optional unit {@code trait} (amount/percent/permille), rendered-text
     * length bounds, and the integer-digit cap. Use {@link #builder()} for direct construction: the canonical
     * constructor's adjacent numeric slots are intentionally not the authoring idiom.
     */
    record NumberSpec(Integer minFractionalDigits, Integer maxFractionalDigits, BigDecimal minValue,
            BigDecimal maxValue, boolean zeroNotAllowed, boolean leadingZerosAllowed, NumberTrait trait,
            Integer minLength, Integer maxLength, Integer maxIntegerDigits,
            boolean positivesOnly) implements FieldTypeSpec {

        /** The kernel's cap on a number field's declared scale (measured: 15 is refused at model check). */
        static final int MAX_SCALE = 14;

        public NumberSpec {
            // The kernel caps the scale at 14 and reports an overrun as a plain, MVK-less model-check message
            // that names neither the property nor the cap — so refuse it here, correctively. A minimum above 14
            // draws that same sentence directly, with no maximum declared (KERNEL-FINDINGS KF559, the
            // "fractional minimum above the scale cap" row of adapter.laws.CheckerDigitMemberRangeAgreementTest).
            if (maxFractionalDigits != null && maxFractionalDigits > MAX_SCALE) {
                throw new IllegalArgumentException("maxFractionalDigits " + maxFractionalDigits
                        + " is refused HERE, before the kernel is asked; the kernel's documented cap is " + MAX_SCALE);
            }
            if (minFractionalDigits != null && minFractionalDigits > MAX_SCALE) {
                throw new IllegalArgumentException("minFractionalDigits " + minFractionalDigits
                        + " is refused HERE, before the kernel is asked; the kernel's documented scale cap is " + MAX_SCALE);
            }
            // A12's own law, measured: the pair is compared with an ABSENT member reading as 0 — which is
            // what the emitter writes for one — and a NEGATIVE maximum takes the pair out of the check
            // instead of being compared (MVK_FRACT_DIGITS_INVALID, [KERNEL-FINDINGS KF320]). Comparing only
            // when both are set left a real trap: `minFractionalDigits(2)` with no maximum was accepted here
            // and refused by A12.
            int declaredMin = minFractionalDigits == null ? 0 : minFractionalDigits;
            int declaredMax = maxFractionalDigits == null ? 0 : maxFractionalDigits;
            if (declaredMax >= 0 && declaredMin > declaredMax) {
                throw new IllegalArgumentException("minFractionalDigits " + declaredMin
                        + " > maxFractionalDigits " + declaredMax);
            }
            // A12 stores the bounds as doubles and, with no fractional digit allowed, compares them rounded
            // to whole numbers — so `1.4` over `1` is a legal declaration (KERNEL-FINDINGS KF569).
            if (minValue != null && maxValue != null && NumberBoundPredicatesKt.numberBoundsInverted(
                    minValue.doubleValue(), maxValue.doubleValue(), maxFractionalDigits)) {
                throw new IllegalArgumentException("minValue " + minValue + " > maxValue " + maxValue
                        + " (with no fractional digit allowed, A12 compares both rounded half away from zero)");
            }
        }

        /** Fluent construction avoids the record's ten-position, same-typed canonical constructor. */
        public static Builder builder() {
            return new Builder();
        }

        /** Backward-compatible pre-sign form; new internal callers use {@link #builder()}. */
        public NumberSpec(Integer minFractionalDigits, Integer maxFractionalDigits, BigDecimal minValue,
                BigDecimal maxValue, boolean zeroNotAllowed, boolean leadingZerosAllowed, NumberTrait trait,
                Integer minLength, Integer maxLength, Integer maxIntegerDigits) {
            this(minFractionalDigits, maxFractionalDigits, minValue, maxValue, zeroNotAllowed,
                    leadingZerosAllowed, trait, minLength, maxLength, maxIntegerDigits, false);
        }

        /** Backward-compatible pre-length form; new internal callers use {@link #builder()}. */
        public NumberSpec(Integer minFractionalDigits, Integer maxFractionalDigits, BigDecimal minValue,
                BigDecimal maxValue, boolean zeroNotAllowed, boolean leadingZerosAllowed, NumberTrait trait) {
            this(minFractionalDigits, maxFractionalDigits, minValue, maxValue, zeroNotAllowed,
                    leadingZerosAllowed, trait, null, null, null, false);
        }

        /** The earlier form: a max-scale (0..{@code fractionDigits}) number with an optional value range. */
        public NumberSpec(Integer fractionDigits, BigDecimal minValue, BigDecimal maxValue) {
            this(null, fractionDigits, minValue, maxValue, false, false, null, null, null, null, false);
        }

        @Override
        public String jsonType() {
            return "NumberType";
        }

        public static final class Builder {
            private Integer minFractionalDigits;
            private Integer maxFractionalDigits;
            private BigDecimal minValue;
            private BigDecimal maxValue;
            private boolean zeroNotAllowed;
            private boolean leadingZerosAllowed;
            private NumberTrait trait;
            private Integer minLength;
            private Integer maxLength;
            private Integer maxIntegerDigits;
            private boolean positivesOnly;

            public Builder minFractionalDigits(Integer value) {
                this.minFractionalDigits = value;
                return this;
            }

            public Builder maxFractionalDigits(Integer value) {
                this.maxFractionalDigits = value;
                return this;
            }

            public Builder minValue(BigDecimal value) {
                this.minValue = value;
                return this;
            }

            public Builder maxValue(BigDecimal value) {
                this.maxValue = value;
                return this;
            }

            public Builder zeroNotAllowed(boolean value) {
                this.zeroNotAllowed = value;
                return this;
            }

            public Builder leadingZerosAllowed(boolean value) {
                this.leadingZerosAllowed = value;
                return this;
            }

            public Builder trait(NumberTrait value) {
                this.trait = value;
                return this;
            }

            public Builder minLength(Integer value) {
                this.minLength = value;
                return this;
            }

            public Builder maxLength(Integer value) {
                this.maxLength = value;
                return this;
            }

            public Builder maxIntegerDigits(Integer value) {
                this.maxIntegerDigits = value;
                return this;
            }


            /**
             * Forbid a sign — the kernel's {@code NumberType.positivesOnly}, which the model→runtime
             * conversion inverts into {@code hasSign} ({@code TypeConverter}: {@code hasSign(!positivesOnly)})
             * and the runtime reads as {@code hatVorzeichen}, so the default is SIGNED. A minus on such a
             * field is the formal error {@code zahlMitVorzeichen}; the flag also flips the empty-operand fill
             * polarity, since an unsigned number cannot be filled smaller.
             *
             * <p>It also matters where no input is validated at all: a date construction's component satisfies
             * the kernel's low-side requirement either by declaring a non-negative minimum OR by being
             * unsigned, so a field can be a legal day or month slot with no minimum at all.
             */
            public Builder positivesOnly(boolean value) {
                this.positivesOnly = value;
                return this;
            }

            public NumberSpec build() {
                return new NumberSpec(minFractionalDigits, maxFractionalDigits, minValue, maxValue,
                        zeroNotAllowed, leadingZerosAllowed, trait, minLength, maxLength, maxIntegerDigits,
                        positivesOnly);
            }
        }
    }

    /**
     * A date field with a constant format (day-first constants, §6) and an optional <em>date precision</em>.
     * A precision other than {@code FULL} makes it a <em>partially known date</em> (a day/month/year part may
     * be unknown, e.g. {@code 00.02.2024}) — the field {@code ValueAsDate} resolves to a concrete date.
     */
    record DateSpec(String format, String datePrecision, boolean rejectBefore1900) implements FieldTypeSpec {
        public DateSpec(String format, String datePrecision) {
            this(format, datePrecision, false);
        }

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

    /**
     * A boolean (three-state, §1), with the text this model stores for true and for false.
     *
     * Both are {@code null} where the model takes A12's defaults, and they are independent — a model may
     * override one and leave the other (DM-JSON {@code notInDCustomTrueValue} / {@code notInDCustomFalseValue}).
     */
    record BooleanSpec(String customTrueValue, String customFalseValue) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "BooleanType";
        }
    }

    /** A confirm (unspecified = False, §1), carrying the same two stored-value overrides as [BooleanSpec]. */
    record ConfirmSpec(String customTrueValue, String customFalseValue) implements FieldTypeSpec {
        @Override
        public String jsonType() {
            return "ConfirmType";
        }
    }

    /**
     * An enumeration: at least one stored value; comparisons use the stored value, not the label (§8).
     * Optional {@code categories} group the values — each category maps positionally onto the values
     * ({@code categories[c].values[i]} classifies {@code values[i]}), so every category must classify
     * every value (length checked here). The optional {@code sValue} is the kernel's stored-token
     * <b>S-value</b> (DM-JSON {@code sValue}) — the model-owned default an eligible empty INDEX field
     * receives during full validation (SPEC-2026-07-23-14); it must name one of the declared values,
     * because the kernel stages it into evaluation without re-validating it. {@code null} for none.
     */
    record EnumSpec(List<EnumValue> values, List<EnumCategory> categories, boolean alphabeticalSorting,
            String invalidValueMessage, Map<String, String> invalidValueMessages,
            String sValue) implements FieldTypeSpec {
        public EnumSpec {
            values = List.copyOf(values);
            categories = List.copyOf(categories);
            invalidValueMessages = invalidValueMessages == null ? Map.of()
                    : OrderedMap.copyOf(invalidValueMessages);
            // Kernel basis (measured 2026-08-29, KERNEL-FINDINGS KF216): a category's `values` array pairs
            // POSITIONALLY with the enum's — disjoint tokens of the same length are kernel-valid, so equal
            // cardinality is the kernel's actual rule and not a proxy for set membership. The kernel reports
            // the two halves separately and per element (MVK_MISSING_CATEGORY_VALUE for an internal value with
            // no category token, MVK_MISSING_INTERNAL_VALUE for the reverse); this refusal collapses both into
            // one message, which under-reports rather than over-rejects. Locked:
            // rulekit.validate.laws.EnumCategoryAlignmentLawsTest.
            for (EnumCategory category : categories) {
                if (category.values().size() != values.size()) {
                    throw new IllegalArgumentException("category '" + category.name() + "' classifies "
                            + category.values().size() + " values but the enum has " + values.size()
                            + " (a category must classify every value)");
                }
            }
            // A12 compares both sides after Java's String.trim, so a padded S-value or token matches and one that
            // trims to nothing is unset (KERNEL-FINDINGS KF569). This constructor also reads models, so refusing
            // more than A12 would make a model A12 loads unreadable; the raw text is kept for the write-back.
            String token = sValue == null ? "" : sValue.trim();
            if (!token.isEmpty() && values.stream().noneMatch(v -> v.value().trim().equals(token))) {
                throw new IllegalArgumentException("the S-value '" + sValue
                        + "' is not one of the declared enum values "
                        + values.stream().map(EnumValue::value).toList()
                        + ", which A12 refuses too");
            }
        }

        /** The no-S-value form (the enum shape before SPEC-2026-07-23-14 added the index default). */
        public EnumSpec(List<EnumValue> values, List<EnumCategory> categories, boolean alphabeticalSorting,
                String invalidValueMessage, Map<String, String> invalidValueMessages) {
            this(values, categories, alphabeticalSorting, invalidValueMessage, invalidValueMessages, null);
        }

        /**
         * The single-message form: a single invalid-value message expanded across every declared locale (G23 added
         * the {@code invalidValueMessages} per-locale map below). Empty per-locale map.
         */
        public EnumSpec(List<EnumValue> values, List<EnumCategory> categories, boolean alphabeticalSorting,
                String invalidValueMessage) {
            this(values, categories, alphabeticalSorting, invalidValueMessage, Map.of(), null);
        }

        /** The earlier form: values + categories, no alphabetical-sort or invalid-value-message slot. */
        public EnumSpec(List<EnumValue> values, List<EnumCategory> categories) {
            this(values, categories, false, null, Map.of(), null);
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
            if (declaresLength(minLength) && declaresLength(maxLength) && minLength > maxLength) {
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
            labels = OrderedMap.copyOf(labels);
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
