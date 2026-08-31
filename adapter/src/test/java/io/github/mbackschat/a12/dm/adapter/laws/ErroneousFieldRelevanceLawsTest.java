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
 * KERNEL-FINDINGS §3 — a field whose value fails formal conversion <b>suppresses every quantifier over the
 * list it sits in</b>. It is not dropped from the list, not counted filled, and not counted empty: no rule
 * over that list reaches a verdict at all, in <b>full</b> validation and in partial alike.
 *
 * <p><b>Why this exists, and what it answers.</b> a12-kernel-lean's {@code SPEC-2026-08-31-05} records, from
 * the kernel's business documentation and with no measurement of their own, that such a field is not relevant
 * — and reads the consequence as "dropped from the LIST rather than counted on either side", adding that this
 * "reaches the same answer as classifying it neither-filled-nor-empty". The two halves get different answers
 * here. <b>Full validation carries the rule: confirmed</b>, and this project can settle it because it can run
 * {@code validatePart} through the adapter, which their accepted probe route cannot reach. <b>The "dropped
 * from the list" reading: contradicted</b>, and the two readings are not equivalent — the discriminating cell
 * is {@code AllFieldsFilled} over a list whose other members are filled, where dropping predicts a fired rule
 * and the measurement is silence.
 *
 * <p><b>Three states, so silence is not read as FALSE.</b> A quantifier that is FALSE and one that is UNKNOWN
 * both look like a rule that did not fire, and dropping the field produces the same answer as counting it
 * filled on the positive quantifier alone. Four rules therefore run on every document, and only the
 * combination separates the four readings:
 *
 * <table><caption>how each reading would show up on a three-field list with one erroneous member</caption>
 * <tr><th>reading</th><th>ALL</th><th>NOT_ALL</th><th>COUNT==3</th><th>COUNT==2</th></tr>
 * <tr><td>dropped from the list — a12-kernel-lean's</td><td>fires</td><td>—</td><td>—</td><td>fires</td></tr>
 * <tr><td>counted as filled</td><td>fires</td><td>—</td><td>fires</td><td>—</td></tr>
 * <tr><td>counted as empty</td><td>—</td><td>fires</td><td>—</td><td>fires</td></tr>
 * <tr><td><b>suppresses the quantifier — measured</b></td><td>—</td><td>—</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * <p><b>The all-silent row needs its own control, or it proves nothing.</b> Four rules that do not fire is
 * also what a broken fixture looks like. Two clean documents fix what "filled" and "empty" look like on this
 * instrument, and a fifth rule reading only the untouched {@code PremiumA} fires on <em>every</em> document
 * including the malformed one — so the silence is scoped to the lists containing the error rather than being
 * a document the kernel gave up on.
 *
 * <p><b>Two error kinds, because one converter is a carrier.</b> A malformed number and an out-of-calendar
 * date reach the erroneous-entity path differently, and a rule measured only on {@code "abc"} in a NUMBER
 * field would be a claim about that converter wearing the name of the class.
 *
 * <p><b>Prior art this sharpens rather than repeats.</b> {@code QuantifierMalformedDiffTest} already holds
 * kernel↔interpreter agreement on several malformed-operand quantifiers, including the discriminating
 * {@code AllFieldsFilled} shape — but as a differential, one rule per run, so it establishes that the two
 * engines agree and never states which of the four readings the kernel holds. [KF120] holds the GROUP carrier
 * ("negative tallies count only clean empty groups, leaving erroneous groups in neither bucket"); the field
 * carrier had no characterization, and carrying a group result across is the scope transfer this project
 * keeps measuring.
 *
 * <p>Own domain (policy premiums).
 */
class ErroneousFieldRelevanceLawsTest {

    private static final String GROUP = "/Policy";
    private static final String A = "/Policy/PremiumA";
    private static final String B = "/Policy/PremiumB";
    private static final String C = "/Policy/PremiumC";
    private static final String RENEWAL = "/Policy/RenewalDate";

    private static final String ALL = "ALL_FILLED";
    private static final String NOT_ALL = "NOT_ALL_FILLED";
    private static final String COUNT_3 = "COUNT_IS_3";
    private static final String COUNT_2 = "COUNT_IS_2";

    /** Reads only {@code PremiumA}, which is valid and filled on every document — the liveness control. */
    private static final String SCOPE = "PREMIUM_A_FILLED";

    private static final Set<String> OBSERVED = Set.of(ALL, NOT_ALL, COUNT_3, COUNT_2, SCOPE);

    /** The four signatures the table above enumerates, as this instrument renders them. */
    private static final String ALL_THREE_FILLED = "[" + ALL + ", " + COUNT_3 + ", " + SCOPE + "]";
    private static final String ONE_EMPTY = "[" + COUNT_2 + ", " + NOT_ALL + ", " + SCOPE + "]";
    private static final String DROPPED_FROM_THE_LIST = "[" + ALL + ", " + COUNT_2 + ", " + SCOPE + "]";
    private static final String QUANTIFIERS_SUPPRESSED = "[" + SCOPE + "]";

    // ---- the number carrier ----

