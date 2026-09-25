package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation.Alternative;

/**
 * KERNEL-FINDINGS KF473 — the field-value read {@code [ … ]} asks four gates of the one entity it names, in a
 * fixed order and wherever the bracket stands.
 *
 * <p>{@code CheckFeldWertOpImpl.check} runs {@code checkIsField} ({@code MVK_NO_GROUPS_ALLOWED}),
 * {@code checkNoWildCards} ({@code MVK_NO_WILDCARDS_ALLOWED}), {@code checkIsValueValidation}
 * ({@code MVK_INVALID_RAW_TYPE}) and the partially-known-date test ({@code MVK_INVALID_DATE_TYPE}) once the
 * bracket closes. The rival reading, that a starred read is refused by the comparison or operator around it,
 * predicts a class that varies with the carrier; every carrier below draws the star's one class, and a starred
 * read compared with a string literal draws it ahead of any kind gate. A second rival, that the star gate asks
 * only an UNBOUND level, is what the read from inside {@code Lines} discriminates: the star on the level the
 * rule iterates is refused too, because the gate asks the spelling.
 *
 * <p>The reader's own classes answer first: an unstarred repeatable group, a star on a non-repeatable level, an
 * unresolvable name. So does containment, when the target lies outside the declaring group — which is what the
 * read from inside {@code Shipments} separates from the star gate: the declaring group binds the starred
 * level's parent, yet the rule's iteration does not. The strings are the subject: each is a spelling the typed
 * builder does not produce.
 *
 * <p>Own domain (the shared e-commerce basket, and a fulfilment sheet with repeating shipments and parcels).
 */
class FieldValueReadOperandLawsTest {

    private static final String BASKET = ModelDrafts.basket().toJson();

    private static final String SHEET = DocumentModelDraft.named("fulfilment-values").locale("en_US")
            .rootGroup("Fulfilment", f -> f
                    .group("Summary", s -> s.numberField("Result", n -> n.fractionDigits(0)))
                    .group("Ledger", l -> l.numberField("Total", n -> n.fractionDigits(0))
                            .stringField("Note", t -> t.lineBreaks().noValueValidation())
                            .dateField("Partial", d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                            .dateField("Due", d -> d.format("yyyy-MM-dd")))
                    .group("Shipments", sh -> sh.repeatable(2)
                            .group("Parcels", p -> p.repeatable(2)
                                    .numberField("Weight", n -> n.fractionDigits(0))
                                    .numberField("Quantity", n -> n.fractionDigits(0))
                                    .stringField("Label", t -> { }))))
            .build().toJson();

    private static final String EVERY = "/Fulfilment/Shipments*/Parcels*";

    @Test
    void aStarredReadIsTheStarsClassWhereverTheBracketStands() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String condition : List.of("[Total] == [Lines*/Qty]", "[Lines*/Qty] == [Total]",
                "[Total] == {[Lines*/Qty] + 1}", "[Total] == Abs([Lines*/Qty])", "[Lines*/Qty] > 0",
                "[Lines*/Qty] == \"x\"", "[Total] == [Lines*/Packaging/Weight]", "[Total] == [Fees]",
                "[Total] == [Lines*]", "[Total] == [Lines]", "[Total] == Sum(Lines*/Qty)")) {
            actual.put(condition, KernelLaws.verdictModel(BASKET, "/Basket", "../Total",
                    "FieldFilled(Total) And " + condition));
        }
        actual.put("from Lines: [Qty] == [/Basket/Lines*/Qty]", KernelLaws.verdictModel(BASKET, "/Basket/Lines",
                "../Qty", "FieldFilled(Qty) And [Qty] == [/Basket/Lines*/Qty]"));
        actual.put("computation: [Lines*/Qty]", computation("[Lines*/Qty]"));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(row -> expected.put(row, "MVK_NO_WILDCARDS_ALLOWED"));
        // `checkIsField` is the first gate, so a group draws its class even when it is starred.
        expected.put("[Total] == [Fees]", "MVK_NO_GROUPS_ALLOWED");
        expected.put("[Total] == [Lines*]", "MVK_NO_GROUPS_ALLOWED");
        // An unstarred repeatable group is refused by the reader, before the bracket closes.
        expected.put("[Total] == [Lines]", "MVK_NO_WILDCARD");
        // The control: the same list in an aggregate's operand, which reads it whole.
        expected.put("[Total] == Sum(Lines*/Qty)", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what a starred field-value read draws on each carrier");
    }

