package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §11 [KF466] — the below-star placement on every other operand kind: a level left unstarred
 * below the first star is a per-row read, so a rule group the error field lies outside draws containment and the
 * one it lies in draws the missing star.
 *
 * <p>{@link PartialStarContainmentLawsTest} measures Number, group and String carriers. a12-kernel-lean reported
 * the same placement on the temporal, DateRange, Boolean, Enumeration, value-list and {@code SumOfProducts}
 * carriers, on a group under a filled-field carrier and on a filtered star ({@code SPEC-2026-09-25-02}, their
 * report); this measures each here, on a fulfilment sheet whose {@code Parcels} carries one field of each kind.
 * Every carrier's fully starred row is its control, so a class the carrier draws of its own cannot pass for the
 * placement.
 *
 * <p>Own domain (an e-commerce fulfilment sheet).
 */
class BelowStarPlacementKindsLawsTest {

    private static final String NOT_IN_RULEGROUP = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";

    private static final String SHEET = DocumentModelDraft.named("fulfilment-kinds").locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .group("Summary", s -> s.numberField("Result", n -> n.fractionDigits(0)))
                    .group("Ledger", l -> l
                            .numberField("Total", n -> n.fractionDigits(0))
                            .stringField("LedgerCode", s -> { })
                            .enumField("LedgerService", e -> e.value("STANDARD").value("EXPRESS"))
                            .dateField("LedgerDate", d -> d.format("dd.MM.yyyy"))
                            .dateRangeField("Window", d -> d.format("dd.MM.yyyy").separator("-"))
                            .dateRangeField("Season", d -> d.format("dd.MM").separator("-")))
                    .group("Shipments", sh -> sh.repeatable(2)
                            .group("Parcels", p -> p.repeatable(2)
                                    .numberField("Weight", n -> n.fractionDigits(0))
                                    .numberField("Quantity", n -> n.fractionDigits(0))
                                    .stringField("Label", s -> { })
                                    .dateField("ShippedOn", d -> d.format("dd.MM.yyyy"))
                                    .dateRangeField("Cover", d -> d.format("dd.MM.yyyy").separator("-"))
                                    .dateRangeField("Peak", d -> d.format("dd.MM").separator("-"))
                                    .field("Fragile", FieldKind.BOOLEAN)
                                    .enumField("Service", e -> e.value("STANDARD").value("EXPRESS"))
                                    .group("Seal", se -> se.stringField("SealCode", s -> { })))))
            .build().toJson();

    private static final String LEDGER = "/Fulfilment/Ledger";
    private static final String SHIPMENTS = "/Fulfilment/Shipments";
    private static final String SUMMARY = "/Fulfilment/Summary";
    private static final String GUARD = "FieldFilled(/Fulfilment/Summary/Result) And ";

    /** Each carrier with `%s` for the list prefix, and the class its fully starred control draws. */
    private static final Map<String, String> CARRIERS = carriers();

    private static Map<String, String> carriers() {
        Map<String, String> carriers = new LinkedHashMap<>();
        carriers.put("FirstFilledValue(%s/Label) == \"x\"", KernelLaws.ADMITTED);
        carriers.put("FieldValuesNotUnique(%s/ShippedOn)", KernelLaws.ADMITTED);
        carriers.put("NumberOfDifferentValues(%s/ShippedOn) < 2", KernelLaws.ADMITTED);
        carriers.put("MaxValue(%s/ShippedOn) == [/Fulfilment/Ledger/LedgerDate]", KernelLaws.ADMITTED);
        carriers.put("MinValue(%s/ShippedOn) == [/Fulfilment/Ledger/LedgerDate]", KernelLaws.ADMITTED);
        carriers.put("DateRangesOverlap(/Fulfilment/Ledger/Window, %s/Cover)", KernelLaws.ADMITTED);
        carriers.put("AtLeastOneDateRangeOverlaps(/Fulfilment/Ledger/Window In %s/Cover)", KernelLaws.ADMITTED);
        carriers.put("DateRangesOverlap(/Fulfilment/Ledger/Season, %s/Peak)", KernelLaws.ADMITTED);
        carriers.put("AtLeastOneDateRangeOverlaps(/Fulfilment/Ledger/Season In %s/Peak)", KernelLaws.ADMITTED);
        carriers.put("NumberOfValueInFields(True In %s/Fragile) > 0", KernelLaws.ADMITTED);
        carriers.put("NumberOfValueInFields(\"x\" In %s/Label) > 0", KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(%s/Service In \"STANDARD\")", KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(%s/Label, /Fulfilment/Ledger/LedgerCode In \"x\")",
                KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(%s/Label In /Fulfilment/Ledger/LedgerCode)",
                KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(%s/Weight In /Fulfilment/Ledger/Total)",
                KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(/Fulfilment/Ledger/LedgerCode In %s/Label)",
                KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldValueIncludedInValueList(/Fulfilment/Ledger/LedgerService In %s/Service)",
                KernelLaws.ADMITTED);
        carriers.put("NumberOfFilledFields(%s/Seal) > 0", KernelLaws.ADMITTED);
        carriers.put("AtLeastOneFieldFilled(%s/Seal)", KernelLaws.ADMITTED);
        carriers.put("Sum(%s/Weight Having ([/Fulfilment/Shipments/Parcels/Quantity] == "
                + "[/Fulfilment/Shipments/Parcels/Weight])) > 0", KernelLaws.ADMITTED);
        carriers.put("NumberOfFilledFields(%s/Weight Having ([/Fulfilment/Shipments/Parcels/Quantity] == "
                + "[/Fulfilment/Shipments/Parcels/Weight])) > 0", KernelLaws.ADMITTED);
        // Its own bar refuses a star above the lowest level, so the fully starred control is not admitted.
        carriers.put("SumOfProducts(%s/Weight, %s/Quantity) > 0", "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        return carriers;
    }

    @Test
    void everyOtherKindPlacesALevelUnstarredBelowTheFirstStar() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        CARRIERS.forEach((carrier, control) -> {
            String below = carrier.replace("%s", "/Fulfilment/Shipments*/Parcels");
            String every = carrier.replace("%s", "/Fulfilment/Shipments*/Parcels*");
            for (String group : List.of(SHIPMENTS, LEDGER)) {
                String from = group.substring(group.lastIndexOf('/') + 1);
                actual.put(from + " | " + below, verdict(group, below));
                expected.put(from + " | " + below, NOT_IN_RULEGROUP);
                actual.put(from + " | " + every + " — control", verdict(group, every));
                expected.put(from + " | " + every + " — control", control);
            }
            actual.put("Summary | " + below, verdict(SUMMARY, below));
            expected.put("Summary | " + below, NO_WILDCARD);
        });
        assertEquals(expected, actual, "the placed class on every other operand kind");
    }

    /** A rule declared in [group] reporting on `Summary/Result`, whose condition reads [read]. */
    private static String verdict(String group, String read) {
        return KernelLaws.verdictModel(SHEET, group, "../../Summary/Result", GUARD + read);
    }
}
