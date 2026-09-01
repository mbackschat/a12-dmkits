package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * KERNEL-FINDINGS §3 — a field whose value fails formal conversion contributes <b>neither TRUE nor FALSE</b>
 * to a quantifier over its list. It is not dropped from the list, not counted filled, and not counted empty,
 * so a quantifier is decided exactly when the members whose state IS known already settle it — in <b>full</b>
 * validation and in partial alike.
 *
 * <p><b>Why this exists, and what it answers.</b> a12-kernel-lean's {@code SPEC-2026-08-31-05} records, from
 * the kernel's business documentation and with no measurement of their own, that such a field is not relevant
 * — and reads the consequence as "dropped from the LIST rather than counted on either side", adding that this
 * "reaches the same answer as classifying it neither-filled-nor-empty". Those two halves get different answers
 * here. <b>Full validation carries the rule: confirmed.</b> <b>The "dropped from the list" reading:
 * contradicted</b>, and the two readings are not equivalent — {@code AllFieldsFilled} over a list whose other
 * members are filled is where dropping predicts a fired rule and the measurement is silence.
 *
 * <p><b>Corrected 2026-08-31, and the correction is the interesting part.</b> This class first ran only
 * {@code AllFieldsFilled}, its negation, and the count at two values, all on one malformed document — and
 * concluded that an erroneous member "suppresses every quantifier over its list". a12-kernel-lean re-derived
 * the result independently and reported the row that refutes that wording: {@code AtLeastOneFieldFilled}
 * <b>fires</b> on the very same document. Every rule in the original set needed the erroneous member's state
 * to reach a verdict, and nothing in the set sat on the other side of that axis, so an account that fits every
 * row was still false — the set-level confound this project keeps measuring, this time in a set of four rules
 * rather than of documents. The fix is not one more row: it is two rules that a known member can settle
 * without B, and a second malformed document where the known members settle the opposite way.
 *
 * <p><b>Six rules × four documents, because silence is ambiguous.</b> A quantifier that is FALSE and one that
 * is UNKNOWN both look like a rule that did not fire, so no single rule distinguishes the readings; the grid
 * does. Rows 3 and 4 are the pair that matters — the same erroneous member, once where the known members
 * decide the positive quantifier and once where they decide the negative one.
 *
 * <table><caption>what each reading predicts for a three-member list with one erroneous member</caption>
 * <tr><th>reading</th><th>ALL</th><th>NOT_ALL</th><th>AT_LEAST_ONE</th><th>COUNT==3</th><th>COUNT==2</th></tr>
 * <tr><td>dropped from the list — a12-kernel-lean's</td><td>fires</td><td>—</td><td>fires</td><td>—</td><td>fires</td></tr>
 * <tr><td>counted as filled</td><td>fires</td><td>—</td><td>fires</td><td>fires</td><td>—</td></tr>
 * <tr><td>counted as empty</td><td>—</td><td>fires</td><td>fires</td><td>—</td><td>fires</td></tr>
 * <tr><td><b>neither, and only what needs it is undecided — measured</b></td><td>—</td><td>—</td><td>fires</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * <p><b>The near-silent rows need their own control, or they prove nothing.</b> Rules that do not fire is also
 * what a broken fixture looks like. Two clean documents fix what "filled" and "empty" look like on this
 * instrument, and a seventh rule reading only the untouched {@code Holder} — outside every quantified list, and
 * filled on all four documents — fires on every row.
 *
 * <p><b>Two error kinds, because one converter is a carrier.</b> A malformed number and an out-of-calendar
 * date reach the erroneous-entity path differently, and a rule measured only on {@code "abc"} in a NUMBER
 * field would be a claim about that converter wearing the name of the class.
 *
 * <p><b>Prior art this composes with.</b> {@code QuantifierMalformedDiffTest} holds kernel↔interpreter
 * agreement on several malformed-operand quantifiers, one rule per run — including the case where
 * {@code AtLeastOneFieldFilled} does NOT fire because no known member is filled, which is row 4 here.
 * [KF120] holds the GROUP carrier ("leaving erroneous groups in neither bucket"); the field carrier had no
 * characterization, and carrying a group result across is the scope transfer this project keeps measuring.
 *
 * <p>Own domain (policy premiums).
 */
class ErroneousFieldRelevanceLawsTest {

