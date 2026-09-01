package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The typed temporal handles are obtained by a field's DECLARED FORMAT, not only by its kind (ex-{@code DG40}).
 *
 * <p>A12's temporal format vocabulary is kind-independent, and so is everything below the declaration: a TIME
 * field declared {@code yyyy-MM-dd} carries the Gregorian floor, compares directly against a DATE, and answers
 * {@code YearFromDate} while its own kind's {@code HoursFromTime} is refused — measured on both codegen
 * strategies in KERNEL-FINDINGS KF211. Keying {@link BoundModel#date} on the kind alone made every one of those
 * kernel-legal conditions <em>unrepresentable</em> rather than refused: no error to read, just a missing factory.
 *
 * <p>{@link BoundModel#ofJson} runs the kernel's own {@code checkConsistency}, so binding the fixture here is
 * itself the proof that these declarations are model-legal; the conditions they enable are run through the
 * kernel in {@code adapter.laws.TemporalDeclaredFormatGateDiffTest}.
 */
class TemporalFormatHandleTest {

    private static final String MODEL = DocumentModelDraft.named("temporal-handles").locale("en_US")
            .rootGroup("P", p -> p
                    .timeField("TimeAsDate", f -> f.format("yyyy-MM-dd"))
                    .dateField("DateAsDate", f -> f.format("yyyy-MM-dd"))
                    .dateField("DateAsClock", f -> f.format("HH:mm:ss"))
                    .timeField("TimeAsStamp", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .timeField("TimeAsTime", f -> f.format("HH:mm:ss"))
                    .numberField("Amount", f -> f.fractionDigits(2)))
            .build().toJson();

    private final BoundModel m = BoundModel.ofJson(MODEL);

    @Test
    void aDateDeclaredTimeFieldYieldsADateHandleAndAuthorsTheKernelLegalCondition() {
        String rendered = new DslRenderer().render(
                m.date("/P/TimeAsDate").after(m.date("/P/DateAsDate")));
        assertEquals("[/P/TimeAsDate] > [/P/DateAsDate]", rendered,
                "the condition the kernel admits, now authorable through the typed surface");
        assertEquals("[/P/TimeAsDate] == [/P/DateAsDate]",
                new DslRenderer().render(m.date("/P/TimeAsDate").eq(m.date("/P/DateAsDate"))),
                "equality too");
        assertEquals("YearFromDate(/P/TimeAsDate) == 2024",
                new DslRenderer().render(Dsl.yearFromDate(m.date("/P/TimeAsDate")).eq(2024)),
                "the date component extractor over a TIME-kind field");
        assertEquals("DifferenceInDays(/P/TimeAsDate, /P/DateAsDate) == 0",
                new DslRenderer().render(
                        Dsl.differenceInDays(m.date("/P/TimeAsDate"), m.date("/P/DateAsDate")).eq(0)),
                "and the difference family");
    }

    @Test
    void aClockDeclaredDateFieldYieldsATimeHandleAndAnIsoDeclaredTimeFieldADateTimeOne() {
        assertEquals("[/P/DateAsClock] == [/P/TimeAsTime]",
                new DslRenderer().render(m.time("/P/DateAsClock").eq(m.time("/P/TimeAsTime"))),
                "the mirror direction: a DATE field declared with the clock format is a time operand");
        assertTrue(m.dateTime("/P/TimeAsStamp").field().path().value().endsWith("TimeAsStamp"),
                "a TIME field declared with the ISO datetime format is a date-time operand");
    }

    @Test
    void theDeclaredFormatIsReadableAndDecidesTheRefusals() {
        assertEquals("yyyy-MM-dd", m.fieldAt("/P/TimeAsDate").orElseThrow().format().orElseThrow(),
                "the declared format is on the read surface, since it is what decides the rest");

        // A clock-declared field is still refused a DATE handle, and the message names the deciding format.
        IllegalArgumentException clock =
                assertThrows(IllegalArgumentException.class, () -> m.date("/P/TimeAsTime"));
        assertTrue(clock.getMessage().contains("HH:mm:ss"),
                "the refusal must name the declared format that decided it — got: " + clock.getMessage());

        // But a KIND-matching field is always admitted, format notwithstanding: the declared format only ADDS
        // handles, it never takes one away, because a refusal justified by the format alone would have no
        // measured kernel basis for this exact input. The kernel's authoring gate still reports any operand it
        // does not accept, so nothing here promises a condition it would refuse.
        assertTrue(m.time("/P/TimeAsDate").field().path().value().endsWith("TimeAsDate"),
                "a TIME field stays a time handle on its kind, whatever it declares");

        // A non-temporal kind carries no format, so the kind test alone refuses it.
        IllegalArgumentException number =
                assertThrows(IllegalArgumentException.class, () -> m.date("/P/Amount"));
        assertTrue(number.getMessage().contains("NUMBER"), "a number is refused on its kind: " + number.getMessage());
    }
}
