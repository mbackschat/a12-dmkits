package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Which iteration levels a SELECTED operand contributes to the negative-condition guard (a12-kernel-lean
 * SPEC-2026-10-07-03). Kernel 30.8.1's {@code IterationNotValidVisitor} judges each iterated level separately and
 * asks two kinds of question, measured apart here:
 * <ul>
 * <li>a composite's: an {@code Or} is admitted when every child references the level and is admitted, an
 * {@code And} when one child does — so a selection's references decide an {@code Or}, and the parent above the
 * selected level and a field-valued key both still reference;</li>
 * <li>a comparison leaf's: a zero comparison over an aggregate whose every field iterates is refused, and so is a
 * number comparison over one mixing iterating and fixed fields — for every aggregate but {@code SumOfProducts}.</li>
 * </ul>
 * The leaf verdicts are measured on bare comparisons, because an admitting {@code And} sibling absorbs them. The
 * {@code isIteration} they ask follows a field-valued key before the selected path, which only the warehouse model
 * can show: there the key sits in a repeatable request while the keyed bins sit under a group that does not repeat,
 * so the key is the operand's only iterating reference.
 *
 * <p>Own domain (a store's depots and a warehouse's keyed bins).
 */
class SelectedIterationGuardLawsTest {

    private static final String NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION";

    /** Each depot keys its own bins, so a selection's parent is the iterated depot. */
    private static final String DEPOTS = DocumentModelDraft.named("store-selected-guard").locale("en_US")
            .rootGroup("Store", s -> s
                    .stringField("Ref", f -> { })
                    .numberField("Budget", n -> n.fractionDigits(0))
                    .group("Depots", d -> d.repeatable(3)
                            .stringField("Wanted", f -> { })
                            .stringField("Note", f -> { })
                            .group("Bins", b -> b.repeatable(4).indexField("BinCode")
                                    .stringField("BinCode", f -> { })
                                    .numberField("Stock", n -> n.fractionDigits(0))
                                    .group("Shelf", sh -> sh
                                            .numberField("Aisle", n -> n.fractionDigits(0))
                                            .numberField("Level", n -> n.fractionDigits(0)))
                                    .group("Slots", sl -> sl.repeatable(3)
                                            .numberField("Qty", n -> n.fractionDigits(0))))))
            .build().toJson();

    /** One warehouse keys its bins, and a repeatable request names the bin it wants. */
    private static final String WAREHOUSE = DocumentModelDraft.named("warehouse-selected-guard").locale("en_US")
            .rootGroup("Store", s -> s
                    .stringField("Ref", f -> { })
                    .group("Requests", r -> r.repeatable(3)
                            .stringField("Wanted", f -> { })
                            .stringField("Note", f -> { }))
                    .group("Warehouse", w -> w
                            .group("Bins", b -> b.repeatable(4).indexField("BinCode")
                                    .stringField("BinCode", f -> { })
                                    .group("Slots", sl -> sl.repeatable(3)
                                            .numberField("Qty", n -> n.fractionDigits(0))
                                            .numberField("Weight", n -> n.fractionDigits(0))))))
            .build().toJson();

    private static String inDepot(String condition) {
        return KernelLaws.verdictModel(DEPOTS, "/Store/Depots", "../Note", "FieldFilled(Note) " + condition);
    }

    private static String inRequest(String condition) {
        return KernelLaws.verdictModel(WAREHOUSE, "/Store/Requests", "../Note", "FieldFilled(Note) " + condition);
    }

    /** A bare comparison leaf in a request, reporting on the key it reads. */
    private static String requestLeaf(String comparison) {
        return KernelLaws.verdictModel(WAREHOUSE, "/Store/Requests", "../Wanted", comparison);
    }

