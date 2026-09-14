package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — which gate reports on a malformed SEMANTIC INDEX, and in what order.
 *
 * <p>The semantic index {@code [Field For key]} is the only way to address one repetition, and its own
 * structure carries nine rejection classes. Five of them had no measurement anywhere in this estate before
 * this grid, and the ledger described each from its NAME — the failure mode already recorded twice here,
 * where a row reads as a law and points at nothing. Two of the five turned out not to be reported at all.
 *
 * <p><b>Why a grid and not nine containment assertions.</b> The gates sit in one chain, so the claim worth
 * locking is which one answers when two are violated at once; a {@code rejects(…, code)} assertion is an
 * {@code anyMatch} and cannot see a co-firing gate. The three wildcard classes are the sharpest case —
 * {@code _AND_WILDCARD}, {@code _BELOW_WILDCARD} and {@code _WITH_WILDCARD} are three different laws about a
 * star, separated only by WHERE the star sits, and naming the wrong one hands an author a fix for a gate
 * they did not trip.
 *
 * <p>Own domain (insurance policy coverages).
 */
class SemanticIndexStructureLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-index").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .stringField("LookupCode", f -> { })
                    .stringField("RawNote", f -> f.lineBreaks().noValueValidation())
                    .numberField("Excess", f -> f.fractionDigits(0))
                    .enumField("Tier", e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH"))
                    // A NON-repeatable group, which is the only way to put a group in the key position
                    // without the read iterating — the pre-emption `theIterationGateReportsFirst` measures.
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Coverages", g -> g.repeatable(20).indexField("CoverageCode")
                            .stringField("CoverageCode", f -> { })
                            .stringField("Note", f -> { })
                            .numberField("Limit", f -> f.fractionDigits(2)))
                    // Keyed on an ENUMERATION, so an enum key passes the index field's base-type gate and the
                    // category gate behind it becomes reachable at all.
                    .group("Endorsements", g -> g.repeatable(10).indexField("Grade")
                            .enumField("Grade", e -> e.value("BRONZE").value("GOLD"))
                            .stringField("Label", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2)))
                    .group("Sections", g -> g.repeatable(10)
                            .stringField("SectionName", f -> { })
                            .numberField("SectionLimit", f -> f.fractionDigits(2))
                            .group("Items", i -> i.repeatable(10).indexField("ItemCode")
                                    .stringField("ItemCode", f -> { })
                                    .numberField("ItemLimit", f -> f.fractionDigits(2)))))
            .build().toJson();

    /**
     * One probe, with the error-field reference held constant so every row differs in the index alone.
     *
     * <p>The {@code FieldFilled(Reference)} conjunct is not decoration: without it every row would draw
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED} and the grid would measure that gate instead of this one.
     */
    private static String indexed(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + probe);
    }

    /**
     * The index's own structure, with every key held NON-iterating so this chain is the one that answers.
     *
     * <p>Two rows name a class no semantic-index code covers, and both are the measurement correcting a
     * ledger row written from a code's name: a key without value validation is {@code MVK_INVALID_RAW_TYPE}
     * (A12 interpolates {@code MVK_SEMANTIC_INDEX_REF_OP} as the operation NAME inside that message rather
     * than reporting it), and a key whose type does not match the index field's is
     * {@code MVK_INDEX_TYPE_INCONSISTENT}.
     */
    @Test
    void whichGateReportsOnAMalformedSemanticIndex() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a LITERAL key — the control", indexed("[Coverages/Limit For \"C-1\"] > 0"));
        actual.put("a FIELD key — the second control", indexed("[Coverages/Limit For LookupCode] > 0"));
        actual.put("the key names a NON-REPEATABLE group", indexed("[Coverages/Limit For Insured] > 0"));
        actual.put("the key carries a WILDCARD", indexed("[Coverages/Limit For Sections*/SectionName] > 0"));
        actual.put("the key field has no VALUE VALIDATION", indexed("[Coverages/Limit For RawNote] > 0"));
        actual.put("the key's TYPE differs from the index field's", indexed("[Coverages/Limit For Excess] > 0"));
        actual.put("the key carries a CATEGORY projection, types matching",
                indexed("[Endorsements/Amount For Tier -> Band] > 0"));
        actual.put("the key carries its OWN semantic index",
                indexed("[Coverages/Limit For Endorsements/Label For \"G-1\"] > 0"));
        actual.put("the indexed group declares NO index field", indexed("[Sections/SectionLimit For \"S-1\"] > 0"));
        actual.put("an EMPTY literal key", indexed("[Coverages/Limit For \"\"] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a LITERAL key — the control", KernelLaws.ADMITTED);
        expected.put("a FIELD key — the second control", KernelLaws.ADMITTED);
        expected.put("the key names a NON-REPEATABLE group", "MVK_SEMANTIC_INDEX_NO_FIELD");
        expected.put("the key carries a WILDCARD", "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        expected.put("the key field has no VALUE VALIDATION", "MVK_INVALID_RAW_TYPE");
        expected.put("the key's TYPE differs from the index field's", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("the key carries a CATEGORY projection, types matching", "MVK_SEMANTIC_INDEX_WITH_CATEGORY");
        expected.put("the key carries its OWN semantic index", "MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX");
        expected.put("the indexed group declares NO index field", "MVK_NO_INDEX_FIELD");
        expected.put("an EMPTY literal key", "MVK_INDEX_VALUE_INVALID");
        assertEquals(expected, actual, "which gate reports on a malformed semantic index");
    }

    /**
     * An ITERATING key pre-empts every structural gate above, and A12 renames the class rather than adding one.
     *
     * <p>This is the same iteration gate that answers an ordinary unstarred read into a repetition the error
     * field does not sit in — the one [KF350] records — and the only difference is the reported class: inside
     * a semantic index it is {@code MVK_SEMANTIC_INDEX_INVALID_ITER}, outside it is {@code MVK_NO_WILDCARD}.
     * The last row is that discriminator, so the two classes are measured as one gate rather than assumed to
     * be two.
     */
    @Test
    void theIterationGateReportsFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a REPEATABLE group as the key", indexed("[Coverages/Limit For Sections] > 0"));
        actual.put("a field INSIDE a repetition as the key",
                indexed("[Coverages/Limit For Coverages/CoverageCode] > 0"));
        actual.put("the same iterating read OUTSIDE any semantic index",
                indexed("FieldFilled(Coverages/CoverageCode)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a REPEATABLE group as the key", "MVK_SEMANTIC_INDEX_INVALID_ITER");
        // Reached before the containment gate, so `MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX` is NOT what a key
        // read from inside the indexed group draws on this carrier — [KF205] measured that class with the
        // rule anchored so the iteration is legal.
        expected.put("a field INSIDE a repetition as the key", "MVK_SEMANTIC_INDEX_INVALID_ITER");
        expected.put("the same iterating read OUTSIDE any semantic index", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "an iterating key pre-empts the structural gates");
    }

    /**
     * The three wildcard classes, separated by WHERE the star sits relative to the indexed group.
     *
     * <p>They share a carrier deliberately: {@code FirstFilledValue} takes a bare entity spec, so a starred
     * path needs no aggregate around it and the rule's error field is satisfied by the fallback operand.
     */
    @Test
    void whereTheStarSitsChoosesTheWildcardClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no star — the control", firstFilled("Coverages/Note For \"C-1\""));
        actual.put("the star at the INDEXED group's own level", firstFilled("Coverages*/Note For \"C-1\""));
        actual.put("the star ABOVE the indexed group", firstFilled("Sections*/Items/ItemCode For \"I-1\""));
        actual.put("the star inside the KEY", firstFilled("Coverages/Note For Sections*/SectionName"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no star — the control", KernelLaws.ADMITTED);
        expected.put("the star at the INDEXED group's own level", "MVK_SEMANTIC_INDEX_AND_WILDCARD");
        expected.put("the star ABOVE the indexed group", "MVK_SEMANTIC_INDEX_BELOW_WILDCARD");
        expected.put("the star inside the KEY", "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        assertEquals(expected, actual, "where the star sits chooses the wildcard class");
    }

    /**
     * The ORDER among the structural gates themselves, which the grids above do not settle.
     *
     * <p>Each grid row above holds exactly one defect, so a reproduction of this chain would have to guess
     * which class a doubly-defective index draws — and the three wildcard classes plus the missing index
     * field are precisely the pairs an author writes together. These cells put two live gates in one index.
     */
    @Test
    void theOrderAmongTheStructuralGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a starred KEY where the indexed group has no index field",
                indexed("[Sections/SectionLimit For Sections*/SectionName] > 0"));
        actual.put("a star at the indexed group's level where that group has no index field",
                firstFilled("Sections*/SectionName For \"S-1\""));
        actual.put("an EMPTY key beside a star at the indexed group's level",
                firstFilled("Coverages*/Note For \"\""));
        actual.put("a CATEGORY key beside a star at the indexed group's level",
                firstFilled("Endorsements*/Label For Tier -> Band"));
        actual.put("a starred KEY beside a star at the indexed group's level",
                firstFilled("Coverages*/Note For Sections*/SectionName"));
        actual.put("an EMPTY key where the indexed group has no index field",
                indexed("[Sections/SectionLimit For \"\"] > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a starred KEY where the indexed group has no index field",
                "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        expected.put("a star at the indexed group's level where that group has no index field",
                "MVK_NO_INDEX_FIELD");
        expected.put("an EMPTY key beside a star at the indexed group's level", "MVK_INDEX_VALUE_INVALID");
        expected.put("a CATEGORY key beside a star at the indexed group's level",
                "MVK_SEMANTIC_INDEX_WITH_CATEGORY");
        expected.put("a starred KEY beside a star at the indexed group's level",
                "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        expected.put("an EMPTY key where the indexed group has no index field", "MVK_NO_INDEX_FIELD");
        assertEquals(expected, actual, "the order among the structural gates");
    }

    private static String firstFilled(String operand) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FirstFilledValue(" + operand + ", Reference) == \"x\"");
    }

    /**
     * Two shapes violating the KEY-KIND gate and a later gate at once, which containment cannot see.
     *
     * <p>Each row holds a group key beside a second defect the chain reports on its own, so a green row
     * states the ORDER rather than merely that something was refused.
     */
    @Test
    void theKeyKindGateReportsAheadOfTheGatesBelowIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group key in a group that declares NO index field",
                indexed("[Sections/SectionLimit For Insured] > 0"));
        actual.put("a group key whose indexed path is starred",
                firstFilled("Coverages*/Note For Insured"));
        actual.put("a group key beside a SECOND index the chain would reject on its own",
                indexed("[Coverages/Limit For Insured] > 0 And [Insured/InsuredName For \"X\"] == \"y\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group key in a group that declares NO index field", "MVK_SEMANTIC_INDEX_NO_FIELD");
        expected.put("a group key whose indexed path is starred", "MVK_SEMANTIC_INDEX_NO_FIELD");
        // The second index is malformed too — `Insured` declares no index field — and A12 stops at the
        // first gate that answers, so this row is what licenses the checker to report the key class once
        // rather than leaving a condition with two defects to a family that does not exist.
        expected.put("a group key beside a SECOND index the chain would reject on its own",
                "MVK_SEMANTIC_INDEX_NO_FIELD");
        assertEquals(expected, actual, "the key-kind gate reports ahead of the gates below it");
    }
}
