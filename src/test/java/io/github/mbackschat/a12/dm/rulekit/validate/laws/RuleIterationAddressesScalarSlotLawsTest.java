package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF477 — <b>the level a rule iterates addresses a scalar slot's read</b>, whether the rule is
 * declared inside the repetition or its error field sits there.
 *
 * <p>The laws for these three gates ({@code FieldOperandKindLawsTest}, {@code ScalarValueListGateLawsTest},
 * {@code DateArgumentSlotLawsTest}) all declare their rule at the root, so none of them varies where the rule
 * iterates. Each gate here reads a field of the repeatable {@code Claims} three ways: from a root rule, whose
 * unaddressed read is {@code MVK_NO_WILDCARD} and is the control that the rival reading is observable; from a
 * rule declared inside {@code Claims}; and from a root rule whose error field is in {@code Claims}, which
 * separates the declaring group from the target's group.
 *
 * <p>{@code Claims} is indexed by {@code ClaimRef}, so a string key on it is sound; {@code Riders} declares the
 * same fields and no index field, so a key on it is not.
 *
 * <p>String rows: each operand is of a kind its gate refuses, which the typed surface refuses statically.
 * Own domain (insurance claims).
 */
class RuleIterationAddressesScalarSlotLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-iterated-slot").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Claims", g -> claimFields(g.repeatable(20).indexField("ClaimRef")))
                    .group("Riders", g -> claimFields(g.repeatable(5)))
                    .group("Notes", g -> g.repeatable(5).stringField("Text", f -> { })))
            .build().toJson();

    private static DocumentModelDraft.GroupBuilder claimFields(DocumentModelDraft.GroupBuilder g) {
        return g.stringField("ClaimRef", f -> { })
                .stringField("Memo", f -> { })
                .booleanField("Settled", f -> { })
                .group("Assessor", a -> a.stringField("AssessorName", f -> { }));
    }

    /** Each gate's probe over one operand, written {@code %s}. */
    private static final Map<String, String> PROBES = new LinkedHashMap<>();

    /** The class each gate names where it is reached. */
    private static final Map<String, String> CLASS = new LinkedHashMap<>();

    static {
        PROBES.put("field-operand kind", "FieldFilled(%s)");
        CLASS.put("field-operand kind", "MVK_NO_GROUPS_ALLOWED");
        PROBES.put("value-list subject", "FieldValueIncludedInValueList(%s, \"x\")");
        CLASS.put("value-list subject", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        PROBES.put("date-argument slot", "AddDays(%s, 1) < Today");
        CLASS.put("date-argument slot", "MVK_NO_DATE");
    }

    /** The field of {@code Claims} each gate is probed on. */
    private static final Map<String, String> FIELD = Map.of(
            "field-operand kind", "Assessor",
            "value-list subject", "Settled",
            "date-argument slot", "Memo");

    @Test
    void theLevelARuleIteratesAddressesTheRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String gate : PROBES.keySet()) {
            String probe = PROBES.get(gate);
            String field = FIELD.get(gate);
            actual.put(gate + " — a root rule", KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                    "FieldFilled(Reference) And " + probe.formatted("Claims/" + field)));
            expected.put(gate + " — a root rule", "MVK_NO_WILDCARD");
            actual.put(gate + " — a rule declared inside Claims", KernelLaws.verdictModel(POLICY, "/Policy/Claims",
                    "../ClaimRef", "FieldFilled(ClaimRef) And " + probe.formatted(field)));
            expected.put(gate + " — a rule declared inside Claims", CLASS.get(gate));
            actual.put(gate + " — a root rule whose error field is in Claims", KernelLaws.verdictModel(POLICY,
                    "/Policy", "../Claims/ClaimRef",
                    "FieldFilled(Claims/ClaimRef) And " + probe.formatted("Claims/" + field)));
            expected.put(gate + " — a root rule whose error field is in Claims", CLASS.get(gate));
        }
        assertEquals(expected, actual, "what a scalar slot names on a read the rule's iteration addresses");
    }

    /**
     * The other two ways a read is addressed, on the same three gates: a key on the level the rule iterates,
     * sound or defective, and a {@code Having} body, on another level's filter and on the filtered level's own.
     * These are KF476's rows on three gates its grid did not carry.
     */
    @Test
    void aKeyAndAFilterBodyAddressTheReadAsOnEveryOtherGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String gate : PROBES.keySet()) {
            String probe = PROBES.get(gate);
            String field = FIELD.get(gate);
            actual.put(gate + " — a string key inside Claims", KernelLaws.verdictModel(POLICY, "/Policy/Claims",
                    "../ClaimRef", "FieldFilled(ClaimRef) And " + probe.formatted(field + " For \"X\"")));
            expected.put(gate + " — a string key inside Claims", CLASS.get(gate));
            actual.put(gate + " — a number literal key inside Claims", KernelLaws.verdictModel(POLICY,
                    "/Policy/Claims", "../ClaimRef",
                    "FieldFilled(ClaimRef) And " + probe.formatted(field + " For 5")));
            expected.put(gate + " — a number literal key inside Claims", "MVK_INVALID_ENTITY");
            actual.put(gate + " — a string key inside Riders", KernelLaws.verdictModel(POLICY, "/Policy/Riders",
                    "../ClaimRef", "FieldFilled(ClaimRef) And " + probe.formatted(field + " For \"X\"")));
            expected.put(gate + " — a string key inside Riders", "MVK_NO_INDEX_FIELD");
            actual.put(gate + " — another level's filter body", KernelLaws.verdictModel(POLICY, "/Policy",
                    "../Reference", "FieldFilled(Reference) And NumberOfFilledFields(Notes*/Text Having "
                            + probe.formatted("Claims/" + field) + ") > 0"));
            expected.put(gate + " — another level's filter body", CLASS.get(gate));
            actual.put(gate + " — the filtered level's own body", KernelLaws.verdictModel(POLICY, "/Policy",
                    "../Reference", "FieldFilled(Reference) And NumberOfFilledFields(Claims*/Memo Having "
                            + probe.formatted("Claims/" + field) + ") > 0"));
            expected.put(gate + " — the filtered level's own body", CLASS.get(gate));
        }
        assertEquals(expected, actual, "what a scalar slot names on a keyed read and on a filter-body read");
    }
}
