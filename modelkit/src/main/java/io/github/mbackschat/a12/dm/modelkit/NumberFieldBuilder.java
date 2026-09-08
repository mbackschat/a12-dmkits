package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;

/** Configures a number field (scale/value/text-length/digit bounds, zero/leading-zero flags, and unit). */
public final class NumberFieldBuilder extends FieldAttrs<NumberFieldBuilder> {
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

    /** Fix the maximum scale (0..{@code n} decimal places); the minimum stays 0. */
    public NumberFieldBuilder fractionDigits(int n) {
        this.maxFractionalDigits = n;
        return this;
    }

    /** Bound the scale to a {@code [min, max]} range of decimal places (FEATURE-GAPS). */
    public NumberFieldBuilder fractionDigits(int min, int max) {
        this.minFractionalDigits = min;
        this.maxFractionalDigits = max;
        return this;
    }

    public NumberFieldBuilder min(String value) {
        this.minValue = new BigDecimal(value);
        return this;
    }

    public NumberFieldBuilder max(String value) {
        this.maxValue = new BigDecimal(value);
        return this;
    }

    /**
     * Declare the field UNSIGNED (A12's {@code positivesOnly}; the kernel reads it inverted as
     * {@code hasSign()}, so signed is the default). Beyond input validation this decides slot eligibility in a
     * date construction: a component satisfies the kernel's low-side requirement by declaring a non-negative
     * minimum OR by being unsigned, so an unsigned field needs no minimum to be a legal day or month.
     */
    public NumberFieldBuilder positivesOnly() {
        this.positivesOnly = true;
        return this;
    }

    /** Reject the value 0 (the kernel emits a dedicated zero-not-allowed error). */
    public NumberFieldBuilder zeroNotAllowed() {
        this.zeroNotAllowed = true;
        return this;
    }

    /** Permit leading zeros in the input (the kernel strips them by default). */
    public NumberFieldBuilder leadingZerosAllowed() {
        this.leadingZerosAllowed = true;
        return this;
    }

    /** Set the unit trait — {@code AMOUNT} (money), {@code PERCENT}, or {@code PERMILLE}. */
    public NumberFieldBuilder unit(NumberTrait trait) {
        this.trait = trait;
        return this;
    }

    /** Set the minimum length of the rendered stored-number text. */
    public NumberFieldBuilder minLength(int n) {
        this.minLength = n;
        return this;
    }

    /** Set the maximum length of the rendered stored-number text. */
    public NumberFieldBuilder maxLength(int n) {
        this.maxLength = n;
        return this;
    }

    /** Cap the digits before the decimal separator; sign and fractional digits do not count. */
    public NumberFieldBuilder maxIntegerDigits(int n) {
        this.maxIntegerDigits = n;
        return this;
    }

    protected FieldTypeSpec spec() {
        return FieldTypeSpec.NumberSpec.builder()
                .minFractionalDigits(minFractionalDigits)
                .maxFractionalDigits(maxFractionalDigits)
                .minValue(minValue)
                .maxValue(maxValue)
                .zeroNotAllowed(zeroNotAllowed)
                .positivesOnly(positivesOnly)
                .leadingZerosAllowed(leadingZerosAllowed)
                .trait(trait)
                .minLength(minLength)
                .maxLength(maxLength)
                .maxIntegerDigits(maxIntegerDigits)
                .build();
    }
}
