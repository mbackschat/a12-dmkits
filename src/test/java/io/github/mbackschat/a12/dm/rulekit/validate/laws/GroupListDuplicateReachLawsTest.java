package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * WHEN two group-list operands are "the same operand twice" — the reach of the duplicate and overlap gates.
 *
 * <p>{@link GroupListDiagnosticIdentityLawsTest} measured that the two gates EXIST and that they are two
 * classes rather than one, on four carriers, with every operand written in the same relative spelling. That
 * says a duplicate is refused and says nothing about what counts as one, which is the half a checker needs:
 * it must decide the question for every operand pair it reads, and every pair it wrongly calls a duplicate
 * is a refusal of a model A12 admits.
 *
 * <p>So the rows below vary the SPELLING and the SHAPE with the operand identities held fixed — a relative
 * path beside the absolute spelling of the same group, an ancestor beside a grandchild rather than a child,
 * the descendant named first, two groups whose names merely share a prefix, a repetition wildcard, and the
 * two carriers the identity grid never exercised on these gates. Each ADMITTED row is one an implementation
 * must not report.
 *
 * <p>Own domain (a motor insurance policy).
 */
class GroupListDuplicateReachLawsTest {

    private static final String EXACT = "MVK_DUPLICATE_PARAM1";
    private static final String OVERLAP = "MVK_DUPLICATE_PARAM2";

    /**
     * Two roots, a three-deep nest, a repeatable group, and two sibling groups whose names share a prefix.
     *
     * <p>{@code Garage} and {@code GarageBay} are siblings on purpose: a containment test written over raw
     * strings calls them overlapping, and A12 does not, so the pair separates "is a path ancestor" from
     * "is a text prefix" — the mistake an implementation makes by default.
     */
    private static final String POLICY = DocumentModelDraft.named("motor-policy").locale("en_US")
            .rootGroup("Policy", p -> p
                    .field("PolicyNumber", FieldKind.STRING)
                    .group("Coverage", c -> c
                            .field("Tier", FieldKind.STRING)
                            .group("Vehicle", v -> v
                                    .field("Plate", FieldKind.STRING)
                                    .group("Driver", d -> d.field("Licence", FieldKind.STRING))))
                    .group("Garage", g -> g.field("GarageName", FieldKind.STRING))
                    .group("GarageBay", g -> g.field("BayNumber", FieldKind.STRING))
                    .group("Claims", c -> c.repeatable(12)
                            .field("ClaimAmount", FieldKind.NUMBER)
                            .field("ClaimDate", FieldKind.STRING)))
            .rootGroup("Broker", b -> b.field("BrokerName", FieldKind.STRING))
            .build().toJson();

    /** A field inside the repeatable group — the error-field locus that makes the rule iterate it. */
    private static final String INSIDE = "../Claims/ClaimAmount";

    @Test
    void whichOperandPairsCountAsTheSameGroupTwice() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the same group twice, both relative — the control", guarded("AllGroupsFilled(Garage, Garage)"));
        actual.put("two distinct groups — the control", guarded("AllGroupsFilled(Garage, Coverage)"));
        // Does the gate compare the RESOLVED element or the authored text? A checker has to know, because
        // the two readings disagree on every model whose author mixed the spellings.
        actual.put("a relative path beside the ABSOLUTE spelling of the same group",
                guarded("AllGroupsFilled(Garage, /Policy/Garage)"));
        actual.put("an ancestor and its CHILD", guarded("AllGroupsFilled(Coverage, Coverage/Vehicle)"));
        actual.put("an ancestor and its GRANDCHILD",
                guarded("AllGroupsFilled(Coverage, Coverage/Vehicle/Driver)"));
        actual.put("the descendant named FIRST", guarded("AllGroupsFilled(Coverage/Vehicle, Coverage)"));
        // Sibling groups whose names share a prefix: `Garage` is not an ancestor of `GarageBay`.
        actual.put("two groups whose NAMES share a prefix", guarded("AllGroupsFilled(Garage, GarageBay)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the same group twice, both relative — the control", EXACT);
        expected.put("two distinct groups — the control", "admitted");
        expected.put("a relative path beside the ABSOLUTE spelling of the same group", EXACT);
        expected.put("an ancestor and its CHILD", OVERLAP);
        expected.put("an ancestor and its GRANDCHILD", OVERLAP);
        expected.put("the descendant named FIRST", OVERLAP);
        expected.put("two groups whose NAMES share a prefix", "admitted");
        assertEquals(expected, actual, "which operand pairs count as the same group twice");
    }

