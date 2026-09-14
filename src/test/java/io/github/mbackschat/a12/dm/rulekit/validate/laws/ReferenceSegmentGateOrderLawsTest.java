package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §14 — which gate answers on a malformed PATH, segment by segment.
 *
 * <p>A12 checks a path one segment at a time, left to right, and each segment runs resolution, then the
 * H-model `.*` suffix, then the wildcard's landing site. That order is what decides the class a reference
 * family may name, and this checker's two most-used families report two of these codes — so a wrong reading
 * here is a wrong class on every malformed reference, not on an unusual one.
 *
 * <p><b>`MVK_POINT_IN_A12_MODEL` had no measurement anywhere in this estate.</b> Its ledger row calls it
 * erased because the typed renderer never emits `.*` — true of the renderer and not of a raw condition
 * string, which `rule check` and `modify --condition` both accept.
 *
 * <p>Own domain (insurance claims).
 */
class ReferenceSegmentGateOrderLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-paths").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Claims", g -> g.repeatable(20)
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))))
            .build().toJson();

    /** One probe, with the error-field reference held constant so every row differs in the path alone. */
    private static String path(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + probe);
    }

    @Test
    void whichGateReportsOnAMalformedPathSegment() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a resolvable path — the control", path("Sum(Claims*/Amount) > 0"));
        actual.put("an unresolvable FIRST segment of an absolute path", path("Sum(/NoSuchRoot/Amount) > 0"));
        actual.put("an unresolvable LATER segment", path("Sum(Claims*/NoSuchField) > 0"));
        actual.put("an absolute path whose ROOT resolves and whose LEAF does not",
                path("FieldFilled(/Policy/NoSuchField)"));
        actual.put("an absolute path that is ONLY an unresolvable root", path("GroupFilled(/NoSuchRoot)"));
        actual.put("an absolute path whose first segment is a DECLARED group that is not a root",
                path("Sum(/Claims*/Amount) > 0"));
        actual.put("a `.*` suffix on a resolvable segment", path("Sum(Claims.*/Amount) > 0"));
        actual.put("a STAR on a non-repeatable group", path("Sum(Insured*/InsuredName) > 0"));
        actual.put("too many upward steps", path("FieldFilled(../../../Reference)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a resolvable path — the control", KernelLaws.ADMITTED);
        expected.put("an unresolvable FIRST segment of an absolute path", "MVK_INVALID_ROOT_GROUP");
        expected.put("an unresolvable LATER segment", "MVK_INVALID_ENTITY");
        // The pair that separates "the path is absolute" from "the ROOT SEGMENT is what failed": A12
        // resolves segment by segment, so the root-group class depends on WHICH segment failed and not on
        // how long the path is. A checker counting separators in the whole candidate gets this row wrong.
        expected.put("an absolute path whose ROOT resolves and whose LEAF does not", "MVK_INVALID_ENTITY");
        expected.put("an absolute path that is ONLY an unresolvable root", "MVK_INVALID_ROOT_GROUP");
        // `Claims` exists, just not at root level, so "the name is undeclared" is false and the root-group
        // class is still what answers — the gate is about the POSITION the segment is read at.
        expected.put("an absolute path whose first segment is a DECLARED group that is not a root",
                "MVK_INVALID_ROOT_GROUP");
        expected.put("a `.*` suffix on a resolvable segment", "MVK_POINT_IN_A12_MODEL");
        expected.put("a STAR on a non-repeatable group", "MVK_INVALID_WILDCARD");
        expected.put("too many upward steps", "MVK_TOO_MANY_PARENT_GROUPS");
        assertEquals(expected, actual, "which gate reports on a malformed path segment");
    }

    /**
     * Two defects in one path, which is what a containment assertion cannot see.
     *
     * <p>Each row breaks resolution AND a later gate on the same segment, or an earlier segment AND a later
     * one, so a green row states the order rather than merely that something was refused.
     */
    @Test
    void whichGateWinsWhenOneSegmentBreaksTwoRules() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a `.*` suffix on an UNRESOLVABLE segment", path("Sum(NoSuchGroup.*/Amount) > 0"));
        actual.put("a STAR on an unresolvable segment", path("Sum(NoSuchGroup*/Amount) > 0"));
        actual.put("a bad wildcard EARLIER, an unresolvable name LATER",
                path("Sum(Insured*/NoSuchField) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Resolution runs first within a segment, so neither of the segment's later gates is reached.
        expected.put("a `.*` suffix on an UNRESOLVABLE segment", "MVK_INVALID_ENTITY");
        expected.put("a STAR on an unresolvable segment", "MVK_INVALID_ENTITY");
        // And segments run left to right, so the earlier segment's defect answers.
        expected.put("a bad wildcard EARLIER, an unresolvable name LATER", "MVK_INVALID_WILDCARD");
        assertEquals(expected, actual, "which gate wins when one segment breaks two rules");
    }
}
