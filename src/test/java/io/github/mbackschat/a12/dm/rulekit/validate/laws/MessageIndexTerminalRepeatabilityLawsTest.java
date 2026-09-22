package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §13 — what the {@code $index(…)$} terminal's REPEATABILITY gate needs, and what it
 * pre-empts.
 *
 * <p>{@link RuleMessageIndexTerminalLawsTest} measured each of this gate's two refusals against a condition
 * that referenced the index field. Three questions it leaves open decide whether a checker can reproduce the
 * gate at all, and each is answered here by one row:
 *
 * <ul>
 * <li><b>Does the gate need a condition reference?</b> The kernel's own list is the condition's index-field
 *     references filtered by short name, so an empty list would mean no refusal — but "the declaration is
 *     refused" and "a reference to it is refused" are indistinguishable from the measured rows alone.</li>
 * <li><b>Does the gate pre-empt the missing-suffix class?</b> Both were measured on shapes where only one
 *     could fire, so their ORDER is a source reading until a shape reaches both.</li>
 * <li><b>Is {@code INVALID_INDEX_FIELD_REP} reachable, and on what?</b> A checker that reproduces the two
 *     measured refusals without knowing this one's trigger names the wrong class on a MIXED shape — the
 *     referenced index field repeatable where the error entity's is not.</li>
 * </ul>
 *
 * <p>Own domain (warehouse fulfilment), and its own fixture: the mixed row needs a second group declaring an
 * index field of the same SHORT NAME off the error entity's path, which would make every row of the shared
 * claims model ambiguous.
 */
class MessageIndexTerminalRepeatabilityLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String PALLETS = "/Warehouse/Pallets";
    private static final String BAYS = "/Warehouse/Bays";

    /**
     * Two indexed groups the error entity can sit under — one repeatable with a String index, one not, with
     * an Enumeration index of TWO values — plus a repeatable group off both paths whose index field carries
     * the same short name as the non-repeatable one's.
     */
    private static final String WAREHOUSE = warehouse(false);

    /** The same model with the non-repeatable group's index field on the ADMITTED side of that gate. */
    private static final String CLEAN_BAY_INDEX = warehouse(true);

    private static String warehouse(boolean cleanBayIndex) {
        return DocumentModelDraft.named("warehouse-index").locale("en_US")
            .rootGroup("Warehouse", w -> w
                    .stringField("Reference", f -> { })
                    .group("Pallets", p -> p.repeatable(20).indexField("Slot")
                            .stringField("Slot", f -> { })
                            .stringField("Weight", f -> { }))
                    .group("Bays", b -> b.indexField("Tier")
                            .enumField("Tier", e -> {
                                if (cleanBayIndex) {
                                    e.value("BASIC").sValue("BASIC");
                                } else {
                                    e.value("BASIC").value("PREMIUM");
                                }
                            })
                            .stringField("Note", f -> { }))
                    .group("Racks", r -> r.repeatable(9).indexField("Tier")
                            .enumField("Tier", e -> e.value("BASIC").value("PREMIUM"))
                            .stringField("Label", f -> { })))
            .build().toJson();
    }

    private static final BoundModel M = BoundModel.ofJson(WAREHOUSE);

    /** A plain per-row read of the repeatable group's own index field. */
    private static final String READS_SLOT = render(Dsl.fieldFilled(M.field("/Warehouse/Pallets/Weight"))
            .and(Dsl.fieldFilled(M.field("/Warehouse/Pallets/Slot"))));

    /** The same index field read through a STAR, which is the measured `_REP_ITER` shape. */
    private static final String READS_SLOT_STARRED = render(Dsl.fieldFilled(M.field("/Warehouse/Pallets/Weight"))
            .and(Dsl.atLeastOneFieldFilled(M.flatten("/Warehouse/Pallets*/Slot"))));

    /** In the NON-repeatable group, reading its own Enumeration index field. */
    private static final String READS_TIER = render(Dsl.fieldFilled(M.field("/Warehouse/Bays/Note"))
            .and(Dsl.fieldFilled(M.field("/Warehouse/Bays/Tier"))));

    /** The same group, reading NOTHING that is an index field — the gate's own precondition, moved. */
    private static final String READS_NOTE = render(Dsl.fieldFilled(M.field("/Warehouse/Bays/Note")));

    /** …and reading the REPEATABLE group's same-short-named index field instead of its own. */
    private static final String READS_RACK_TIER = render(Dsl.fieldFilled(M.field("/Warehouse/Bays/Note"))
            .and(Dsl.atLeastOneFieldFilled(M.flatten("/Warehouse/Racks*/Tier"))));

    private static String render(Condition condition) {
        return KernelLaws.render(condition);
    }

    private static String inPallet(String condition, String message) {
        return KernelLaws.messageVerdict(WAREHOUSE, PALLETS, "../Weight", condition, "en_US", message);
    }

    private static String inBay(String condition, String message) {
        return KernelLaws.messageVerdict(WAREHOUSE, BAYS, "../Note", condition, "en_US", message);
    }

    /** The same rule on the model whose non-repeatable index field passes the Enumeration gate. */
    private static String inCleanBay(String condition, String message) {
        return KernelLaws.messageVerdict(CLEAN_BAY_INDEX, BAYS, "../Note", condition, "en_US", message);
    }

    @Test
    void whatTheRepeatabilityGateNeedsAndWhatItPreEmpts() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain reference and a suffix — the control", inPallet(READS_SLOT, "row $index(Slot).value$"));
        actual.put("a plain reference and NO suffix", inPallet(READS_SLOT, "row $index(Slot)$"));
        actual.put("a STARRED reference and a suffix", inPallet(READS_SLOT_STARRED, "row $index(Slot).value$"));
        actual.put("a STARRED reference and NO suffix", inPallet(READS_SLOT_STARRED, "row $index(Slot)$"));
        actual.put("a STARRED reference, parameter-free — the control", inPallet(READS_SLOT_STARRED, "row"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain reference and a suffix — the control", ADMITTED);
        expected.put("a plain reference and NO suffix", "INVALID_INDEX_FIELD_PARAMETER");
        expected.put("a STARRED reference and a suffix", "INVALID_INDEX_FIELD_REP_ITER");
        // THE ORDER ROW: both gates would fire, and the repeatability one answers — so a checker reporting
        // the missing suffix has to have reproduced this gate first, or it names the wrong class here.
        expected.put("a STARRED reference and NO suffix", "INVALID_INDEX_FIELD_REP_ITER");
        expected.put("a STARRED reference, parameter-free — the control", ADMITTED);
        assertEquals(expected, actual, "the repeatability gate runs before the missing-suffix one");
    }

    /**
     * The non-repeatable branch reads the error entity's OWN index-field declaration, not the condition.
     *
     * <p>Both refusals the shared class measured sat on a condition that referenced the index field, which
     * left the gate's input undecided — and the source's own list is the condition's references, so the
     * reading the code invites is the one row 2 falsifies.
     */
    @Test
    void theNonRepeatableGateReadsTheDeclarationAndNotTheConditionsReferences() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the index field REFERENCED by the condition", inBay(READS_TIER, "row $index(Tier).value$"));
        actual.put("the same declaration, NOT referenced", inBay(READS_NOTE, "row $index(Tier).value$"));
        actual.put("not referenced, parameter-free — the control", inBay(READS_NOTE, "row"));
        actual.put("the DECLARATION on its admitted side", inCleanBay(READS_NOTE, "row $index(Tier).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the index field REFERENCED by the condition", "INVALID_INDEX_FIELD_NONREP_ENUM");
        // The row that decides it: dropping the condition's reference changes nothing, so the gate is about
        // the declaration the parameter NAMED. A checker reproducing it needs no condition reading at all.
        expected.put("the same declaration, NOT referenced", "INVALID_INDEX_FIELD_NONREP_ENUM");
        expected.put("not referenced, parameter-free — the control", ADMITTED);
        // …and the same model with one value carrying its own S-value admits it, which is what makes the two
        // rows above the gate's refusal rather than the shape's.
        expected.put("the DECLARATION on its admitted side", ADMITTED);
        assertEquals(expected, actual, "the non-repeatable gate reads the named declaration");
    }

    /**
     * A same-short-named index field in a REPEATABLE group, off the error entity's path.
     *
     * <p>This is the shape a checker reproducing the two measured refusals can name a wrong class on, so the
     * rows establish which class each side answers with — and the pair differs only in whether the error
     * entity's own index field passes its Enumeration gate.
     */
    @Test
    void aSameNamedIndexFieldOfARepeatableGroupIsTheMixedShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("beside an OFFENDING declaration of the error entity's own",
                inBay(READS_RACK_TIER, "row $index(Tier).value$"));
        actual.put("beside an ADMITTED one", inCleanBay(READS_RACK_TIER, "row $index(Tier).value$"));
        actual.put("the mixed shape, parameter-free — the control", inCleanBay(READS_RACK_TIER, "row"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("beside an OFFENDING declaration of the error entity's own", "INVALID_INDEX_FIELD_NONREP_ENUM");
        expected.put("beside an ADMITTED one", "INVALID_INDEX_FIELD_REP");
        expected.put("the mixed shape, parameter-free — the control", ADMITTED);
        assertEquals(expected, actual, "a same-named repeatable index field has a class of its own");
    }
}