    /**
     * The wildcard and the error field's locus — the two axes that select a different class elsewhere.
     *
     * <p>Both are measured because the neighbouring gates are sensitive to them: a repetition wildcard picks
     * between three wildcard classes, and the error field's locus picks between {@code MVK_NO_WILDCARD} and
     * the arity class for the same operand shape ([KF185]). A duplicate that changed class on either axis
     * would make the checker's verdict locus-dependent too.
     */
    @Test
    void theWildcardAndTheErrorFieldsLocus() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the count carrier, a starred repeatable twice",
                guarded("NumberOfFilledGroups(Claims*, Claims*) > 0"));
        actual.put("the count carrier, starred beside its UNSTARRED spelling",
                guarded("NumberOfFilledGroups(Claims*, Claims) > 0"));
        actual.put("the quantifier carrier, a starred repeatable twice",
                guarded("AllGroupsFilled(Claims*, Claims*)"));
        actual.put("an exact duplicate with the error field INSIDE the repeatable group",
                verdict("/Policy", INSIDE, "FieldFilled(Claims/ClaimAmount) And AllGroupsFilled(Garage, Garage)"));
        actual.put("an overlap with the error field INSIDE the repeatable group",
                verdict("/Policy", INSIDE,
                        "FieldFilled(Claims/ClaimAmount) And AllGroupsFilled(Coverage, Coverage/Vehicle)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // A starred operand is SKIPPED by the duplicate gate, exactly as it is on the field-list carriers:
        // the pair that names one repeatable group twice is ADMITTED. A checker comparing every operand
        // would refuse it.
        expected.put("the count carrier, a starred repeatable twice", "admitted");
        // And a mixed pair never reaches the gate at all — the wildcard gate speaks first.
        expected.put("the count carrier, starred beside its UNSTARRED spelling", "MVK_NO_WILDCARD");
        expected.put("the quantifier carrier, a starred repeatable twice", "MVK_NO_WILDCARDS_G_ALLOWED");
        expected.put("an exact duplicate with the error field INSIDE the repeatable group", EXACT);
        expected.put("an overlap with the error field INSIDE the repeatable group", OVERLAP);
        assertEquals(expected, actual, "the wildcard and the error field's locus");
    }

    /** The two group-list carriers the identity grid exercised for arity but never for these two gates. */
    @Test
    void theTwoCarriersTheIdentityGridNeverPutThroughTheseGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("notCollectively | exact duplicate", guarded("GroupsNotCollectivelyFilled(Garage, Garage)"));
        actual.put("notCollectively | ancestor overlap",
                guarded("GroupsNotCollectivelyFilled(Coverage, Coverage/Vehicle)"));
        actual.put("noGroup | exact duplicate", guarded("NoGroupFilled(Garage, Garage)"));
        actual.put("noGroup | ancestor overlap", guarded("NoGroupFilled(Coverage, Coverage/Vehicle)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("notCollectively | exact duplicate", EXACT);
        expected.put("notCollectively | ancestor overlap", OVERLAP);
        expected.put("noGroup | exact duplicate", EXACT);
        expected.put("noGroup | ancestor overlap", OVERLAP);
        assertEquals(expected, actual, "the two carriers the identity grid never put through these gates");
    }

    /**
     * That the gate reads ONE CALL's operand list — the fact that makes the capability necessary.
     *
     * <p>A checker reading the condition's flat set of names cannot answer this: the same group named once
     * in each of two calls is exactly as "repeated" as a group named twice in one, and A12 admits the first.
     * So the operand list has to be recovered per call, which is why the portable checker parses rather than
     * scans.
     */
    @Test
    void theGateReadsOneCallsOperandList() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the same group once in EACH of two calls",
                guarded("AllGroupsFilled(Garage, Coverage) And AllGroupsFilled(Garage, GarageBay)"));
        actual.put("an exact duplicate and an overlap in ONE call",
                guarded("AllGroupsFilled(Coverage, Coverage/Vehicle, Garage, Garage)"));
        actual.put("an overlap in one call and an exact duplicate in another",
                guarded("AllGroupsFilled(Coverage, Coverage/Vehicle) And AllGroupsFilled(Garage, Garage)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the same group once in EACH of two calls", "admitted");
        // Within one call the exact duplicate wins whatever the authored order — the overlap pair is
        // written first here and A12 still names the exact class.
        expected.put("an exact duplicate and an overlap in ONE call", EXACT);
        // ACROSS calls A12 stops at the first violation, so the second call's exact duplicate is not in
        // the code list. It is a real violation all the same — fixing the first makes A12 report it — so a
        // checker reporting BOTH is telling the truth about the model rather than diverging from it.
        expected.put("an overlap in one call and an exact duplicate in another", OVERLAP);
        assertEquals(expected, actual, "the gate reads one call's operand list");
    }

    /**
     * What PRE-EMPTS the duplicate gates, so a checker reports one fix for one mistake rather than two.
     *
     * <p>The unresolvable operand is the row that matters: a checker judging both gates would otherwise name
     * a duplicate the author has to fix and a missing group the author has to fix, for one typo.
     */
    @Test
    void whatPreEmptsTheDuplicateGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an UNRESOLVABLE operand beside an exact duplicate",
                guarded("AllGroupsFilled(Nowhere, Garage, Garage)"));
        actual.put("a FIELD where a group belongs, named twice",
                guarded("AllGroupsFilled(PolicyNumber, PolicyNumber)"));
        actual.put("a ROOT group named twice", guarded("AllGroupsFilled(/Broker, /Broker)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an UNRESOLVABLE operand beside an exact duplicate", "MVK_INVALID_ENTITY");
        // The duplicate gate runs BEFORE the operand-KIND gate: naming one field twice where groups belong
        // is reported as a duplicate, not as the wrong kind of entity. So the gate is about the operand
        // LIST, not about groups, and a checker that first insisted every operand be a group would miss it.
        expected.put("a FIELD where a group belongs, named twice", EXACT);
        expected.put("a ROOT group named twice", EXACT);
        assertEquals(expected, actual, "what pre-empts the duplicate gates");
    }

    /** The model-level anchoring: the error field sits outside every repeatable group. */
    private static String guarded(String condition) {
        return verdict("/Policy", "../PolicyNumber", "FieldFilled(PolicyNumber) And " + condition);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(POLICY, group, errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
