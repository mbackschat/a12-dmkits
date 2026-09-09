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
     * Reject values before 1900-01-01 through A12's {@code DateFragmentType.youngerThan1900Check} — the same
     * opt-in additional check {@link DateFieldBuilder#rejectBefore1900()} carries, which the kernel reaches by
     * converting the fragment declaration into a date type holding the flag. Off by default.
     */
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
