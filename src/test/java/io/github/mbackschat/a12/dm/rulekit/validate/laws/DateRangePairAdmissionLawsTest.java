package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * WHICH tier refuses a DATERANGE whose {@code (format, rangeSeparator)} pair A12 does not allowlist, and
 * what an ABSENT separator means in stored JSON.
 *
 * <p>{@link TemporalFormatVocabularyGuardTest} derives the eight admitted pairs and publishes them in
 * {@code docs/TEMPORAL-FORMAT-VOCABULARY.md}, but it asks the kernel a boolean: admitted, or not. A consumer
 * that wants to SAY something about a refused pair needs the tier as well, because the four tiers are
 * different claims — a reader refusal means A12 never examined the declaration, a build failure means it
 * read the model and could not construct it, a CODELESS refusal means it judged the declaration and named no
 * class, and an {@code MVK_*} code means it judged and named one. This grid settles that, and settles the
 * absent-member question the stored-JSON carrier raises and the typed builder cannot: the builder always
 * writes a separator.
 *
 * <p><b>Corrected 2026-09-15: every refusal here is the CODELESS-VERDICT tier, not the build tier.</b> The
 * rows read {@code NOT BUILDABLE} until {@link KernelLaws#REFUSED_CODELESS} existed, which said A12 had
 * reached no verdict about the declaration — and its sentence is {@code "Field with path '…': The date range
 * format is invalid."}, a verdict about exactly this declaration. So a consumer may say the pair was refused
 * and why; what it may not do is cite one of A12's classes, because A12 names none.
 *
 * <p><b>The pair is genuinely a pair, in both directions.</b> A format admitted with one separator is
 * refused with another, and a separator admitted with one format is refused with another — so neither half
 * can be judged alone, and a checker keyed on either would be wrong about the models this grid's middle rows
 * hold. The controls are the admitted pairs that differ from them in exactly one member.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName} — a plain root field no other
 * declaration names, so a verdict here is about the type and not about a role the field plays.
 */
class DateRangePairAdmissionLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The pair grid: two admitted controls, then each half swapped for one the OTHER control admits.
     *
     * <p>The two swap rows are the point. {@code yyyy-MM-dd} is admitted with {@code /} and {@code -} is
     * admitted with {@code dd.MM.yyyy}, so every member of {@code (yyyy-MM-dd, -)} appears in an admitted
     * pair and the combination is still refused; the mirrored row says the same from the other side. Without
     * both, one refusal would be equally consistent with a bad format or a bad separator.
     */
    @Test
    void aDateRangeAllowlistsThePairAndRefusesItWithoutNamingAClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ISO with its separator — a control", range("{\"format\":\"yyyy-MM-dd\",\"rangeSeparator\":\"/\"}"));
        actual.put("German with its separator — a control",
                range("{\"format\":\"dd.MM.yyyy\",\"rangeSeparator\":\"-\"}"));
        actual.put("ISO with the German pair's separator",
                range("{\"format\":\"yyyy-MM-dd\",\"rangeSeparator\":\"-\"}"));
        actual.put("German with the ISO pair's separator",
                range("{\"format\":\"dd.MM.yyyy\",\"rangeSeparator\":\"/\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ISO with its separator — a control", KernelLaws.ADMITTED);
        expected.put("German with its separator — a control", KernelLaws.ADMITTED);
        // Neither half is faulty on its own — each appears in one of the controls above — so the allowlist
        // reads the combination. The refusal is a verdict A12 states in prose — "The date range format is
        // invalid." — so a consumer may report it and may not attribute one of A12's classes to it.
        expected.put("ISO with the German pair's separator", KernelLaws.REFUSED_CODELESS);
        expected.put("German with the ISO pair's separator", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "a DATERANGE allowlists the (format, separator) pair");
    }

    /**
     * An ABSENT {@code rangeSeparator} is the kernel's own default and not "no separator".
     *
     * <p>Stored JSON can omit the member, which the typed builder cannot, so this is the carrier that can
     * ask. The kernel's answer is {@code FieldTypeConstants.A12_DATE_RANGE_SEPARATOR}, {@code "/"}
     * (source-read of {@code DateRangeType} and its {@code DateRangeTypeBuilder}, kernel {@code 30.8.1},
     * 2026-09-13) — and the pair of rows measures that rather than trusting it: omission behaves like an
     * explicit {@code /} on both a format {@code /} admits and one it does not.
     *
     * <p>The EMPTY separator is the row that keeps the default reading honest. {@code ""} is a declarable
     * value with admitted pairs of its own, so "absent" and "empty" are different declarations; a reader
     * treating omission as the empty string would get the last two rows exactly backwards.
     */
    @Test
    void anAbsentSeparatorIsTheKernelsDefaultAndNotTheEmptyOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ISO, separator omitted", range("{\"format\":\"yyyy-MM-dd\"}"));
        actual.put("German, separator omitted", range("{\"format\":\"dd.MM.yyyy\"}"));
        actual.put("ISO with an explicit empty separator",
                range("{\"format\":\"yyyy-MM-dd\",\"rangeSeparator\":\"\"}"));
        actual.put("month with an explicit empty separator", range("{\"format\":\"MM\",\"rangeSeparator\":\"\"}"));
        actual.put("month, separator omitted", range("{\"format\":\"MM\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ISO, separator omitted", KernelLaws.ADMITTED);
        // The discriminator for "omission means `/`": the same omission on a format `/` is not admitted
        // with, and it draws the same prose sentence as an explicit mismatching separator.
        expected.put("German, separator omitted", KernelLaws.REFUSED_CODELESS);
        expected.put("ISO with an explicit empty separator", KernelLaws.REFUSED_CODELESS);
        // …and the discriminator for "the empty separator is a declaration rather than a missing one".
        expected.put("month with an explicit empty separator", KernelLaws.ADMITTED);
        // `MM` is admitted with BOTH the empty separator and `/`, so this row cannot separate the two
        // readings — it is here because a consumer must not read the two rows above as "empty is refused".
        expected.put("month, separator omitted", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an omitted rangeSeparator is the kernel's `/` default");
    }

    /**
     * The range domain is NOT a projection of the field-format vocabulary, measured in both directions.
     *
     * <p>{@code yyyyMM} is an admitted FIELD format and is refused on a range at every probed separator;
     * {@code dd.MM} is refused as a field format and admitted on a range with {@code -}. So a consumer that
     * reused the thirteen-member field vocabulary here would both refuse a legal model and miss an illegal
     * one, which is why {@code docs/TEMPORAL-FORMAT-VOCABULARY.md} carries the pairs as their own table.
     */
    @Test
    void theRangeVocabularyIsItsOwnDomain() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field-admitted format on a range",
                range("{\"format\":\"yyyyMM\",\"rangeSeparator\":\"/\"}"));
        actual.put("a field-REFUSED format on a range",
                range("{\"format\":\"dd.MM\",\"rangeSeparator\":\"-\"}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field-admitted format on a range", KernelLaws.REFUSED_CODELESS);
        expected.put("a field-REFUSED format on a range", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the DATERANGE pair domain is measured, not projected");
    }

    /** The order fixture with {@code /Order/CustomerName} typed {@code DateRangeType} over [rawBody]. */
    private static String range(String rawBody) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ((ObjectNode) element.path("Field")).set("fieldType", MAPPER.readTree(
                            "{\"type\":\"DateRangeType\",\"DateRangeType\":" + rawBody + "}"));
                    return KernelLaws.verdictOf(root.toString());
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw body must parse", unreadable);
        }
    }
}