    private static final String GROUP = "/Policy";
    private static final String HOLDER = "/Policy/Holder";
    private static final String A = "/Policy/PremiumA";
    private static final String B = "/Policy/PremiumB";
    private static final String C = "/Policy/PremiumC";
    private static final String RENEWAL = "/Policy/RenewalDate";

    private static final String ALL = "ALL_FILLED";
    private static final String NOT_ALL = "NOT_ALL_FILLED";
    private static final String AT_LEAST_ONE = "AT_LEAST_ONE_FILLED";
    private static final String NONE = "NO_FIELD_FILLED";
    private static final String COUNT_3 = "COUNT_IS_3";
    private static final String COUNT_2 = "COUNT_IS_2";

    /**
     * Reads only {@code Holder}, which is outside every quantified list and filled on every document. It has
     * to be outside: the first version of this control read the list's own first member, which the fourth
     * document leaves empty — so the control would have gone silent exactly where it was needed most.
     */
    private static final String LIVENESS = "HOLDER_FILLED";

    private static final Set<String> OBSERVED =
            Set.of(ALL, NOT_ALL, AT_LEAST_ONE, NONE, COUNT_3, COUNT_2, LIVENESS);

    /** What a12-kernel-lean's {@code consequence} row predicts for row 3, named so the message can deny it. */
    private static final String DROPPED_FROM_THE_LIST = fired(ALL, AT_LEAST_ONE, COUNT_2);

    // ---- the number carrier ----

    /**
     * The finding on one operand list, over four documents. Rows 3 and 4 carry the same erroneous member and
     * differ only in what the other two members say, which is the whole rule.
     */
    @Test
    void anErroneousMemberSettlesNothingAndLeavesUndecidedOnlyWhatNeededIt() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all three valid — control", fired(ALL, AT_LEAST_ONE, COUNT_3));
        expected.put("B empty — control", fired(NOT_ALL, AT_LEAST_ONE, COUNT_2));
        expected.put("B malformed, A and C valid", fired(AT_LEAST_ONE));
        expected.put("B malformed, A and C empty", fired(NOT_ALL));

