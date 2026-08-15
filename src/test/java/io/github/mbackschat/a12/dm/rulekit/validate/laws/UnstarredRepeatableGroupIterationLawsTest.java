package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>what actually gates an UNSTARRED repeatable group operand</b>, and what does not.
 *
 * <p>This file exists to retire a wrong conjunct we ourselves published. KERNEL-FINDINGS and
 * KERNEL-SEMANTICS both stated the admission as "only when the group is INDEX-keyed
 * ({@code indexFieldName}; otherwise {@code MVK_NO_WILDCARD}) <em>and</em> the error field sits inside it".
 * The fixture behind it varied both properties at once, so the index-keying half was never separated from
 * the locus half. {@link #indexKeyingDoesNotGateTheUnstarredRepeatableGroupOperand} is the separating
 * control: two models differing in <b>exactly one builder call</b>, and every verdict is identical.
 *
 * <p>The remaining two cases attack the <em>mechanism</em> rather than the admission, because the obvious
 * reading of {@code MVK_NEG_CONDITION_IN_ITERATION} — "negative quantifiers are refused inside an
 * iteration" — is refuted from both directions, and a second implementation that reasons from either the
 * operator's name or from the condition's truth set gets a different answer than the kernel:
 *
 * <ul>
 *   <li>{@code FieldsNotCollectivelyFilled} is grammatically negative and is <b>admitted</b>, while
 *       {@code NumberOfFilledFields(...) < 1} contains no negative operator at all and is <b>refused</b>
 *       — so the gate does not read the operator's polarity.</li>
 *   <li>{@code < 1} is refused where {@code <= 0} and {@code == 0} are admitted, though on a count that
 *       cannot go below zero all three denote the same predicate — so the gate does not read the
 *       condition's truth set either. Whatever the analysis is, it is <b>syntactic</b>, and it is the
 *       kernel's ({@code IterationNotValidVisitor}); we lock the observations and claim no mechanism.</li>
 * </ul>
 *
 * <p>Gate identity for the neighbouring <em>group</em>-list carriers stays in
 * {@link GroupListDiagnosticIdentityLawsTest}, whose own locus rows corroborate this one from a fixture
 * whose repeatable group never carried an index field at all.
 */
class UnstarredRepeatableGroupIterationLawsTest {

    /**
     * One model, one axis. {@code Items} is repeatable either way; the sole difference is whether its
     * {@code Sku} is declared the row key — which is the variable the published claim rested on.
     */
    private static String orders(boolean indexKeyed) {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("CustomerName", FieldKind.STRING)
                        .group("Items", i -> {
                            i.repeatable(50)
                                    .field("Sku", FieldKind.STRING)
                                    .field("Count", FieldKind.NUMBER);
                            if (indexKeyed) {
                                i.indexField("Sku");
                            }
                        }))
                .build().toJson();
    }

    private static final String INDEXED = orders(true);
    private static final String PLAIN = orders(false);

    /** Inside the repeatable group, and deliberately NOT its index field — see the second row block. */
    private static final String INSIDE = "../Items/Count";

    /** Inside AND the index field itself, so "is the index field" is separable from "is inside". */
    private static final String INSIDE_KEY = "../Items/Sku";

    /** Outside every repeatable group; the condition references it so the not-referenced gate stays shut. */
    private static final String OUTSIDE = "../CustomerName";

    // ── the refutation ──────────────────────────────────────────────────────────────────────────────

    /**
     * THE control. Same model, one builder call apart, so nothing but index-keying can explain a
     * difference — and there is none to explain. Both maps are asserted whole rather than row by row,
     * so a divergence anywhere in the matrix fails here rather than being sampled away.
     */
    @Test
    void indexKeyingDoesNotGateTheUnstarredRepeatableGroupOperand() {
        Map<String, String> indexed = matrix(INDEXED);

        // Comparing two computed maps passes vacuously if the probe degenerates — if `verdict` came back
        // constant, or the matrix collapsed to one row, equality would still hold and prove nothing. So
        // pin the shape and the spread first: this matrix is only evidence while it discriminates.
        assertEquals(24, indexed.size(), "4 quantifiers x 2 spellings x 3 error-field loci");
        assertEquals(Set.of("admitted", "MVK_NO_WILDCARD", "MVK_NEG_CONDITION_IN_ITERATION"),
                Set.copyOf(indexed.values()),
                "the matrix must span all three outcomes, or equality below is vacuous");

        assertEquals(indexed, matrix(PLAIN),
                "index-keying is not a gate: an unstarred repeatable group operand behaves identically "
                        + "whether or not the group declares an indexFieldName");
    }

    /** The full accept/reject surface, evaluated against whichever model is passed. */
    private static Map<String, String> matrix(String model) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String quantifier : new String[] {
                "AllFieldsFilled", "AtLeastOneFieldFilled", "NotAllFieldsFilled", "NoFieldFilled" }) {
            for (String star : new String[] { "", "*" }) {
                String body = quantifier + "(Items" + star + ")";
                String label = quantifier + " | Items" + star + " | ";
                actual.put(label + "error field inside", verdict(model, INSIDE, body));
                actual.put(label + "error field inside, is the index field", verdict(model, INSIDE_KEY, body));
                actual.put(label + "error field outside", verdict(model, OUTSIDE,
                        "FieldFilled(CustomerName) And " + body));
            }
        }
        return actual;
    }

    // ── what DOES gate it ───────────────────────────────────────────────────────────────────────────

    /**
     * The locus half of the published claim, which survives: an unstarred repeatable group operand is
     * admitted only where the error field sits inside that group. The starred rows are the control that
     * attributes the refusal to the missing star rather than to the operand list.
     */
    @Test
    void theErrorFieldLocusGatesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("bare | error field inside", verdict(INDEXED, INSIDE, "AllFieldsFilled(Items)"));
        actual.put("bare | error field outside", verdict(INDEXED, OUTSIDE,
                "FieldFilled(CustomerName) And AllFieldsFilled(Items)"));
        actual.put("starred | error field inside", verdict(INDEXED, INSIDE, "AllFieldsFilled(Items*)"));
        actual.put("starred | error field outside", verdict(INDEXED, OUTSIDE,
                "FieldFilled(CustomerName) And AllFieldsFilled(Items*)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("bare | error field inside", "admitted");
        expected.put("bare | error field outside", "MVK_NO_WILDCARD");
        expected.put("starred | error field inside", "admitted");
        expected.put("starred | error field outside", "admitted");
        assertEquals(expected, actual, "the error field's locus is what admits the unstarred operand");
    }

    /**
     * Once the operand is admitted, a second gate decides — and it is not the operator's polarity.
     * The two middle rows are the discriminating pair: a grammatically negative quantifier is admitted
     * while a comparison containing no negation at all is refused.
     */
    @Test
    void grammaticalPolarityDoesNotPredictTheIterationGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AllFieldsFilled — positive", verdict(INDEXED, INSIDE, "AllFieldsFilled(Items)"));
        actual.put("NotAllFieldsFilled — negative", verdict(INDEXED, INSIDE, "NotAllFieldsFilled(Items)"));
        actual.put("FieldsNotCollectivelyFilled — negative, yet admitted",
                verdict(INDEXED, INSIDE, "FieldsNotCollectivelyFilled(Items)"));
        actual.put("NumberOfFilledFields < 1 — no negation, yet refused",
                verdict(INDEXED, INSIDE, "NumberOfFilledFields(Items) < 1"));
        actual.put("MoreThanOneFieldFilled — positive", verdict(INDEXED, INSIDE, "MoreThanOneFieldFilled(Items)"));
        actual.put("NotExactlyOneFieldFilled — negative", verdict(INDEXED, INSIDE, "NotExactlyOneFieldFilled(Items)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AllFieldsFilled — positive", "admitted");
        expected.put("NotAllFieldsFilled — negative", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("FieldsNotCollectivelyFilled — negative, yet admitted", "admitted");
        expected.put("NumberOfFilledFields < 1 — no negation, yet refused", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("MoreThanOneFieldFilled — positive", "admitted");
        expected.put("NotExactlyOneFieldFilled — negative", "MVK_NEG_CONDITION_IN_ITERATION");
        assertEquals(expected, actual, "the iteration gate does not read the operator's polarity");
    }

    /**
     * …nor the condition's truth set. {@code NumberOfFilledFields} counts, so it is never negative, and
     * {@code < 1}, {@code <= 0} and {@code == 0} are therefore the same predicate — yet the first is
     * refused and the other two admitted. That pair alone forbids reimplementing this gate semantically.
     */
    @Test
    void theIterationGateIsSyntacticNotSemantic() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String comparison : new String[] {
                "< 1", "<= 0", "== 0", "< 2", "<= 1", "!= 1", ">= 0", "> 0", ">= 1" }) {
            actual.put(comparison, verdict(INDEXED, INSIDE, "NumberOfFilledFields(Items) " + comparison));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("< 1", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("<= 0", "admitted");
        expected.put("== 0", "admitted");
        expected.put("< 2", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("<= 1", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("!= 1", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put(">= 0", "admitted");
        expected.put("> 0", "admitted");
        expected.put(">= 1", "admitted");
        assertEquals(expected, actual,
                "'< 1' and '<= 0' denote the same predicate on a count and get opposite verdicts");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String model, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(model, "/Order", errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
