package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-range field — both a format and a range separator are required by the kernel. */
public final class DateRangeFieldBuilder extends FieldAttrs<DateRangeFieldBuilder> {
    private String format;
    private String separator;
    private RangeYearInterpretation interpretationOfYear = RangeYearInterpretation.STANDARD;
    private boolean rejectBefore1900;

    public DateRangeFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    public DateRangeFieldBuilder separator(String separator) {
        this.separator = separator;
        return this;
    }

    /**
     * Which endpoint of a <b>wrapping yearless</b> range keeps the base year — see
     * {@link RangeYearInterpretation}. Leaving it unset (or passing {@code STANDARD}/{@code null}) omits the
     * DM-JSON key, which is how the declaration expresses the default.
     */
    public DateRangeFieldBuilder interpretationOfYear(RangeYearInterpretation interpretation) {
        // No null-coalesce here: the record's canonical constructor owns that normalization, and two
        // loci for one invariant means neither can be mutation-covered.
        this.interpretationOfYear = interpretation;
        return this;
    }

    /**
     * Declare A12's {@code DateRangeType.youngerThan1900Check} — the range's own opt-in additional check, which
     * through kernel 31.1.1 rejected a <b>start</b> date before 1900-01-01. Off by default.
     *
     * @deprecated A12 deprecated the attribute and stopped evaluating it in kernel 31.2 (A12K-4073): it admits
     *     every format and rejects no value. The kernel still reads and writes it, so the declaration survives a
     *     round trip, but it constrains nothing. Scheduled for removal with A12's own removal (A12K-4439).
     */
    @Deprecated(forRemoval = true)
    public DateRangeFieldBuilder rejectBefore1900() {
        this.rejectBefore1900 = true;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (format == null || separator == null) {
            throw new IllegalStateException("a date range needs both a format and a range separator");
        }
        return new FieldTypeSpec.DateRangeSpec(format, separator, interpretationOfYear, rejectBefore1900);
    }
}
