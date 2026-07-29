package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — a quoted TIME constant is classified by its own <b>syntax</b>, exactly ASCII
 * {@code HH:mm:ss} with real-clock bounds, independently of locale and of the operand it is compared with
 * (the kernel's {@code CheckDatumAngabeImpl.checkDatumString} → {@code DateUtils}: an eight-character ASCII
 * shape, the fixed {@code HH:mm:ss} format, and a non-lenient parse). Anything else is an invalid date string
 * — never a runtime String value that might compare successfully.
 *
 * <p>Every rejection below reports the SAME code ({@code MVK_INVALID_COMPARE_TO_DATE}), which is the point:
 * width, punctuation, digit repertoire, surrounding whitespace, and range are one gate, not five. The typed
 * surface carries the text verbatim ({@code TimeLiteral}) and lets the kernel arbitrate — there is no
 * client-side pre-check to keep in step.
 *
 * <p>Reconciles lean SPEC-2026-07-27-06.
 */
class TimeLiteralLawsTest {

    @Test
    void anExactAsciiRealClockIsAccepted() {
        accepts("[PickupTime] == \"10:30:45\"");
        accepts("[PickupTime] == \"00:00:00\"");
        accepts("[PickupTime] == \"23:59:59\"");
    }

    @Test
    void everyComponentIsBoundedByTheRealClock() {
        rejects("[PickupTime] == \"24:00:00\"");   // no 24:00 midnight spelling
        rejects("[PickupTime] == \"23:60:00\"");
        rejects("[PickupTime] == \"23:59:60\"");   // and no leap second
    }

    @Test
    void everyComponentIsExactlyTwoDigitsWide() {
        rejects("[PickupTime] == \"1:30:45\"");
        rejects("[PickupTime] == \"10:3:45\"");
        rejects("[PickupTime] == \"10:30\"");      // a two-component prefix is not a Time constant
    }

    @Test
    void theSeparatorAndDigitRepertoireAreFixed() {
        rejects("[PickupTime] == \"10.30.45\"");
        rejects("[PickupTime] == \"10-30-45\"");
        rejects("[PickupTime] == \"١٠:٣٠:٤٥\"");   // Arabic-Indic digits
        rejects("[PickupTime] == \"10:30:45 \"");                                // no surrounding whitespace
    }

    /** The classification is context-free: the same literal is a Time in the `DateTime(...)` time argument. */
    @Test
    void theSameLiteralIsATimeInsideADateTimeConstruction() {
        accepts("[ScheduledAt] > DateTime(ValueAsDate(ApproxDate, LastDay), \"10:30:45\")");
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition,
                "MVK_INVALID_COMPARE_TO_DATE");
    }
}