    /**
     * The whole finding on one operand list. {@link #DROPPED_FROM_THE_LIST} is what a12-kernel-lean's
     * consequence predicts and is deliberately named here so the assertion message can say which reading
     * failed rather than only that a map differed.
     */
    @Test
    void aMalformedNumberSuppressesEveryQuantifierOverItsListRatherThanBeingDropped() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("all three valid — control", firedFull(numberModel(), doc("2", "3", "4")));
        actual.put("B empty — control", firedFull(numberModel(), doc("2", null, "4")));
        actual.put("B malformed", firedFull(numberModel(), doc("2", "abc", "4")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("all three valid — control", ALL_THREE_FILLED);
        expected.put("B empty — control", ONE_EMPTY);
        expected.put("B malformed", QUANTIFIERS_SUPPRESSED);

        assertEquals(expected, actual,
                "the malformed row is " + QUANTIFIERS_SUPPRESSED + " and not " + DROPPED_FROM_THE_LIST
                        + ": AllFieldsFilled does not hold over the two clean members, so the erroneous field "
                        + "is still in the list");
    }

    /**
     * The same three documents through {@code validatePart} with the whole group relevant. a12-kernel-lean
     * records the rule for partial validation and asks whether full carries it too; running both on one
     * instrument is what turns that from two claims into one.
     */
    @Test
    void partialValidationReachesTheSameVerdictOnEveryDocument() {
        Map<String, String> full = new LinkedHashMap<>();
        Map<String, String> part = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeLaws.Doc> e : documents().entrySet()) {
            full.put(e.getKey(), firedFull(numberModel(), e.getValue()));
            part.put(e.getKey(), firedPart(numberModel(), e.getValue()));
        }

        assertEquals(full, part,
                "the exclusion is not a partial-validation artefact — the two validation kinds agree cell for "
                        + "cell, including on the malformed document");
    }

    // ---- the date carrier ----

    /**
     * An out-of-calendar date in place of the malformed number. Same list position, same five rules, a
     * different converter — so the result belongs to the class and not to the number converter.
     */
    @Test
    void anOutOfCalendarDateSuppressesThemTheSameWay() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("renewal valid — control", firedFull(dateModel(), dateDoc("01.07.2024")));
        actual.put("renewal empty — control", firedFull(dateModel(), dateDoc(null)));
        actual.put("renewal out of calendar", firedFull(dateModel(), dateDoc("32.13.2024")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("renewal valid — control", ALL_THREE_FILLED);
        expected.put("renewal empty — control", ONE_EMPTY);
        expected.put("renewal out of calendar", QUANTIFIERS_SUPPRESSED);

        assertEquals(expected, actual, "the same signature on a second converter");
    }

    // ---- fixtures ----

    private static Map<String, RuntimeLaws.Doc> documents() {
        Map<String, RuntimeLaws.Doc> docs = new LinkedHashMap<>();
        docs.put("all three valid — control", doc("2", "3", "4"));
        docs.put("B empty — control", doc("2", null, "4"));
        docs.put("B malformed", doc("2", "abc", "4"));
        return docs;
    }

    /** The three premium fields, {@code B} optionally absent — an absent path is an unfilled field. */
    private static RuntimeLaws.Doc doc(String a, String b, String c) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group(GROUP, 1).field(A, a, 1, 1).field(C, c, 1, 1);
        return b == null ? d : d.field(B, b, 1, 1);
    }

    private static RuntimeLaws.Doc dateDoc(String renewal) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group(GROUP, 1).field(A, "2", 1, 1).field(C, "4", 1, 1);
        return renewal == null ? d : d.field(RENEWAL, renewal, 1, 1);
    }

    private static String numberModel() {
        return quantifierRules(A, B, C);
    }

    private static String dateModel() {
        return quantifierRules(A, RENEWAL, C);
    }

    /**
     * One model carrying the four quantifier rules over {@code (first, middle, last)} plus the liveness
     * control, each with its own error code so a single validation run reports all five verdicts.
     */
    private static String quantifierRules(String first, String middle, String last) {
        BoundModel m = BoundModel.ofJson(baseModel());
        List<FieldRef> fields = List.of(m.field(first), m.field(middle), m.field(last));
        String model = baseModel();
        model = withRule(model, ALL, Dsl.allFieldsFilled(fields));
        model = withRule(model, NOT_ALL, Dsl.notAllFieldsFilled(fields));
        model = withRule(model, COUNT_3, Dsl.numberOfFilledFields(fields).eq(3));
        model = withRule(model, COUNT_2, Dsl.numberOfFilledFields(fields).eq(2));
        model = withRule(model, SCOPE, m.field(A).filled());
        return model;
    }

    /**
     * The error field is {@code PremiumA} — the list's first member and never the erroneous one. It cannot be
     * the {@code Holder} sibling: the kernel refuses a rule whose error field its condition does not reference
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), so the error field has to come from inside the quantified list.
     */
    private static String withRule(String modelJson, String code, Condition condition) {
        return RuntimeLaws.withRule(modelJson, GROUP, code, "../PremiumA", code,
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

    /** The five observed codes that fired under {@code validateFull}, sorted; other codes are not the subject. */
    private static String firedFull(String modelJson, RuntimeLaws.Doc doc) {
        return observedCodes(RuntimeLaws.firedCodes(modelJson, doc));
    }

    /** The same, under {@code validatePart} with the whole root group declared relevant. */
    private static String firedPart(String modelJson, RuntimeLaws.Doc doc) {
        return observedCodes(RuntimeLaws.firedCodesPart(modelJson, doc,
                Set.of(RuntimeLaws.Relevant.of(GROUP, 0))));
    }

    private static String observedCodes(Set<String> fired) {
        Set<String> ours = new TreeSet<>(fired);
        ours.retainAll(OBSERVED);
        return ours.toString();
    }
}