    @Test
    void aSelectionStillReferencesItsParentLevel() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group sum above zero — control", inDepot("Or Sum(Bins/Shelf For Wanted) > 0"));
        actual.put("a group sum equal to zero", inDepot("Or Sum(Bins/Shelf For Wanted) == 0"));
        actual.put("at least zero", inDepot("Or Sum(Bins/Shelf For Wanted) >= 0"));
        actual.put("at most zero", inDepot("Or Sum(Bins/Shelf For Wanted) <= 0"));
        actual.put("not zero", inDepot("Or Sum(Bins/Shelf For Wanted) != 0"));
        actual.put("below zero", inDepot("Or Sum(Bins/Shelf For Wanted) < 0"));
        actual.put("equal to five", inDepot("Or Sum(Bins/Shelf For Wanted) == 5"));
        actual.put("equal to zero beside an admitting And", inDepot("And Sum(Bins/Shelf For Wanted) == 0"));
        actual.put("equal to zero, bare", KernelLaws.verdictModel(DEPOTS, "/Store/Depots", "../Wanted",
                "Sum(Bins/Shelf For Wanted) == 0"));
        actual.put("a literal key, equal to zero", inDepot("Or Sum(Bins/Shelf For \"a\") == 0"));
        actual.put("a literal key, above zero", inDepot("Or Sum(Bins/Shelf For \"a\") > 0"));
        actual.put("a starred list under the key, equal to zero", inDepot("Or Sum(Bins/Slots*/Qty For Wanted) == 0"));
        actual.put("a starred list under the key, above zero", inDepot("Or Sum(Bins/Slots*/Qty For Wanted) > 0"));
        actual.put("a keyed field, equal to zero", inDepot("Or [Bins/Stock For Wanted] == 0"));
        actual.put("a keyed field, above zero", inDepot("Or [Bins/Stock For Wanted] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group sum above zero — control", KernelLaws.ADMITTED);
        expected.put("a group sum equal to zero", NEGATIVE);
        expected.put("at least zero", NEGATIVE);
        expected.put("at most zero", NEGATIVE);
        expected.put("not zero", KernelLaws.ADMITTED);
        expected.put("below zero", KernelLaws.ADMITTED);
        expected.put("equal to five", KernelLaws.ADMITTED);
        expected.put("equal to zero beside an admitting And", KernelLaws.ADMITTED);
        expected.put("equal to zero, bare", NEGATIVE);
        expected.put("a literal key, equal to zero", NEGATIVE);
        expected.put("a literal key, above zero", KernelLaws.ADMITTED);
        expected.put("a starred list under the key, equal to zero", NEGATIVE);
        expected.put("a starred list under the key, above zero", KernelLaws.ADMITTED);
        expected.put("a keyed field, equal to zero", NEGATIVE);
        expected.put("a keyed field, above zero", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a selection's parent level in the negative-condition guard");
    }

    @Test
    void aFieldValuedKeyIsTheSelectionsIteratingReference() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a dynamic key, above zero, in an Or",
                inRequest("Or Sum(/Store/Warehouse/Bins/Slots*/Qty For Wanted) > 0"));
        actual.put("a literal key, above zero, in an Or — control",
                inRequest("Or Sum(/Store/Warehouse/Bins/Slots*/Qty For \"a\") > 0"));
        actual.put("a dynamic key, equal to zero", requestLeaf("Sum(/Store/Warehouse/Bins/Slots*/Qty For Wanted) == 0"));
        actual.put("a literal key beside a read of the key, equal to zero — control",
                requestLeaf("FieldFilled(Wanted) And Sum(/Store/Warehouse/Bins/Slots*/Qty For \"a\") == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a dynamic key, above zero, in an Or", KernelLaws.ADMITTED);
        expected.put("a literal key, above zero, in an Or — control", NEGATIVE);
        expected.put("a dynamic key, equal to zero", NEGATIVE);
        expected.put("a literal key beside a read of the key, equal to zero — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the key's level in the negative-condition guard");
    }

    /** An aggregate mixing the key's iterating selection with a fixed one, as a bare comparison leaf. */
    @Test
    void theMixedReferenceGateSkipsOnlySumOfProducts() {
        String dynamic = "/Store/Warehouse/Bins/Slots*/Qty For Wanted";
        String literal = "/Store/Warehouse/Bins/Slots*/Weight For \"a\"";
        String dynamicWeight = "/Store/Warehouse/Bins/Slots*/Weight For Wanted";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sum of two iterating selections — control", requestLeaf("Sum(" + dynamic + ", " + dynamicWeight + ") > 0"));
        actual.put("a mixed sum", requestLeaf("Sum(" + dynamic + ", " + literal + ") > 0"));
        actual.put("a mixed maximum", requestLeaf("MaxValue(" + dynamic + ", " + literal + ") > 0"));
        actual.put("a mixed product", requestLeaf("SumOfProducts(" + dynamic + ", " + literal + ") > 0"));
        actual.put("a mixed product, equal to zero", requestLeaf("SumOfProducts(" + dynamic + ", " + literal + ") == 0"));
        actual.put("an iterating product, equal to zero", requestLeaf("SumOfProducts(" + dynamic + ", " + dynamicWeight + ") == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sum of two iterating selections — control", KernelLaws.ADMITTED);
        expected.put("a mixed sum", NEGATIVE);
        expected.put("a mixed maximum", NEGATIVE);
        expected.put("a mixed product", KernelLaws.ADMITTED);
        // The zero gate asks whether EVERY field iterates, so a mixed product passes it as well.
        expected.put("a mixed product, equal to zero", KernelLaws.ADMITTED);
        expected.put("an iterating product, equal to zero", NEGATIVE);
        assertEquals(expected, actual, "the mixed-reference gate and its product exception");
    }
}
