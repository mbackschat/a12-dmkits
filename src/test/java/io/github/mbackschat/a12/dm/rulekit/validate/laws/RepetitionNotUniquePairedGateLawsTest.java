package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — {@code RepetitionNotUnique}'s gates measured two at a time, where
 * {@link RepetitionNotUniqueGateOrderLawsTest} measured them one at a time.
 *
 * <p>A single violated gate shows which class a gate names, not which gate A12 asks first, and every row here
 * violates two gates or pairs a violation with the construct that decides whether a gate runs at all. The
 * order the kernel's check source writes is a hypothesis these rows test, not their basis: each method names
 * the rival order that would draw a different class on its discriminating rows. The texts are written out,
 * because most of them are shapes the typed builder refuses.
 *
 * <p>Own domain (insurance claims, and a fleet of vehicles as a repeatable root).
 */
class RepetitionNotUniquePairedGateLawsTest {

    private static final String MODEL = DocumentModelDraft.named("policy-uniqueness-pairs").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .enumField("Tier", e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH"))
                    .group("Insured", i -> i.stringField("InsuredName", f -> { }))
                    .group("Claims", c -> c.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))
                            .stringField("RawNote", f -> f.lineBreaks().noValueValidation())
                            .group("Adjuster", a -> a.stringField("AdjusterRef", f -> { }))
                            .group("Assessor", a -> a.stringField("AssessorRef", f -> { })))
                    .group("Payments", g -> g.repeatable(20).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .stringField("PayRef", f -> { }))
                    // Two repeatable groups keyed on one index field inside a repeatable frame that has none:
                    // an indexed frame draws `MVK_MULTIPLE_INDEX_FIELDS` before any gate of this construct
                    // ([KF332]), and an index-free pair is refused `MVK_NO_WILDCARD`.
                    .group("Batches", b -> b.repeatable(4)
                            .group("Items", i -> i.repeatable(5).indexField("LineRef")
                                    .stringField("LineRef", f -> { }).stringField("ItemRef", f -> { }))
                            .group("Parts", q -> q.repeatable(5).indexField("LineRef")
                                    .stringField("LineRef", f -> { }).stringField("PartRef", f -> { })))
                    .group("Riders", r -> r.repeatable(5).stringField("RiderRef", f -> { }))
                    .group("Vacant", v -> v.repeatable(3))
                    .group("Dormant", d -> { }))
            .rootGroup("Fleet", f -> f.repeatable(50)
                    .stringField("Plate", s -> { })
                    .stringField("Vin", s -> { })
                    .group("Registration", r -> r.stringField("RegNo", s -> { }))
                    .group("Drivers", d -> d.repeatable(3).stringField("License", s -> { })))
            .build().toJson();

    /** A key A12 refuses while READING it, before any list gate: an index on a group without an index field. */
    private static final String UNREAD = "Insured/InsuredName For \"k\"";

    private static String inPolicy(String condition) {
        return KernelLaws.verdictModel(MODEL, "/Policy", "../Claims/ClaimRef", condition);
    }

    private static String inClaims(String condition) {
        return KernelLaws.verdictModel(MODEL, "/Policy/Claims", "../ClaimRef", condition);
    }

    /**
     * Every key is read before any key-list gate is asked. Rival: the gates run per key as it is read, which
     * gives the first key's gate class on each row whose defect comes first.
     */
    @Test
    void aKeyThatCannotBeReadAnswersAheadOfEveryKeyListGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String defect : new String[] {"Insured", "Claims/RawNote", "Tier -> Band", "Claims*/ClaimRef"}) {
            expected.put(defect + ", " + UNREAD, "MVK_NO_INDEX_FIELD");
            expected.put(UNREAD + ", " + defect, "MVK_NO_INDEX_FIELD");
        }
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inPolicy("RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /**
     * The key-list gates run one gate over the whole list at a time, in a fixed order. Each adjacent pair of
     * gates is measured in both key orders; the rival, one key's gates at a time, gives the first key's class.
     */
    @Test
    void theKeyListGatesAnswerInOneOrderWhateverTheKeyOrder() {
        String category = "Tier -> Band";
        String group = "Insured";
        String starred = "Claims*/ClaimRef";
        String indexed = "Claims/Amount For \"C-1\"";
        String raw = "Claims/RawNote";
        Map<String, String> expected = new LinkedHashMap<>();
        putBothOrders(expected, category, group, "MVK_CATEGORY_NOT_ALLOWED");
        putBothOrders(expected, group, starred, "MVK_NO_GROUPS_ALLOWED");
        putBothOrders(expected, starred, indexed, "MVK_NO_WILDCARDS_ALLOWED");
        putBothOrders(expected, indexed, raw, "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        putBothOrders(expected, group, raw, "MVK_NO_GROUPS_ALLOWED");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inPolicy("RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /**
     * Without a scope the shared-path gate is asked before the rule-group gate. Rival: rule group first, which
     * gives {@code MVK_NOT_IN_RULEGROUP} on the pairs. The lone outside key is the control for that class.
     */
    @Test
    void theSharedPathGateAnswersAheadOfTheRuleGroupGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        putBothOrders(expected, "ClaimRef", "/Policy/Payments/PayRef", "MVK_NOT_IN_SAME_PATH");
        expected.put("/Policy/Payments/PayRef", "MVK_NOT_IN_RULEGROUP");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inClaims("RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /**
     * The shared-path gate compares the keys' lowest REPEATABLE groups, so two keys in sibling fixed groups of
     * one repeatable group share a path. Rival: comparing the keys' own groups, which are parallel, gives
     * {@code MVK_NOT_IN_SAME_PATH}. Under an {@code @From} scope the shared-path gate is not asked: the fixed
     * pair stays admitted, and two keys in sibling repeatable groups, which iterate jointly on their shared
     * index field, meet the construct's post-parse ban on parallel iteration instead.
     */
    @Test
    void theSharedPathIsTheLowestRepeatableGroup() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Claims/Adjuster/AdjusterRef", KernelLaws.ADMITTED);
        expected.put("Claims/Adjuster/AdjusterRef, Claims/Assessor/AssessorRef", KernelLaws.ADMITTED);
        expected.put("Claims/Adjuster/AdjusterRef, Claims/Assessor/AssessorRef @From Claims", KernelLaws.ADMITTED);
        expected.put("Batches/Items/ItemRef, Batches/Parts/PartRef", "MVK_NOT_IN_SAME_PATH");
        expected.put("Batches/Items/ItemRef, Batches/Parts/PartRef @From Batches",
                "MVK_PARALLEL_ITERATION_NOT_ALLOWED");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, reportingAtFirstKey("/Policy", keys)));
        assertEquals(expected, actual);
    }

    /**
     * At a repeatable root the repeatable-group gate is skipped when every key sits directly in the root
     * group. Rival: the skip covers any key below the root, which admits the fixed-subgroup rows; the
     * repeatable subgroup is the control that a key below the root is admitted when a repeatable level is
     * there.
     */
    @Test
    void aRepeatableRootSkipsTheRepeatableGroupGateOnlyForItsOwnFields() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Plate", KernelLaws.ADMITTED);
        expected.put("Plate, Vin", KernelLaws.ADMITTED);
        expected.put("Registration/RegNo", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("Plate, Registration/RegNo", "MVK_REPEATABLE_GROUP_MISSING");
        expected.put("Drivers/License", KernelLaws.ADMITTED);
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, reportingAtFirstKey("/Fleet", keys)));
        assertEquals(expected, actual);
    }

    /**
     * The {@code @From} scope is read with the keys, ahead of every gate the construct asks, the duplicate gate
     * included, and a key that cannot be read answers ahead of the scope. The rival, that the scope is read
     * after the key-list gates, predicted the key's gate class on the raw-key and duplicate rows; it was the
     * prediction here before the first run refuted it. {@code Vacant} is an empty repeatable group and
     * {@code Dormant} an empty fixed one; the rule iterates {@code Claims}, so {@code Vacant}'s level is
     * unbound and its missing star answers before its emptiness.
     */
    @Test
    void theScopeIsReadWithTheKeysAheadOfEveryGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Claims/RawNote @From Vacant", "MVK_NO_WILDCARD");
        expected.put("Claims/ClaimRef @From Vacant", "MVK_NO_WILDCARD");
        expected.put("Claims/ClaimRef, Claims/ClaimRef @From Vacant", "MVK_NO_WILDCARD");
        expected.put(UNREAD + " @From Vacant", "MVK_NO_INDEX_FIELD");
        expected.put("Claims/ClaimRef @From Dormant", "MVK_GROUP_IS_EMPTY");
        expected.put("Claims/RawNote @From Dormant", "MVK_GROUP_IS_EMPTY");
        expected.put("Claims/ClaimRef, Claims/ClaimRef @From Dormant", "MVK_GROUP_IS_EMPTY");
        expected.put("Claims/RawNote @From Insured For \"k\"", "MVK_NO_INDEX_FIELD");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inPolicy("RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /**
     * The scope's own read: its index classes, and its missing star where the rule reports at the root. Rival
     * for the index rows: the scope's index gate asked before its read, which gives
     * {@code MVK_SEMANTIC_INDEX_NOT_ALLOWED} on all three. The empty scope at the root error field orders its
     * emptiness against its missing star.
     */
    @Test
    void theScopeIsReadWithTheSameClassesAsAKey() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Claims/ClaimRef @From Insured For \"k\"", "MVK_NO_INDEX_FIELD");
        expected.put("Claims/ClaimRef @From Claims For \"\"", "MVK_INDEX_VALUE_INVALID");
        expected.put("Claims/ClaimRef @From Claims For \"C-1\"", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inPolicy("RepetitionNotUnique(" + keys + ")")));

        Map<String, String> atRoot = new LinkedHashMap<>();
        atRoot.put("Reference @From Claims", "MVK_NO_WILDCARD");
        atRoot.put("Reference @From Riders", "MVK_NO_WILDCARD");
        atRoot.put("Reference @From Vacant", "MVK_NO_WILDCARD");
        atRoot.forEach((keys, verdict) -> {
            expected.put("root error field: " + keys, verdict);
            actual.put("root error field: " + keys,
                    KernelLaws.verdictModel(MODEL, "/Policy", "../Reference", "RepetitionNotUnique(" + keys + ")"));
        });
        assertEquals(expected, actual);
    }

    /**
     * A rule declared in {@code Claims} and reporting at the root reads a row of {@code Claims} per row, which
     * A12 refuses with {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP}: on a key, and on a scope. Rival for the scope
     * row: a scope is no per-row read, so the key outside it gives {@code MVK_NOT_IN_GROUP}.
     */
    @Test
    void aRuleReportingOutsideItsGroupRefusesAPerRowKeyAndScope() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ClaimRef", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        expected.put("/Policy/Reference @From /Policy/Claims", "MVK_ERROR_FIELD_NOT_IN_RULEGROUP");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, KernelLaws.verdictModel(MODEL, "/Policy/Claims",
                "../../Reference", "RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /**
     * The duplicate gate reads the list before the fields-only gate: a group named twice and the root beside a
     * key it contains, the classes [KF388] measures on the other carriers that build this list. Rival: the
     * fields-only gate first, which gives {@code MVK_NO_GROUPS_ALLOWED} on every row.
     */
    @Test
    void theDuplicateGateAnswersAheadOfTheFieldsOnlyGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Insured, Insured", "MVK_DUPLICATE_PARAM1");
        putBothOrders(expected, "/Policy", "Claims/ClaimRef", "MVK_DUPLICATE_PARAM2");
        expected.put("Insured, Insured/InsuredName", "MVK_DUPLICATE_PARAM2");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(keys -> actual.put(keys, inPolicy("RepetitionNotUnique(" + keys + ")")));
        assertEquals(expected, actual);
    }

    /** The construct at {@code group}, reporting at its first key, so the condition references its error field. */
    private static String reportingAtFirstKey(String group, String keys) {
        String first = keys.split("[, @]")[0];
        return KernelLaws.verdictModel(MODEL, group, "../" + first, "RepetitionNotUnique(" + keys + ")");
    }

    private static void putBothOrders(Map<String, String> rows, String first, String second, String verdict) {
        rows.put(first + ", " + second, verdict);
        rows.put(second + ", " + first, verdict);
    }
}
