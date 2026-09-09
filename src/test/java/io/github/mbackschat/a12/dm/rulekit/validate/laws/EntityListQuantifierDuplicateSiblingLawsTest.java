package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * KERNEL-FINDINGS §1 — the SIBLING sweep of the field-list quantifier family, all nine keywords over
 * all four duplicate arms.
 *
 * <p>{@code DuplicateOverlapCarrierSweepLawsTest} measures three of the four arms on {@code AllFieldsFilled}
 * alone. That is one member of nine, and it is the member whose gate was being designed — so a claim written into the other
 * eight operators' catalog entries would be a scope transfer across the axis the grep grouped and nobody read.
 * Client-side the transfer is trivially sound (one record, {@code EntityListPredicate}, keyed only by the
 * keyword), which is exactly why it needs measuring: the catalog states what the KERNEL refuses, and the
 * client's uniformity is no evidence about the kernel's.
 *
 * <p>Two of the eight are not filled-quantifier siblings at all — {@code FieldValuesNotUnique} compares values
 * and {@code DateRangesOverlap} pairs endpoints — so if the family's duplicate rule were keyed on anything but
 * the operand list, those two are where it would show.
 *
 * <p>The four arms per keyword: an exact direct duplicate, a group beside its own descendant field, a
 * repeated STARRED operand (the exemption), and that same containment with the star on BOTH sides (the
 * boundary the star does not buy). The leading {@code FieldFilled(Quantity)} satisfies
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} for rows whose operands do not mention the error field.
 *
 * <p><b>{@code DateRangesOverlap}'s starred rows use the fixture's date-range fields, not its numbers.</b> It
 * takes date-RANGE operands, so a starred number field draws {@code MVK_NO_DATE_RANGE} before the duplicate arm
 * speaks — the exemption has to be measured on a starred repetition list of date ranges. Its containment row still
 * answers on a group pair, because the duplicate arms run AHEAD of the operand-kind bar:
 * {@code DateRangesOverlap(ShippingAddress, ShippingAddress/City)} reports {@code MVK_DUPLICATE_PARAM2} rather
 * than the {@code MVK_NO_GROUPS_ALLOWED} that same group draws on its own.
 */
class EntityListQuantifierDuplicateSiblingLawsTest {

    /**
     * All nine members of {@code Quantifier}. {@code AllFieldsFilled} is here rather than excluded as
     * "already measured": the carrier sweep takes three of its arms and not the both-starred containment
     * one, so excluding it would leave that arm's published claim resting on the other eight.
     */
    private static final String[] SIBLINGS = {
        "AllFieldsFilled",
        "NoFieldFilled",
        "AtLeastOneFieldFilled",
        "NotAllFieldsFilled",
        "FieldsNotCollectivelyFilled",
        "NotExactlyOneFieldFilled",
        "MoreThanOneFieldFilled",
        "FieldValuesNotUnique",
        "DateRangesOverlap",
    };

    private static String verdict(String condition) {
        var result = KernelLaws.checkModel(Fixtures.orderJson(), "/Order", "../Quantity",
                "FieldFilled(Quantity) And " + condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void everyFieldListQuantifierAnswersTheDuplicateArmsLikeAllFieldsFilled() {
        var actual = new LinkedHashMap<String, String>();
        for (String op : SIBLINGS) {
            actual.put(op + " | direct duplicate", verdict(op + "(Quantity, Quantity)"));
            actual.put(op + " | group beside its own field",
                    verdict(op + "(ShippingAddress, ShippingAddress/City)"));
            if (!op.equals("DateRangesOverlap")) {
                actual.put(op + " | STARRED pair", verdict(op + "(Items*/Count, Items*/Count)"));
                // Containment with the star on BOTH sides — the boundary the star does not buy.
                actual.put(op + " | starred group beside its own starred field",
                        verdict(op + "(Items*, Items*/Count)"));
            }
        }

        var expected = new LinkedHashMap<String, String>();
        for (String op : SIBLINGS) {
            expected.put(op + " | direct duplicate", "MVK_DUPLICATE_PARAM1");
            expected.put(op + " | group beside its own field", "MVK_DUPLICATE_PARAM2");
            if (!op.equals("DateRangesOverlap")) {
                expected.put(op + " | STARRED pair", KernelLaws.ADMITTED);
                expected.put(op + " | starred group beside its own starred field", "MVK_DUPLICATE_PARAM2");
            }
        }

        // The two arms whose operand must be a date range rather than the loop's number field.
        expected.put("DateRangesOverlap | STARRED pair", KernelLaws.ADMITTED);
        actual.put("DateRangesOverlap | STARRED pair",
                verdict("DateRangesOverlap(Items*/ItemCoverage, Items*/ItemCoverage)"));
        expected.put("DateRangesOverlap | starred group beside its own starred field", "MVK_DUPLICATE_PARAM2");
        actual.put("DateRangesOverlap | starred group beside its own starred field",
                verdict("DateRangesOverlap(Items*, Items*/ItemCoverage)"));

        assertEquals(expected, actual, "the family's duplicate arms are keyed on the operand list, not the "
                + "keyword — a cell that moved is the kernel disagreeing with every catalog entry that says so");
    }

}
