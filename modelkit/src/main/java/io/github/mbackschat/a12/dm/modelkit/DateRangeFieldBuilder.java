package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-range field — both a format and a range separator are required by the kernel. */
public final class DateRangeFieldBuilder extends FieldAttrs<DateRangeFieldBuilder> {
    private String format;
    private String separator;
    private RangeYearInterpretation interpretationOfYear = RangeYearInterpretation.STANDARD;

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

    protected FieldTypeSpec spec() {
        if (format == null || separator == null) {
            throw new IllegalStateException("a date range needs both a format and a range separator");
        }
        return new FieldTypeSpec.DateRangeSpec(format, separator, interpretationOfYear);
    }
}
