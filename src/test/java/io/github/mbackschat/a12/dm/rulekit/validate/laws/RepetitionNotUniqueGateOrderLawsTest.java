package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — the COMPLETE gate order of `RepetitionNotUnique`, key list and `@From` scope.
 *
 * <p>Eleven classes guard one construct and the estate had measured them one containment assertion at a
 * time, spread across four files. A containment claim cannot say which gate reports when two are violated,
 * and two portable-checker families already report two of these classes — so the order is not a curiosity
 * here, it is what decides whether each family may speak.
 *
 * <p><b>The two branches are exclusive, which is the structural claim the last rows carry.</b> With an
 * `@From` scope A12 checks containment in that scope; without one it checks a shared path, the rule group
 * and a repeatable group below it. A grid that never pairs a scope with a key-placement defect cannot see
 * that the second set is simply not run.
 *
 * <p>Own domain (insurance claims).
 */
class RepetitionNotUniqueGateOrderLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-uniqueness").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .enumField("Tier", e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH"))
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Claims", g -> g.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))
                            .stringField("RawNote", f -> f.lineBreaks().noValueValidation())
                            .group("Items", i -> i.repeatable(5).stringField("ItemRef", f -> { })))
                    // Keyed on the SAME index field as `Claims`, so the two iterate jointly and a read of
                    // one from a rule about the other is legal. Without that the iteration gate answers
                    // `MVK_NO_WILDCARD` on every placement row and the construct's own gates are never
                    // reached — measured, which is also what [KF349] records for `MVK_NOT_IN_GROUP`.
                    .group("Payments", g -> g.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .stringField("PayRef", f -> { })))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Claims/ClaimRef", condition);
    }

    /** The same construct with the rule one level down, where a key can sit OUTSIDE the rule's own group. */
    private static String inClaims(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy/Claims", "../ClaimRef", condition);
    }

    @Test
    void whichKeyListGateReports() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a well-formed key — the control", verdict("RepetitionNotUnique(Claims/ClaimRef)"));
        actual.put("a key carrying a CATEGORY", verdict("RepetitionNotUnique(Tier -> Band)"));
        actual.put("a key naming a GROUP", verdict("RepetitionNotUnique(Claims)"));
        actual.put("a STARRED key", verdict("RepetitionNotUnique(Claims*/ClaimRef)"));
        actual.put("a key carrying a SEMANTIC INDEX",
                verdict("RepetitionNotUnique(Claims/Amount For \"C-1\")"));
        actual.put("a key without VALUE VALIDATION", verdict("RepetitionNotUnique(Claims/RawNote)"));
        actual.put("a repeated key without VALUE VALIDATION",
                verdict("RepetitionNotUnique(Claims/RawNote, Claims/RawNote)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a well-formed key — the control", KernelLaws.ADMITTED);
        expected.put("a key carrying a CATEGORY", "MVK_CATEGORY_NOT_ALLOWED");
        expected.put("a key naming a GROUP", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a STARRED key", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a key carrying a SEMANTIC INDEX", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        expected.put("a key without VALUE VALIDATION", "MVK_INVALID_RAW_TYPE");
        expected.put("a repeated key without VALUE VALIDATION", "MVK_DUPLICATE_PARAM1");
        assertEquals(expected, actual, "which key-list gate reports");
    }

    @Test
    void whichScopeAndPlacementGateReports() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a scope naming a FIELD", verdict("RepetitionNotUnique(Claims/ClaimRef @From Reference)"));
        actual.put("a scope that does not REPEAT", verdict("RepetitionNotUnique(Claims/ClaimRef @From Insured)"));
        actual.put("a key outside the SCOPE", verdict("RepetitionNotUnique(Claims/ClaimRef @From Payments)"));
        actual.put("keys on PARALLEL paths, no scope",
                verdict("RepetitionNotUnique(Claims/ClaimRef, Payments/PayRef)"));
        actual.put("a key with no repeatable group between it and the rule group",
                verdict("RepetitionNotUnique(Reference)"));
        actual.put("a key outside the RULE GROUP, no scope",
                inClaims("RepetitionNotUnique(/Policy/Payments/PayRef)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a scope naming a FIELD", "MVK_NO_FIELDS_ALLOWED");
        expected.put("a scope that does not REPEAT", "MVK_GROUP_NOT_REPEATABLE");
        expected.put("a key outside the SCOPE", "MVK_NOT_IN_GROUP");
        expected.put("keys on PARALLEL paths, no scope", "MVK_NOT_IN_SAME_PATH");
        expected.put("a key with no repeatable group between it and the rule group",
                "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("a key outside the RULE GROUP, no scope", "MVK_NOT_IN_RULEGROUP");
        assertEquals(expected, actual, "which scope and placement gate reports");
    }

    /**
     * Two gates violated at once, which is what a containment assertion cannot see.
     *
     * <p>The last row is the structural one: keys on parallel paths would draw `MVK_NOT_IN_SAME_PATH`
     * without a scope, and with one the shared-path check is simply not run.
     */
    @Test
    void whichGateWinsWhenTwoAreViolated() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a GROUP key beside a scope that does not repeat",
                verdict("RepetitionNotUnique(Claims @From Insured)"));
        actual.put("a key without value validation, outside the scope",
                verdict("RepetitionNotUnique(Claims/RawNote @From Payments)"));
        actual.put("a STARRED key beside a field scope",
                verdict("RepetitionNotUnique(Claims*/ClaimRef @From Reference)"));
        actual.put("keys on PARALLEL paths, with a scope",
                verdict("RepetitionNotUnique(Claims/ClaimRef, Payments/PayRef @From Claims)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Every key-list gate runs before any scope gate, so the key's defect answers whatever the scope does.
        expected.put("a GROUP key beside a scope that does not repeat", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a key without value validation, outside the scope", "MVK_INVALID_RAW_TYPE");
        expected.put("a STARRED key beside a field scope", "MVK_NO_WILDCARDS_ALLOWED");
        // The branch claim: with a scope present the shared-path check never runs.
        expected.put("keys on PARALLEL paths, with a scope", "MVK_NOT_IN_GROUP");
        assertEquals(expected, actual, "which gate wins when two are violated");
    }
}
