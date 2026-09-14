package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — the two gates {@code NumberOfValueInFields} runs AFTER its string arm's type walk.
 *
 * <p>{@link ValueInFieldsConstantKindLawsTest} locks the switch on the constant: four arms, four type
 * classes. Its string arm does not end there — {@code CheckAnzahlWertOpImpl} runs the type walk, then
 * {@code checkAllAreValueValidation}, then {@code CheckOpUtils.checkNoDateConstant}, so a list that clears
 * the types meets two more gates.
 *
 * <p><b>The last of them is about the CONSTANT and not about the fields</b>, which is what makes it
 * surprising: a quoted literal is typed a DATE from its own syntax alone ({@code CheckKonstanteImpl} calls
 * {@code DateUtils.isStringAValidDate} on every string literal), so a perfectly ordinary list of text fields
 * is refused because of how the counted value happens to be spelled. {@code MVK_NO_DATE_CONSTANT} has
 * exactly one caller in the estate, and this is it.
 *
 * <p><b>Which literals that catches is the half a reimplementer gets wrong</b>, because the typing is by
 * LENGTH first: {@code "01.02"} is five characters and no pattern is tried, while {@code "32.01.2024"} and
 * {@code "29.02.2023"} match their pattern and fail a non-lenient parse. All three stay strings and are
 * counted, so the near-misses are what bound the gate.
 *
 * <p>Own domain (an insurance endorsement desk).
 */
class ValueInFieldsDateConstantLawsTest {

    private static final String ENDORSEMENT = DocumentModelDraft.named("endorsement-count").locale("en_US")
            .rootGroup("Endorsement", e -> e
                    .stringField("Reference", f -> { })
                    .stringField("Note", f -> { })
                    .stringField("Memo", f -> { })
                    .stringField("RawText", f -> f.lineBreaks().noValueValidation())
                    .dateField("IssuedOn", f -> f.format("dd.MM.yyyy"))
                    .dateField("ExpiresOn", f -> f.format("dd.MM.yyyy"))
                    .enumField("Channel", x -> x.value("01.02.2024").value("AGENT"))
                    .enumField("Origin", x -> x.value("01.02.2024").value("BROKER"))
                    .enumField("Tier", x -> x.value("BRONZE").value("GOLD")))
            .build().toJson();

    private static final String DATED = "MVK_NO_DATE_CONSTANT";

    private static String counting(String constant, String list) {
        return KernelLaws.verdictModel(ENDORSEMENT, "/Endorsement", "../Reference",
                "FieldFilled(Reference) And NumberOfValueInFields(" + constant + " In " + list + ") > 0");
    }

    /** The same date-shaped literal over lists that fail each earlier gate, so a green row states an order. */
    @Test
    void theGatesThatRunAfterTheStringArmsTypeWalk() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain literal over two text declarations — the control",
                counting("\"AGENT\"", "Note, Memo"));
        actual.put("a date-shaped literal over two text declarations",
                counting("\"01.02.2024\"", "Note, Memo"));
        actual.put("the same literal over two DATE declarations",
                counting("\"01.02.2024\"", "IssuedOn, ExpiresOn"));
        actual.put("the same literal over a list holding a field that waives value validation",
                counting("\"01.02.2024\"", "Note, RawText"));
        actual.put("the same literal over enumerations that do NOT declare it",
                counting("\"01.02.2024\"", "Tier, Note"));
        actual.put("the same literal over enumerations that DO declare it",
                counting("\"01.02.2024\"", "Channel, Origin"));
        actual.put("a date CONSTANT rather than a literal", counting("Today", "Note, Memo"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain literal over two text declarations — the control", KernelLaws.ADMITTED);
        expected.put("a date-shaped literal over two text declarations", DATED);
        // The type walk is the whole list's first pass, so it answers before either later gate.
        expected.put("the same literal over two DATE declarations", "MVK_NO_STRING_OR_ENUM");
        expected.put("the same literal over a list holding a field that waives value validation",
                "MVK_INVALID_RAW_TYPE");
        expected.put("the same literal over enumerations that do NOT declare it",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        // …and where the domain admits it, the date gate still refuses it — the gate reads the CONSTANT's
        // own spelling and never the declarations it is counted against.
        expected.put("the same literal over enumerations that DO declare it", DATED);
        // `Today` is a date constant by kind rather than by spelling and takes no arm of the switch at all.
        expected.put("a date CONSTANT rather than a literal", "MVK_INVALID_CONST");
        assertEquals(expected, actual, "the gates that run after the string arm's type walk");
    }

    /** Which spellings the kernel types as a date, over one fixed list of two text declarations. */
    @Test
    void whichLiteralsCountAsADate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a year-less date, with its trailing dot", counting("\"01.02.\"", "Note, Memo"));
        actual.put("a time", counting("\"10:30:00\"", "Note, Memo"));
        actual.put("a dated day", counting("\"01.02.2024\"", "Note, Memo"));
        actual.put("a stamp", counting("\"01.02.2024T10:30:00\"", "Note, Memo"));
        actual.put("a leap day in a leap year", counting("\"29.02.2024\"", "Note, Memo"));
        actual.put("the year-less date WITHOUT its trailing dot", counting("\"01.02\"", "Note, Memo"));
        actual.put("a day the calendar has not", counting("\"32.01.2024\"", "Note, Memo"));
        actual.put("a leap day in a common year", counting("\"29.02.2023\"", "Note, Memo"));
        actual.put("the year-less LEAP day", counting("\"29.02.\"", "Note, Memo"));
        actual.put("an ISO day", counting("\"2024-01-02\"", "Note, Memo"));
        actual.put("a stamp spelled with a space", counting("\"01.02.2024 10:30:00\"", "Note, Memo"));
        actual.put("a two-digit day and month with no separators", counting("\"0102\"", "Note, Memo"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a year-less date, with its trailing dot", DATED);
        expected.put("a time", DATED);
        expected.put("a dated day", DATED);
        expected.put("a stamp", DATED);
        expected.put("a leap day in a leap year", DATED);
        // The LENGTH picks the pattern before any pattern is tried, so five and four characters are refused
        // outright rather than read as a shorter date…
        expected.put("the year-less date WITHOUT its trailing dot", KernelLaws.ADMITTED);
        // …and these three are the right length, match their pattern, and fail the non-lenient parse behind
        // it. A reader checking only the shape would report all three, which is the forbidden direction.
        expected.put("a day the calendar has not", KernelLaws.ADMITTED);
        expected.put("a leap day in a common year", KernelLaws.ADMITTED);
        // The year-less parse leaves the year at the epoch default, and 1970 is not a leap year — so the
        // year-less spelling is not year-agnostic.
        expected.put("the year-less LEAP day", KernelLaws.ADMITTED);
        expected.put("an ISO day", KernelLaws.ADMITTED);
        expected.put("a stamp spelled with a space", KernelLaws.ADMITTED);
        expected.put("a two-digit day and month with no separators", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which literals count as a date");
    }
}
