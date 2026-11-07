package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date field's constant format. */
public final class DateFieldBuilder extends FieldAttrs<DateFieldBuilder> {
    private String format;
    private DatePrecision datePrecision = DatePrecision.FULL;
    private boolean rejectBefore1900;

    public DateFieldBuilder format(String format) {
        this.format = format;
        return this;
    }

    /**
     * The date precision. Any value other than {@link DatePrecision#FULL} makes this a
     * <em>partially known date</em> (a day/month/year part may be unknown) — the field
     * {@code ValueAsDate(field, FirstDay|LastDay)} then resolves it to a concrete date.
     */
    public DateFieldBuilder datePrecision(DatePrecision precision) {
        this.datePrecision = precision;
        return this;
    }

    /**
     * Declare A12's {@code DateType.youngerThan1900Check}, which through kernel 31.1.1 rejected stored and
     * computed values before 1900-01-01. Off by default.
     *
     * @deprecated A12 deprecated the attribute and stopped evaluating it in kernel 31.2 (A12K-4073): it admits
     *     every format and rejects no value. The kernel still reads and writes it, so the declaration survives a
     *     round trip, but it constrains nothing. Scheduled for removal with A12's own removal (A12K-4439).
     */
    @Deprecated(forRemoval = true)
    public DateFieldBuilder rejectBefore1900() {
        this.rejectBefore1900 = true;
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.DateSpec(format, datePrecision.jsonValue(), rejectBefore1900);
    }
}
