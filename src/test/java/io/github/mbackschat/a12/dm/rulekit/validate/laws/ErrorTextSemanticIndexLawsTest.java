package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — the semantic index an error-text parameter may carry.
 *
 * <p>An error-message parameter may name a field with a semantic-index key, and A12 checks that key against
 * the rule's own CONDITION rather than against the model:
 * {@code FullChecks_ErrorTextParameterParser.checkFieldReferencedInConditionAndGetFirstId} asks
 * {@code refEntities.tryGetFor(fullName, semanticIndexSpec)} and, finding nothing, answers
 * {@code MVK_INDEX_FOR_ERROR_TEXT_INVALID} when a key was written and {@code INVALID_FIELD} when none was.
 *
 * <p><b>The two surfaces spell the keyword differently</b>, which is the first thing this grid establishes:
 * a condition writes {@code [Field For key]} through the EN operator catalog, while the error-text parameter
 * grammar carries the terminal {@code FOR: 'Fuer'} in its own lexer, with no locale to follow.
 *
 * <p>Own domain (an e-commerce fulfilment desk).
 */
class ErrorTextSemanticIndexLawsTest {

    private static final String REFUSED = "MVK_INDEX_FOR_ERROR_TEXT_INVALID";

    private static final String FULFILMENT = DocumentModelDraft.named("fulfilment-error-text-index")
            .locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .stringField("Reference", x -> { })
                    .stringField("LookupCode", x -> { })
                    .numberField("Total", x -> { })
                    .group("Shipments", s -> s.repeatable(10).indexField("TrackingNo")
                            .stringField("TrackingNo", x -> { })
                            .stringField("Carrier", x -> { })
                            .numberField("Weight", x -> { }))
                    .group("Parcels", p -> p.repeatable(10).indexField("ParcelNo")
                            .stringField("ParcelNo", x -> { })
                            .numberField("Volume", x -> { })))
            .build().toJson();

    /** Held constant so every row differs in the message alone: one indexed read, one literal key. */
    private static final String READS_AN_INDEXED_SHIPMENT =
            "FieldFilled(Reference) And [Shipments/Weight For \"T-1\"] > 0";

    @Test
    void whichSemanticIndexAnErrorTextParameterMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain parameter the condition reads — the control", reporting("$Reference$"));
        actual.put("the indexed field with the condition's OWN key", reporting("$Shipments/Weight For \"T-1\"$"));
        actual.put("the indexed field with a DIFFERENT literal key",
                reporting("$Shipments/Weight For \"T-9\"$"));
        actual.put("the indexed field with a FIELD key where the condition used a literal",
                reporting("$Shipments/Weight For LookupCode$"));
        actual.put("the indexed field with NO key at all", reporting("$Shipments/Weight$"));
        actual.put("a SIBLING field of the indexed group, the condition's key",
                reporting("$Shipments/TrackingNo For \"T-1\"$"));
        actual.put("a PLAIN sibling of the indexed group, the condition's key",
                reporting("$Shipments/Carrier For \"T-1\"$"));
        actual.put("a field of a group the condition never reads, with a key",
                reporting("$Parcels/Volume For \"P-1\"$"));
        actual.put("a key on a field that does not repeat", reporting("$Total For \"T-1\"$"));
        actual.put("a NESTED key on a field the condition reads",
                reporting("$Shipments/Weight For Shipments/TrackingNo For \"T-1\"$"));
        actual.put("a NESTED key on a field the condition does NOT read",
                reporting("$Parcels/Volume For Parcels/ParcelNo For \"P-1\"$"));
        actual.put("the GERMAN keyword in an en_US model", reporting("$Shipments/Weight Fuer \"T-1\"$"));
        actual.put("a plain parameter, on a condition with NO semantic index — the control",
                onAPlainCondition("$Reference$"));
        actual.put("a NESTED key, on a condition with NO semantic index",
                onAPlainCondition("$Shipments/Weight For Shipments/TrackingNo For \"T-1\"$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain parameter the condition reads — the control", KernelLaws.ADMITTED);
        expected.put("the indexed field with the condition's OWN key", KernelLaws.ADMITTED);
        expected.put("the indexed field with a DIFFERENT literal key", REFUSED);
        expected.put("the indexed field with a FIELD key where the condition used a literal", REFUSED);
        expected.put("the indexed field with NO key at all", "INVALID_FIELD");
        // THE DISCRIMINATING PAIR. The key does not bind the whole repetition: only the group's own INDEX
        // FIELD rides along with a key the condition bound, and a plain sibling of it is refused. A checker
        // reading the first row alone would admit every field of a bound group, which is the wrong law.
        expected.put("a SIBLING field of the indexed group, the condition's key", KernelLaws.ADMITTED);
        expected.put("a PLAIN sibling of the indexed group, the condition's key", REFUSED);
        expected.put("a field of a group the condition never reads, with a key", REFUSED);
        expected.put("a key on a field that does not repeat", REFUSED);
        // `checkSemanticIndex` runs as the KEY sub-rule completes, so it answers before `checkFieldRef`
        // reaches membership at all — which is what lets a checker report the nested shape without first
        // establishing that the field is read.
        expected.put("a NESTED key on a field the condition reads", "ILLEGAL_SEMANTIC_INDEX");
        expected.put("a NESTED key on a field the condition does NOT read", "ILLEGAL_SEMANTIC_INDEX");
        expected.put("the GERMAN keyword in an en_US model", "MVK_UNEXPECTED_TOKEN");
        // The axis every other nested row holds CONSTANT: those all sit on a condition that itself carries a
        // semantic index, so "chaining is refused" and "the condition's own index is what refuses it" are
        // indistinguishable across them. This row sits on the other side and settles which one holds.
        expected.put("a plain parameter, on a condition with NO semantic index — the control",
                KernelLaws.ADMITTED);
        expected.put("a NESTED key, on a condition with NO semantic index", "ILLEGAL_SEMANTIC_INDEX");
        assertEquals(expected, actual, "which semantic index an error-text parameter may carry");
    }

    private static String reporting(String text) {
        return KernelLaws.messageVerdict(FULFILMENT, "/Fulfilment", "../Reference",
                READS_AN_INDEXED_SHIPMENT, "en_US", text);
    }

    /** The same desk on a condition carrying no semantic index, so the parameter's key stands alone. */
    private static String onAPlainCondition(String text) {
        return KernelLaws.messageVerdict(FULFILMENT, "/Fulfilment", "../Reference",
                "FieldFilled(Reference)", "en_US", text);
    }
}
