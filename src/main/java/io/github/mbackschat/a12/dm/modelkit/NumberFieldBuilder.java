package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;

/** Configures a number field (decimal-place range, optional value range, zero/leading-zero flags). */
public final class NumberFieldBuilder extends FieldAttrs<NumberFieldBuilder> {
    private Integer minFractionalDigits;
    private Integer maxFractionalDigits;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private boolean zeroNotAllowed;
    private boolean leadingZerosAllowed;
    private NumberTrait trait;

    /** Fix the maximum scale (0..{@code n} decimal places); the minimum stays 0. */
    public NumberFieldBuilder fractionDigits(int n) {
        this.maxFractionalDigits = n;
        return this;
    }

    /** Bound the scale to a {@code [min, max]} range of decimal places (FEATURE-GAPS §3.4). */
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

    /** Set the unit trait — {@code AMOUNT} (money), {@code PERCENT}, or {@code PERMILLE} (§3.4). */
    public NumberFieldBuilder unit(NumberTrait trait) {
        this.trait = trait;
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.NumberSpec(minFractionalDigits, maxFractionalDigits, minValue, maxValue,
                zeroNotAllowed, leadingZerosAllowed, trait);
    }
}
