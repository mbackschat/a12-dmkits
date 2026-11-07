package io.github.mbackschat.a12.dm.modelkit;

/** Configures a date-fragment field (e.g. year-month) — the fragment format is required. */
public final class DateFragmentFieldBuilder extends FieldAttrs<DateFragmentFieldBuilder> {
    private String fragmentFormat;
    private boolean rejectBefore1900;

    public DateFragmentFieldBuilder fragmentFormat(String format) {
        this.fragmentFormat = format;
        return this;
    }

    /**
     * Declare A12's {@code DateFragmentType.youngerThan1900Check}, which through kernel 31.1.1 rejected values
     * before 1900-01-01 — the same opt-in additional check {@link DateFieldBuilder#rejectBefore1900()} carries,
     * which the kernel reaches by converting the fragment declaration into a date type holding the flag. Off by
     * default.
     *
     * @deprecated A12 deprecated the attribute and stopped evaluating it in kernel 31.2 (A12K-4073): it admits
     *     every format and rejects no value. The kernel still reads and writes it, so the declaration survives a
     *     round trip, but it constrains nothing. Scheduled for removal with A12's own removal (A12K-4439).
     */
    @Deprecated(forRemoval = true)
    public DateFragmentFieldBuilder rejectBefore1900() {
        this.rejectBefore1900 = true;
        return this;
    }

    protected FieldTypeSpec spec() {
        if (fragmentFormat == null) {
            throw new IllegalStateException("a date fragment needs a fragment format");
        }
        return new FieldTypeSpec.DateFragmentSpec(fragmentFormat, rejectBefore1900);
    }
}
