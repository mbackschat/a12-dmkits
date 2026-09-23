package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * {@code Length} takes a field or its keyed cell and nothing wider: a call or a concatenation inside it does not
 * parse. The keyed row is the one that matters, because the typed surface offers {@code length()} on a field
 * handle only, so a keyed {@code Length} is written as text here (DEV-API-GAPS DG71). KERNEL-FINDINGS KF387
 * records the same shape rule for the pattern operators.
 */
class LengthOperandShapeLawsTest {

    private static final String PARCELS_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.group("Parcels", p -> p.repeatable(5).indexField("Code")
                        .stringField("Code", f -> { })
                        .stringField("Label", f -> { }));
            }).build().toJson();

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(PARCELS_JSON, "/Order", "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    @Test
    void lengthTakesAFieldOrItsKeyedCell() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field — the control", verdict("Length(Reference) > 3"));
        actual.put("a keyed cell", verdict("Length(/Order/Parcels/Label For \"P1\") > 3"));
        actual.put("a call", verdict("Length(FieldValueAsString(Quantity)) > 3"));
        actual.put("a concatenation", verdict("Length([Reference] + \"-X\") > 3"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field — the control", KernelLaws.ADMITTED);
        expected.put("a keyed cell", KernelLaws.ADMITTED);
        expected.put("a call", "MVK_UNEXPECTED_TOKEN");
        expected.put("a concatenation", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual);
    }
}