        assertEquals(expected, observe(numberModel(), numberDocuments()),
                "row 3 is not " + DROPPED_FROM_THE_LIST + " — AllFieldsFilled does not hold over the two clean "
                        + "members, so B is still in the list; and it is not silence either, since "
                        + "AtLeastOneFieldFilled fires on a known TRUE that never needed B");
    }

    /**
     * The same four documents through {@code validatePart} with the whole group relevant. a12-kernel-lean
     * records the rule for partial validation and asks whether full carries it too; running both on one
     * instrument is what turns that from two claims into one.
     */
    @Test
    void partialValidationReachesTheSameVerdictOnEveryDocument() {
        assertEquals(
                observe(numberModel(), numberDocuments()),
                observePart(numberModel(), numberDocuments()),
                "the exclusion is not a partial-validation artefact — the two validation kinds agree cell for "
                        + "cell, including on both malformed documents");
    }

    // ---- the date carrier ----

    /**
     * An out-of-calendar date in place of the malformed number. Same list position, same seven rules, a
     * different converter — so the result belongs to the class and not to the number converter.
     */
    @Test
    void anOutOfCalendarDateBehavesTheSameWay() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("renewal valid — control", fired(ALL, AT_LEAST_ONE, COUNT_3));
        expected.put("renewal empty — control", fired(NOT_ALL, AT_LEAST_ONE, COUNT_2));
        expected.put("renewal out of calendar, A and C valid", fired(AT_LEAST_ONE));
        expected.put("renewal out of calendar, A and C empty", fired(NOT_ALL));

        assertEquals(expected, observe(dateModel(), dateDocuments()),
                "the same four-row signature on a second converter");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static Map<String, RuntimeLaws.Doc> numberDocuments() {
        Map<String, RuntimeLaws.Doc> docs = new LinkedHashMap<>();
        docs.put("all three valid — control", doc(B, "2", "3", "4"));
        docs.put("B empty — control", doc(B, "2", null, "4"));
        docs.put("B malformed, A and C valid", doc(B, "2", "abc", "4"));
        docs.put("B malformed, A and C empty", doc(B, null, "abc", null));
        return docs;
    }

    private static Map<String, RuntimeLaws.Doc> dateDocuments() {
        Map<String, RuntimeLaws.Doc> docs = new LinkedHashMap<>();
        docs.put("renewal valid — control", doc(RENEWAL, "2", "01.07.2024", "4"));
        docs.put("renewal empty — control", doc(RENEWAL, "2", null, "4"));
        docs.put("renewal out of calendar, A and C valid", doc(RENEWAL, "2", "32.13.2024", "4"));
        docs.put("renewal out of calendar, A and C empty", doc(RENEWAL, null, "32.13.2024", null));
        return docs;
    }

    /** {@code Holder} is always filled — the liveness control's subject; a {@code null} value means unfilled. */
    private static RuntimeLaws.Doc doc(String middle, String a, String middleValue, String c) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group(GROUP, 1).field(HOLDER, "H", 1, 1);
        if (a != null) {
            d.field(A, a, 1, 1);
        }
        if (middleValue != null) {
            d.field(middle, middleValue, 1, 1);
        }
        if (c != null) {
            d.field(C, c, 1, 1);
        }
        return d;
    }

    private static String numberModel() {
        return quantifierRules(A, B, C);
    }

    private static String dateModel() {
        return quantifierRules(A, RENEWAL, C);
    }

    /**
     * One model carrying the six quantifier rules over {@code (first, middle, last)} plus the liveness
     * control, each with its own error code so a single validation run reports all seven verdicts.
     */
    private static String quantifierRules(String first, String middle, String last) {
        BoundModel m = BoundModel.ofJson(baseModel());
        List<FieldRef> fields = List.of(m.field(first), m.field(middle), m.field(last));
        String model = baseModel();
        model = withRule(model, ALL, "../PremiumA", Dsl.allFieldsFilled(fields));
        model = withRule(model, NOT_ALL, "../PremiumA", Dsl.notAllFieldsFilled(fields));
        model = withRule(model, AT_LEAST_ONE, "../PremiumA", Dsl.atLeastOneFieldFilled(fields));
        model = withRule(model, NONE, "../PremiumA", Dsl.noFieldFilled(fields));
        model = withRule(model, COUNT_3, "../PremiumA", Dsl.numberOfFilledFields(fields).eq(3));
        model = withRule(model, COUNT_2, "../PremiumA", Dsl.numberOfFilledFields(fields).eq(2));
        model = withRule(model, LIVENESS, "../Holder", m.field(HOLDER).filled());
        return model;
    }

    /**
     * The kernel refuses a rule whose error field its condition does not reference
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), so each rule's error field comes from inside its own condition
     * — the quantifiers use the list's first member, the liveness control uses {@code Holder}.
     */
    private static String withRule(String modelJson, String code, String errorRelPath, Condition condition) {
        return RuntimeLaws.withRule(modelJson, GROUP, code, errorRelPath, code,
                new DslRenderer().render(condition));
    }

    /** The premium list beside a String holder — a group of one field cannot tell a field rule from a group one. */
    private static String baseModel() {
        return DocumentModelDraft.named("policies").locale("en_US")
                .rootGroup("Policy", policy -> {
                    policy.field("Holder", FieldKind.STRING);
                    policy.field("PremiumA", FieldKind.NUMBER);
                    policy.field("PremiumB", FieldKind.NUMBER);
                    policy.field("PremiumC", FieldKind.NUMBER);
                    policy.dateField("RenewalDate", f -> f.format("dd.MM.yyyy"));
                })
                .build().toJson();
    }

    // ---- instruments ----

    private static Map<String, String> observe(String modelJson, Map<String, RuntimeLaws.Doc> documents) {
        Map<String, String> out = new LinkedHashMap<>();
        documents.forEach((name, doc) -> out.put(name, observedCodes(RuntimeLaws.firedCodes(modelJson, doc))));
        return out;
    }

    /** The same, under {@code validatePart} with the whole root group declared relevant. */
    private static Map<String, String> observePart(String modelJson, Map<String, RuntimeLaws.Doc> documents) {
        Map<String, String> out = new LinkedHashMap<>();
        documents.forEach((name, doc) -> out.put(name, observedCodes(RuntimeLaws.firedCodesPart(
                modelJson, doc, Set.of(RuntimeLaws.Relevant.of(GROUP, 0))))));
        return out;
    }

    private static String observedCodes(Set<String> fired) {
        Set<String> ours = new TreeSet<>(fired);
        ours.retainAll(OBSERVED);
        return ours.toString();
    }

    /** An expected row: the named codes plus the liveness control, which fires on every document. */
    private static String fired(String... codes) {
        Set<String> all = new TreeSet<>(List.of(codes));
        all.add(LIVENESS);
        return all.toString();
    }
}
