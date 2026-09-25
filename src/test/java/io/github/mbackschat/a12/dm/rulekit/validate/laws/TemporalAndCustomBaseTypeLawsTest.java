package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS KF468 — which base-type class a TIME, DATE_TIME, date-fragment and CUSTOM declaration falls
 * into, on the gates that compare two base types.
 *
 * <p>Read from source first: the model mapper builds all four temporal kinds as one date type, whose
 * {@code matchBaseType} admits any other, and a custom declaration shares the string's base type. So a date
 * fragment should pass an ordering comparison beside another fragment of its format, and a time, a date-time
 * and a custom field should draw the same class a date or a text field does where a gate compares kinds.
 * Each row holds every other variable fixed: the same model, the same rule, one operand changed.
 *
 * <p>Own domain (a parcel shipment).
 */
class TemporalAndCustomBaseTypeLawsTest {

    private static final String SHIPMENT = DocumentModelDraft.named("parcel-shipment").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .stringField("Reference", f -> { })
                    .stringField("Carrier", f -> { })
                    .stringField("Service", f -> { })
                    .numberField("Weight", n -> n.fractionDigits(2))
                    .dateField("ShipDate", d -> d.format("dd.MM.yyyy"))
                    .dateFragmentField("DispatchMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("ReturnMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .timeField("PickupTime", t -> t.format("HH:mm:ss"))
                    .timeField("DropoffTime", t -> t.format("HH:mm:ss"))
                    .dateTimeField("ShippedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("DeliveredAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .customField("Tracking", f -> f.type("TrackingCode"))
                    .field("Insured", FieldKind.BOOLEAN)
                    .field("Signed", FieldKind.CONFIRM)
                    .enumField("Priority", e -> e.value("STANDARD").value("EXPRESS"))
                    .dateRangeField("Window", d -> d.format("dd.MM.yyyy").separator("-")))
            .build().toJson();

    /** Names the error field, so an admitted row is admitted rather than refused for not reading it. */
    private static String verdict(String condition) {
        return KernelLaws.verdictModel(SHIPMENT, "/Shipment", "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    @Test
    void whichBaseTypeClassEachTemporalAndCustomKindFallsInto() {
        Map<String, String> rows = new LinkedHashMap<>();
        // An ordering comparison between two kinds of one class, each against a same-kind twin of its format.
        rows.put("[DispatchMonth] < [ReturnMonth]", KernelLaws.ADMITTED);
        rows.put("[PickupTime] < [DropoffTime]", KernelLaws.ADMITTED);
        rows.put("[ShippedAt] < [DeliveredAt]", KernelLaws.ADMITTED);
        // The ordering and tolerance gates against a side of another class.
        rows.put("[DispatchMonth] < [Carrier]", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("[PickupTime] < [Carrier]", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("[ShippedAt] DiffersWithToleranceRange1 [Weight]", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        rows.put("[Tracking] < 1", "MVK_INVALID_TYPE_FOR_COMPARISON");
        // The two boolean kinds against a temporal side.
        rows.put("[Signed] == [DispatchMonth]", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Insured] == [PickupTime]", "MVK_INVALID_COMPARE_TO_YESNO");
        // The value-list quantifiers' base-type gate, which runs ahead of their kind gate: two kinds of one
        // class pass it and meet the kind gate, as two dates do; two classes draw the base-type class.
        rows.put("AtLeastOneFieldValueIncludedInValueList(ShipDate, DispatchMonth In \"x\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        rows.put("AtLeastOneFieldValueIncludedInValueList(PickupTime, ShippedAt In \"x\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        rows.put("AtLeastOneFieldValueIncludedInValueList(Carrier, Tracking In \"x\")", KernelLaws.ADMITTED);
        rows.put("AtLeastOneFieldValueIncludedInValueList(Carrier In Service, PickupTime)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        rows.put("FieldValueIncludedInValueList(Carrier, [PickupTime])", "MVK_VARYING_TYPES_NOT_ALLOWED");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "the base-type class of each temporal and custom kind");
    }

    /**
     * The value-list quantifier's base-type gate keeps every two classes apart, whichever comes first and on
     * either side of the {@code In}: the two token families (text and enumeration), the two boolean kinds, and a
     * number, a date or a date range beside another class. Rows a12-kernel-lean measured on its own model
     * ({@code SPEC-2026-09-25-05}), taken here on ours; the rival readings — the token families or the boolean
     * kinds merged, or the kind gate asked first — predict an admission or
     * {@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED} on these rows. The two controls hold two text fields in the
     * same two shapes.
     */
    @Test
    void theValueListQuantifiersBaseTypeGateKeepsEveryTwoClassesApart() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String pair : List.of("Priority, Carrier", "Carrier, Priority", "Insured, Signed", "Signed, Insured",
                "Weight, ShipDate", "Weight, Carrier", "Window, Carrier", "Insured, ShipDate")) {
            rows.put("AtLeastOneFieldValueIncludedInValueList(" + pair + " In \"x\")",
                    "MVK_VARYING_TYPES_NOT_ALLOWED");
        }
        for (String pair : List.of("Carrier In Priority", "Priority In Carrier", "Priority In Weight",
                "Weight In Carrier")) {
            rows.put("AtLeastOneFieldValueIncludedInValueList(" + pair + ")", "MVK_VARYING_TYPES_NOT_ALLOWED");
        }
        rows.put("AtLeastOneFieldValueIncludedInValueList(Carrier, Service In \"x\")", KernelLaws.ADMITTED);
        rows.put("AtLeastOneFieldValueIncludedInValueList(Carrier In Service)", KernelLaws.ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "which pairs the value-list quantifier's base-type gate keeps apart");
    }

    /**
     * Every other arm a checker reaches once it reads these kinds by that class, each measured on the kind
     * itself rather than carried over from the date or text field it now shares a class with: an ordering
     * against a text literal, a tolerance over a custom field, and the two boolean kinds against a custom or
     * a time field. The date row is the control the transfer would otherwise rest on.
     */
    @Test
    void eachArmAReclassifiedKindReachesIsMeasuredOnTheKindItself() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("[ShipDate] < \"x\"", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("[PickupTime] < \"x\"", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("[DispatchMonth] < \"x\"", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("[Tracking] DiffersWithToleranceRange1 1", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        rows.put("[Signed] == [Tracking]", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Insured] == [Tracking]", "MVK_INVALID_COMPARE_TO_YESNO");
        rows.put("[Signed] == [PickupTime]", "MVK_INVALID_COMPARE_TO_YES");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "each arm a reclassified kind reaches");
    }
}
