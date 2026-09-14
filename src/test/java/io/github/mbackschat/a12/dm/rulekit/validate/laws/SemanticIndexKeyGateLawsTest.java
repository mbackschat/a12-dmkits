package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — the two semantic-index gates that read the KEY itself: its declared type and where it
 * lives.
 *
 * <p>{@link SemanticIndexStructureLawsTest} measures every gate of this chain that needs no comparison of
 * declared types, and each of its rows holds one defect. The two gates left over both read the key against
 * the indexed group — {@code MVK_INDEX_TYPE_INCONSISTENT} compares its declared type with the index field's,
 * {@code MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX} asks whether it is read from inside the group being indexed —
 * and neither has a measured position in the chain, so a checker reproducing them would have to guess which
 * one a doubly-defective key draws.
 *
 * <p>Every containment row anchors the rule INSIDE the indexed group. Without that anchoring the key's read
 * iterates and {@code MVK_SEMANTIC_INDEX_INVALID_ITER} answers ahead of the whole chain — the pre-emption
 * {@code SemanticIndexStructureLawsTest.theIterationGateReportsFirst} locks — so the containment class is
 * unreachable on an unanchored rule.
 *
 * <p>Own domain (a parcel depot's delivery runs).
 */
class SemanticIndexKeyGateLawsTest {

    private static final String DEPOT = DocumentModelDraft.named("depot-index-key").locale("en_US")
            .rootGroup("Depot", d -> d
                    .stringField("Reference", f -> { })
                    .stringField("LookupKey", f -> { })
                    .stringField("RawNote", f -> f.lineBreaks().noValueValidation())
                    .numberField("Excess", f -> f.fractionDigits(0))
                    .booleanField("Insured", f -> { })
                    .dateField("ShippedOn", f -> f.format("dd.MM.yyyy"))
                    .timeField("PickupTime", f -> f.format("HH:mm:ss"))
                    .dateTimeField("ScheduledAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("BillingMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateRangeField("Window", f -> f.format("dd.MM.yyyy").separator("-"))
                    .confirmField("Acknowledged", f -> { })
                    .customField("Courier", f -> f.type("CourierCode"))
                    .enumField("Grade", e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH"))
                    .group("Runs", g -> g.repeatable(20).indexField("RunKey")
                            .stringField("RunKey", f -> { })
                            .stringField("RunNote", f -> { })
                            .stringField("RunRaw", f -> f.lineBreaks().noValueValidation())
                            .numberField("RunCount", f -> f.fractionDigits(0))
                            .numberField("RunLimit", f -> f.fractionDigits(2)))
                    // Keyed on an ENUMERATION, so the index field's own side of the type gate varies too —
                    // a relation measured in one direction only would not show that it is not symmetric.
                    .group("Shifts", g -> g.repeatable(10).indexField("ShiftGrade")
                            .enumField("ShiftGrade", e -> e.value("BRONZE").value("GOLD"))
                            .enumField("ShiftTier", e -> e.value("BRONZE").value("GOLD")
                                    .category("Band", "LOW", "HIGH"))
                            .stringField("ShiftLabel", f -> { })
                            .numberField("ShiftAmount", f -> f.fractionDigits(2)))
                    // A second string-keyed group, so an ANCHORED key can be measured against an index on a
                    // group that is neither the anchor nor the key's own.
                    .group("Legs", g -> g.repeatable(10).indexField("LegCode")
                            .stringField("LegCode", f -> { })
                            .numberField("LegWeight", f -> f.fractionDigits(2)))
                    // An indexed group one level BELOW a repetition, so a star can sit ABOVE the group the
                    // index selects in. Keyed on an enumeration for the same reason as `Shifts`: the category
                    // gate is only reachable behind a key whose type the index field admits.
                    .group("Regions", g -> g.repeatable(5)
                            .stringField("RegionName", f -> { })
                            .group("Docks", i -> i.repeatable(5).indexField("DockGrade")
                                    .enumField("DockGrade", e -> e.value("BRONZE").value("GOLD"))
                                    .stringField("DockName", f -> { })
                                    .numberField("DockLimit", f -> f.fractionDigits(2)))))
            .build().toJson();

    /** An unanchored rule at the root, where no key read from inside a repetition is legal. */
    private static String indexed(String probe) {
        return KernelLaws.verdictModel(DEPOT, "/Depot", "../Reference", "FieldFilled(Reference) And " + probe);
    }

    /** The rule sits inside the indexed group, so a key read from that group does not make the read iterate. */
    private static String inRuns(String probe) {
        return KernelLaws.verdictModel(DEPOT, "/Depot/Runs", "../RunNote", "FieldFilled(RunNote) And " + probe);
    }

    private static String runKeyedOn(String key) {
        return inRuns("[/Depot/Runs/RunLimit For " + key + "] > 0");
    }

    private static String firstFilled(String operand) {
        return KernelLaws.verdictModel(DEPOT, "/Depot", "../Reference",
                "FirstFilledValue(" + operand + ", Reference) == \"x\"");
    }

    /**
     * Which of the two key gates answers when a key trips both — the row the chain's order rests on.
     *
     * <p>The last row is the control for the ANCHORING the other three depend on: the anchor binds the group
     * it sits in and no other, so a key inside a different repetition still iterates and the chain never runs.
     */
    @Test
    void theTypeGateAnswersAheadOfThePlacementGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("outside the indexed group, type matching — the control", runKeyedOn("/Depot/LookupKey"));
        actual.put("INSIDE the indexed group, type matching", runKeyedOn("/Depot/Runs/RunNote"));
        actual.put("outside it, type differing", runKeyedOn("/Depot/Excess"));
        actual.put("INSIDE it AND type differing", runKeyedOn("/Depot/Runs/RunCount"));
        actual.put("inside ANOTHER repetition, which the anchor does not bind",
                runKeyedOn("/Depot/Shifts/ShiftLabel"));
        actual.put("inside the anchor, indexing a THIRD group, type matching",
                inRuns("[/Depot/Legs/LegWeight For /Depot/Runs/RunNote] > 0"));
        actual.put("inside the anchor, indexing a THIRD group, type differing",
                inRuns("[/Depot/Legs/LegWeight For /Depot/Runs/RunCount] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("outside the indexed group, type matching — the control", KernelLaws.ADMITTED);
        expected.put("INSIDE the indexed group, type matching", "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX");
        expected.put("outside it, type differing", "MVK_INDEX_TYPE_INCONSISTENT");
        // The type comparison sits inside `checkIndexField`, which `doChecks` calls before it asks where the
        // key lives — so the placement class is NOT what a doubly-wrong key draws, and a reader ordering the
        // two the other way round hands an author the wrong repair.
        expected.put("INSIDE it AND type differing", "MVK_INDEX_TYPE_INCONSISTENT");
        // Not the iteration class this chain renames, but the rule-level gate ahead of it: the read leaves
        // the anchored group, so A12 refuses the ERROR FIELD's own index before the index is ever read. What
        // the row settles is what it was written for — the anchor binds the group it sits in and no other.
        expected.put("inside ANOTHER repetition, which the anchor does not bind", "MVK_ERROR_FIELD_INVALID_INDEX");
        // The last two rows are what licenses a checker to read the anchor at all: a key the anchor binds is
        // judged by this chain even when the index selects in a group the key does not sit in, so treating
        // every key inside a repetition as unjudgeable would miss the class rather than merely defer it.
        expected.put("inside the anchor, indexing a THIRD group, type matching", KernelLaws.ADMITTED);
        expected.put("inside the anchor, indexing a THIRD group, type differing", "MVK_INDEX_TYPE_INCONSISTENT");
        assertEquals(expected, actual, "the type gate answers ahead of the placement gate");
    }

    /**
     * Which declared types the index key gate calls inconsistent — read from BOTH sides, because it is not a
     * symmetric relation.
     *
     * <p>An Enumeration index field admits only an Enumeration key, while a String one admits a custom
     * (predefined) declaration as well; so "the same kind of type" is the wrong summary, and a checker that
     * compared type names for equality would refuse the custom row A12 admits.
     */
    @Test
    void whichDeclaredTypesTheIndexKeyGateCallsInconsistent() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a STRING index field, a string key", indexed("[Runs/RunLimit For LookupKey] > 0"));
        actual.put("a STRING index field, a custom key", indexed("[Runs/RunLimit For Courier] > 0"));
        actual.put("a STRING index field, an enum key", indexed("[Runs/RunLimit For Grade] > 0"));
        actual.put("a STRING index field, a number key", indexed("[Runs/RunLimit For Excess] > 0"));
        actual.put("a STRING index field, a boolean key", indexed("[Runs/RunLimit For Insured] > 0"));
        actual.put("a STRING index field, a date key", indexed("[Runs/RunLimit For ShippedOn] > 0"));
        actual.put("a STRING index field, a time key", indexed("[Runs/RunLimit For PickupTime] > 0"));
        actual.put("a STRING index field, a date-time key", indexed("[Runs/RunLimit For ScheduledAt] > 0"));
        actual.put("a STRING index field, a date-fragment key", indexed("[Runs/RunLimit For BillingMonth] > 0"));
        actual.put("a STRING index field, a date-range key", indexed("[Runs/RunLimit For Window] > 0"));
        actual.put("a STRING index field, a confirm key", indexed("[Runs/RunLimit For Acknowledged] > 0"));
        actual.put("an ENUM index field, an enum key", indexed("[Shifts/ShiftAmount For Grade] > 0"));
        actual.put("an ENUM index field, a string key", indexed("[Shifts/ShiftAmount For LookupKey] > 0"));
        actual.put("an ENUM index field, a custom key", indexed("[Shifts/ShiftAmount For Courier] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a STRING index field, a string key", KernelLaws.ADMITTED);
        expected.put("a STRING index field, a custom key", KernelLaws.ADMITTED);
        expected.put("a STRING index field, an enum key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a number key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a boolean key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a date key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a time key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a date-time key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a date-fragment key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a date-range key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a STRING index field, a confirm key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("an ENUM index field, an enum key", KernelLaws.ADMITTED);
        expected.put("an ENUM index field, a string key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("an ENUM index field, a custom key", "MVK_INDEX_TYPE_INCONSISTENT");
        assertEquals(expected, actual, "which declared types the index key gate calls inconsistent");
    }

    /**
     * Where the two key gates sit relative to the star gates and the category gate.
     *
     * <p>Each row holds two live gates in one index, so a green row states an ORDER rather than that
     * something was refused. The four rows that pair a star ABOVE the indexed group with each earlier class
     * put that class LAST in the whole chain — a position no reading of {@code CheckSemanticIndexImpl} gives
     * it, because the gate that raises it lives in a later pass over the finished index list.
     */
    @Test
    void theOrderAroundTheTwoKeyGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a star ABOVE the indexed group beside a type-differing key",
                firstFilled("Regions*/Docks/DockLimit For LookupKey"));
        actual.put("a star at the indexed group's own level beside a type-differing key",
                firstFilled("Runs*/RunNote For Excess"));
        actual.put("a CATEGORY key that also differs in type", indexed("[Runs/RunLimit For Grade -> Band] > 0"));
        actual.put("a key inside the indexed group that also carries a CATEGORY",
                KernelLaws.verdictModel(DEPOT, "/Depot/Shifts", "../ShiftLabel",
                        "FieldFilled(ShiftLabel) And "
                                + "[/Depot/Shifts/ShiftAmount For /Depot/Shifts/ShiftTier -> Band] > 0"));
        actual.put("a star ABOVE the indexed group, nothing else wrong — the control",
                firstFilled("Regions*/Docks/DockLimit For Grade"));
        actual.put("a star ABOVE the indexed group beside an EMPTY key",
                firstFilled("Regions*/Docks/DockLimit For \"\""));
        actual.put("a star ABOVE the indexed group beside a CATEGORY key",
                firstFilled("Regions*/Docks/DockLimit For Grade -> Band"));
        actual.put("a star ABOVE the indexed group beside a star at its own level",
                firstFilled("Regions*/Docks*/DockLimit For Grade"));
        actual.put("a key without VALUE VALIDATION beside a star at the indexed group's own level",
                firstFilled("Runs*/RunNote For RawNote"));
        actual.put("a key without VALUE VALIDATION that is also inside the indexed group",
                runKeyedOn("/Depot/Runs/RunRaw"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The star ABOVE the indexed group does NOT win, which is where a reading of the source order goes
        // wrong: `getIndexGroup` finds the group without judging the star, and the class is raised much later
        // by `checkIndicesForA12Model`, once the semantic index has already been written into its level.
        expected.put("a star ABOVE the indexed group beside a type-differing key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a star at the indexed group's own level beside a type-differing key",
                "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a CATEGORY key that also differs in type", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a key inside the indexed group that also carries a CATEGORY",
                "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX");
        expected.put("a star ABOVE the indexed group, nothing else wrong — the control",
                "MVK_SEMANTIC_INDEX_BELOW_WILDCARD");
        expected.put("a star ABOVE the indexed group beside an EMPTY key", "MVK_INDEX_VALUE_INVALID");
        expected.put("a star ABOVE the indexed group beside a CATEGORY key", "MVK_SEMANTIC_INDEX_WITH_CATEGORY");
        expected.put("a star ABOVE the indexed group beside a star at its own level",
                "MVK_SEMANTIC_INDEX_AND_WILDCARD");
        // The raw-key gate is A12's `checkIsValueValidation`, which runs between the placement gate and the
        // category one — so it is the only class of this chain reported through an operation NAME rather than
        // a semantic-index code, and it still sits in a fixed place among them.
        expected.put("a key without VALUE VALIDATION beside a star at the indexed group's own level",
                "MVK_INVALID_RAW_TYPE");
        expected.put("a key without VALUE VALIDATION that is also inside the indexed group",
                "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX");
        assertEquals(expected, actual, "the order around the two key gates");
    }
}