    @Test
    void theGatesAnswerInTheirOrderAfterTheReadersOwnClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String row : List.of(
                "Ledger | [" + EVERY + "/Weight] > 0",
                "Ledger | [/Fulfilment/Shipments/Parcels*/Weight] > 0",
                "Shipments | [Parcels*/Weight] > 0",
                "Summary | [" + EVERY + "/Weight] > 0",
                "Summary | [/Fulfilment/Shipments*/Parcels] > 0",
                "Summary | [" + EVERY + "] > 0",
                "Summary | [/Fulfilment/Ledger] > 0",
                "Summary | [/Fulfilment/Ledger/Total] > 0",
                "Summary | [/Fulfilment/Ledger/Note] == \"x\"",
                "Summary | [/Fulfilment/Ledger/Partial] == [/Fulfilment/Ledger/Due]",
                "Summary | [/Fulfilment/Ledger/Due] == [/Fulfilment/Ledger/Due]",
                "Summary | [/Fulfilment/Ledger/NoSuch] > 0",
                "Summary | [/Fulfilment/Ledger*/Total] > 0",
                "Summary | [/Fulfilment/Ledger/Total*] > 0",
                "Summary | [/Fulfilment/Ledger/Note] == [" + EVERY + "/Label]",
                "Summary | [" + EVERY + "/Label] == [/Fulfilment/Ledger/Note]",
                "Summary | [/Fulfilment/Ledger/Due] == [" + EVERY + "/Label]",
                "Summary | Sum(" + EVERY + "/Weight Having [/Fulfilment/Shipments/Parcels/Weight] > [" + EVERY
                        + "/Quantity]) > 0",
                "Summary | Sum(" + EVERY + "/Weight Having [/Fulfilment/Ledger] > 0) > 0")) {
            String[] cell = row.split(" \\| ", 2);
            actual.put(row, KernelLaws.verdictModel(SHEET, "/Fulfilment/" + cell[0], "../../Summary/Result",
                    "FieldFilled(/Fulfilment/Summary/Result) And " + cell[1]));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Ledger | [" + EVERY + "/Weight] > 0", "MVK_NO_WILDCARDS_ALLOWED");
        // Outside the declaring group, a read that leaves a repeatable level unstarred is containment's —
        // even from inside `Shipments`, whose level the declaring group binds and the rule's iteration does not.
        expected.put("Ledger | [/Fulfilment/Shipments/Parcels*/Weight] > 0", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        expected.put("Shipments | [Parcels*/Weight] > 0", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        expected.put("Summary | [" + EVERY + "/Weight] > 0", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("Summary | [/Fulfilment/Shipments*/Parcels] > 0", "MVK_NO_WILDCARD");
        expected.put("Summary | [" + EVERY + "] > 0", "MVK_NO_GROUPS_ALLOWED");
        expected.put("Summary | [/Fulfilment/Ledger] > 0", "MVK_NO_GROUPS_ALLOWED");
        expected.put("Summary | [/Fulfilment/Ledger/Total] > 0", KernelLaws.ADMITTED);
        expected.put("Summary | [/Fulfilment/Ledger/Note] == \"x\"", "MVK_INVALID_RAW_TYPE");
        expected.put("Summary | [/Fulfilment/Ledger/Partial] == [/Fulfilment/Ledger/Due]", "MVK_INVALID_DATE_TYPE");
        // The control for the date gate: a date of full precision, read the same way.
        expected.put("Summary | [/Fulfilment/Ledger/Due] == [/Fulfilment/Ledger/Due]", KernelLaws.ADMITTED);
        expected.put("Summary | [/Fulfilment/Ledger/NoSuch] > 0", "MVK_INVALID_ENTITY");
        expected.put("Summary | [/Fulfilment/Ledger*/Total] > 0", "MVK_INVALID_WILDCARD");
        expected.put("Summary | [/Fulfilment/Ledger/Total*] > 0", "MVK_INVALID_WILDCARD");
        // Each bracket is checked as it closes, so the left-hand read's class is the one A12 names.
        expected.put("Summary | [/Fulfilment/Ledger/Note] == [" + EVERY + "/Label]", "MVK_INVALID_RAW_TYPE");
        expected.put("Summary | [" + EVERY + "/Label] == [/Fulfilment/Ledger/Note]", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("Summary | [/Fulfilment/Ledger/Due] == [" + EVERY + "/Label]", "MVK_NO_WILDCARDS_ALLOWED");
        // In a filter's body: a star is refused as everywhere, and a group at its bracket — ahead of the
        // filter chain's own `MVK_NO_ITERATION_FOR_WILDCARD`, which waits for the whole filtered spec.
        expected.put("Summary | Sum(" + EVERY + "/Weight Having [/Fulfilment/Shipments/Parcels/Weight] > [" + EVERY
                + "/Quantity]) > 0", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("Summary | Sum(" + EVERY + "/Weight Having [/Fulfilment/Ledger] > 0) > 0",
                "MVK_NO_GROUPS_ALLOWED");
        assertEquals(expected, actual, "which class a field-value read draws, and in which order");
    }

    private static String computation(String operation) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath("/Basket").name("TotalCalc").computedFieldRelPath("../Total")
                .alternatives(List.of(new Alternative(null, operation)))
                .messages(List.of(en("x"), de("y")))
                .build();
        return KernelLaws.computationVerdict(BASKET, draft);
    }
}
