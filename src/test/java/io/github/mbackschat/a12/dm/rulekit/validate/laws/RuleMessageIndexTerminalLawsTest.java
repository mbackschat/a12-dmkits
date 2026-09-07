package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §13 — <b>the {@code $index(…)$} message-parameter terminal</b>, a positive surface neither
 * estate had. [KF198] enumerated four parameter forms — a field, its value suffix, its category suffix, the
 * {@code BaseYear} terminal — and the semantic-index key; it never reached this one, which pins a message to
 * the FIRING ROW's declared index-field value.
 *
 * <p><b>The suffix is mandatory, and that is the whole reason the terminal reads as dead.</b> The grammar rule
 * makes the value/category suffix optional so the checker can give a better message than a parse error, so
 * {@code $index(ClaimNo)$} is refused and {@code $index(ClaimNo).value$} is admitted — one character apart,
 * with different classes. An account that attributes the refusal to the ARGUMENT's shape rather than to the
 * missing suffix gets every row below wrong in the same direction.
 *
 * <p><b>The argument is matched against the SHORT name of an index field on the error entity's path</b>, so no
 * path spelling can match and a rule whose error field sits at the root cannot reach the terminal at all. Both
 * are rows here rather than inferences from the mechanism.
 *
 * <p><b>Two parameter-free controls, one per error field</b>, so a refusal is never the route's: whatever the
 * error field, the same condition with no parameter is admitted.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-07-09}, correcting a no-witness clause of their own.
 * This estate carried no claim about the terminal in either direction, so these rows are new here too.
 *
 * <p>Unmeasured: what an admitted {@code index(…).value} RENDERS, the German arm, an Enumeration index field
 * (which is what would admit the category form), a composed index, and the computation carrier.
 *
 * <p>Own domain (a claims policy).
 */
class RuleMessageIndexTerminalLawsTest {

    private static final String ROOT_GROUP = "/Policy";
    private static final String CLAIMS = "/Policy/Claims";
    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String UNEXPECTED = "MVK_UNEXPECTED_TOKEN";
    private static final String WRONG_ARGUMENT = "INVALID_INDEX_FIELD";
    private static final String MISSING_SUFFIX = "INVALID_INDEX_FIELD_PARAMETER";

    private static final String POLICY = DocumentModelDraft.named("claims-index").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .group("Claims", claims -> claims.repeatable(20).indexField("ClaimNo")
                            .stringField("ClaimNo", f -> { })
                            .stringField("Payout", f -> { })))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(POLICY);

    /** A plain per-row read of the indexed group, from inside it — no semantic index anywhere. */
    private static final String READS_ROW = render(Dsl.fieldFilled(M.field("/Policy/Claims/ClaimNo"))
            .and(Dsl.fieldNotFilled(M.field("/Policy/Claims/Payout"))));

    /** The same shape with the error field at the ROOT, so the error entity's path carries no index field. */
    private static final String READS_ROOT = render(Dsl.fieldFilled(M.field("/Policy/Reference")));

    private static String render(Condition condition) {
        return new DslRenderer().render(condition);
    }

    /** In the repeating group: rule group {@code /Policy/Claims}, error field one of its own fields. */
    private static String inRow(String message) {
        return KernelLaws.messageVerdict(POLICY, CLAIMS, "../Payout", READS_ROW, "en_US", message);
    }

    /** At the root: rule group {@code /Policy}, error field a root field. */
    private static String atRoot(String message) {
        return KernelLaws.messageVerdict(POLICY, ROOT_GROUP, "../Reference", READS_ROOT, "en_US", message);
    }

    @Test
    void theIndexTerminalIsAdmittedAndItsSuffixIsMandatory() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("$index(ClaimNo).value$", inRow("row $index(ClaimNo).value$"));
        actual.put("$index(ClaimNo)$ — suffix omitted", inRow("row $index(ClaimNo)$"));
        actual.put("no parameter at all — control", inRow("row"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("$index(ClaimNo).value$", ADMITTED);
        expected.put("$index(ClaimNo)$ — suffix omitted", MISSING_SUFFIX);
        expected.put("no parameter at all — control", ADMITTED);
        assertEquals(expected, actual, "the terminal admits, and the suffix the grammar makes optional is not");
    }

    /**
     * The argument axis. Every row moves only the name inside the parentheses, and all four are refused under
     * one class — which is what separates "the argument is wrong" from "the suffix is missing" above.
     */
    @Test
    void theArgumentMustBeTheShortNameOfAnIndexFieldOnTheErrorEntitysPath() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a non-index field of the same group", inRow("row $index(Payout).value$"));
        actual.put("a field outside the group", inRow("row $index(Reference).value$"));
        actual.put("a name no field carries", inRow("row $index(Nope).value$"));
        actual.put("the index field spelled as a PATH", inRow("row $index(/Policy/Claims/ClaimNo).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a non-index field of the same group", WRONG_ARGUMENT);
        expected.put("a field outside the group", WRONG_ARGUMENT);
        expected.put("a name no field carries", WRONG_ARGUMENT);
        expected.put("the index field spelled as a PATH", WRONG_ARGUMENT);
        assertEquals(expected, actual, "the match is against the short name, on the error entity's own path");
    }

    /**
     * The spelling axis: the terminal, its suffix, and the German suffix token are all case- and
     * language-sensitive, and all three fail in the PARSER rather than at the checker.
     */
    @Test
    void theTerminalAndItsSuffixAreCaseAndLanguageSensitive() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("$Index(...)$ capitalised", inRow("row $Index(ClaimNo).value$"));
        actual.put("$....Value$ capitalised suffix", inRow("row $index(ClaimNo).Value$"));
        actual.put("$....Wert$ the German suffix under en_US", inRow("row $index(ClaimNo).Wert$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("$Index(...)$ capitalised", UNEXPECTED);
        expected.put("$....Value$ capitalised suffix", UNEXPECTED);
        expected.put("$....Wert$ the German suffix under en_US", UNEXPECTED);
        assertEquals(expected, actual, "these are lexer terminals, not names the checker resolves");
    }

    /**
     * The category alternative is LIVE and gated on the index field being an Enumeration — which this model's
     * String index field is not, so it reports the Enumeration class rather than a parse error. That is the
     * row proving the alternative exists at all; what an Enumeration index field admits is unmeasured.
     */
    @Test
    void theCategoryAlternativeIsLiveAndGatedOnAnEnumerationIndexField() {
        assertEquals("FIELD_NO_ENUM", inRow("row $index(ClaimNo)->Band$"));
    }

    /**
     * The error entity's path is what carries the index field, so the identical parameter is refused on a rule
     * whose error field sits at the root — with its own parameter-free control admitted, so the refusal is the
     * parameter's and not the rule's.
     */
    @Test
    void aRootErrorFieldCannotReachTheTerminal() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("$index(ClaimNo).value$ with a ROOT error field", atRoot("row $index(ClaimNo).value$"));
        actual.put("no parameter at all — control", atRoot("row"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("$index(ClaimNo).value$ with a ROOT error field", WRONG_ARGUMENT);
        expected.put("no parameter at all — control", ADMITTED);
        assertEquals(expected, actual, "the index field must sit on the ERROR entity's path");
    }
}
