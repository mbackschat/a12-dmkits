package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS KF469 — which ONE class a comparison draws when two of its agreement arms would refuse it.
 *
 * <p>{@code CheckVergleichsBedingungImpl.check} asks its arms in a fixed order and each throws: the two
 * boolean kinds, then a date, then a date range, then a text or enumeration, then a number. Each row pairs
 * an earlier arm's refusal with a partner a later arm refuses too, so the verdict names the arm asked first;
 * a reader that judged each arm on its own would report both.
 *
 * <p>Own domain (a parcel shipment).
 */
class ComparisonArmOrderLawsTest {

    private static final String SHIPMENT = DocumentModelDraft.named("parcel-arm-order").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .stringField("Reference", f -> { })
                    .stringField("Carrier", f -> { })
                    .numberField("Weight", n -> n.fractionDigits(2))
                    .enumField("Priority", e -> e.value("EXPRESS").value("STANDARD"))
                    .dateField("ShipDate", d -> d.format("dd.MM.yyyy"))
                    .dateRangeField("DeliveryWindow", d -> d.format("dd.MM.yyyy").separator("-"))
                    .field("Insured", FieldKind.BOOLEAN)
                    .field("Signed", FieldKind.CONFIRM))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(SHIPMENT, "/Shipment", "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    @Test
    void theArmAskedFirstIsTheOneThatAnswers() {
        Map<String, String> rows = new LinkedHashMap<>();
        // The boolean arm, ahead of every later one.
        rows.put("[Signed] == [ShipDate]", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Insured] == [ShipDate]", "MVK_INVALID_COMPARE_TO_YESNO");
        rows.put("[Signed] == [DeliveryWindow]", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Insured] == [Carrier]", "MVK_INVALID_COMPARE_TO_YESNO");
        rows.put("[Signed] == [Priority]", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Insured] == [Weight]", "MVK_INVALID_COMPARE_TO_YESNO");
        // The date arm, ahead of the range, text, enumeration and number arms.
        rows.put("[ShipDate] == [DeliveryWindow]", "MVK_INVALID_COMPARE_TO_DATE");
        rows.put("[ShipDate] == [Priority]", "MVK_INVALID_COMPARE_TO_DATE");
        rows.put("[ShipDate] == [Weight]", "MVK_INVALID_COMPARE_TO_DATE");
        // The range arm, ahead of the text, enumeration and number arms.
        rows.put("[DeliveryWindow] == [Carrier]", "MVK_INVALID_COMPARE_TO_DATE_RANGE");
        rows.put("[DeliveryWindow] == [Priority]", "MVK_INVALID_COMPARE_TO_DATE_RANGE");
        rows.put("[DeliveryWindow] == [Weight]", "MVK_INVALID_COMPARE_TO_DATE_RANGE");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "the comparison arm asked first");
    }
}
