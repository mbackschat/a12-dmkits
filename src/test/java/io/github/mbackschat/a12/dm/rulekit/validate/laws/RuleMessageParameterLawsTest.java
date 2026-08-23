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
 * KERNEL-FINDINGS §13 — the rule-owned error-text <b>parameter</b> surface is far wider than
 * {@code $Field$} / {@code $Field.value$} / {@code $$}: it also admits an Enumeration <b>category</b>
 * suffix, a <b>Base Year</b> terminal with a signed offset, and a <b>semantic-index key</b> whose gate is
 * on the INDEX rather than on the field ([KF198]).
 *
 * <p><b>Why a verdict TABLE and not one assertion per row.</b> Every claim here is a claim about which of
 * several gates reports, so a row-by-row test aborts at the first divergence and hides the rest of the
 * matrix — exactly what a kernel upgrade needs you to see. Each test therefore collects the whole table and
 * asserts it once, the shape {@code KernelLaws.checkModel}'s javadoc prescribes.
 *
 * <p><b>The condition is held fixed and only the message varies</b>, through
 * {@link KernelLaws#messageVerdict}, which first proves the condition itself is admitted. Without that
 * control a refusal of the CONDITION reads as a verdict on the message.
 *
 * <p>The bilingual half of [KF198] — which parameter terminals differ between the two condition languages,
 * and what the other language's spelling does — is {@link RuleMessageParameterLanguageLawsTest}.
 */
class RuleMessageParameterLawsTest {

    private static final String GROUP = "/Policy";
    private static final String CLAIMS = "/Policy/Claims";
    private static final String ERROR_FIELD = "../Reference";
    private static final String ADMITTED = KernelLaws.ADMITTED;

    /** Declares a Base Year, so the {@code $BaseYear$} family is reachable. */
    private static final String POLICY = policy(2020);

    /** Byte-identical except for the Base Year — the control for {@code NO_BASE_YEAR}. */
    private static final String NO_BASE_YEAR = policy(null);

    private static final BoundModel M = BoundModel.ofJson(POLICY);

    /** Reads {@code Reference} only — every parameter naming another field meets the membership gate here. */
    private static final String READS_REFERENCE = render(Dsl.fieldFilled(M.field("/Policy/Reference")));

    /** Reads the Enumeration too, so a category or value suffix on it clears the membership gate. */
    private static final String READS_TIER = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(M.field("/Policy/Tier"))));

    /** Reads the Number, for the value-suffix rows. */
    private static final String READS_PREMIUM = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(M.field("/Policy/Premium"))));

    /** Reads {@code index}, a field whose name collides with an EXEMPT parameter terminal. */
    private static final String READS_INDEX = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(M.field("/Policy/index"))));

    /** Reads {@code For}, a field whose name collides with a NON-exempt terminal. */
    private static final String READS_FOR = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(M.field("/Policy/For"))));

    /** Reads ONE keyed cell of the indexed group — and nothing else from it. */
    private static final String READS_KEYED_PAYOUT = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(M.field("/Policy/Claims/Payout").semanticIndex("C-1").notFilled()));

    /** The same read keyed by a DIFFERENT literal. */
    private static final String READS_OTHER_KEY = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(M.field("/Policy/Claims/Payout").semanticIndex("C-9").notFilled()));

    /** The same read keyed by a FIELD rather than a literal. */
    private static final String READS_FIELD_KEYED = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(M.field("/Policy/Claims/Payout").semanticIndex(M.field("/Policy/Adjuster")).notFilled()));

    /** An ordinary per-row read of the indexed group, from inside it — no semantic index anywhere. */
    private static final String READS_ROW = render(Dsl.fieldFilled(M.field("/Policy/Claims/ClaimNo"))
            .and(Dsl.fieldNotFilled(M.field("/Policy/Claims/Payout"))));

    /** Reads the row Enumeration through a STAR only — the operand shape the value suffix refuses. */
    private static final String READS_STARRED_SEVERITY =
            render(Dsl.fieldFilled(M.field("/Policy/Reference"))
                    .and(Dsl.atLeastOneFieldFilled(M.flatten("/Policy/Claims*/Severity"))));

    // ---- the Enumeration category suffix ---------------------------------------------------------

    /**
     * {@code $Field->Category$} is a fourth parameter form, and its three gates report in a fixed order:
     * the arrow needs a name, the field must BE an Enumeration, and the name must be one it declares.
     */
    @Test
    void theCategorySuffixHasThreeOrderedGates() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("$Tier->Band$", verdict(POLICY, GROUP, READS_TIER, "see $Tier->Band$"));
        verdicts.put("$Tier->Nope$", verdict(POLICY, GROUP, READS_TIER, "see $Tier->Nope$"));
        verdicts.put("$Tier->$", verdict(POLICY, GROUP, READS_TIER, "see $Tier->$"));
        verdicts.put("$Premium->Band$", verdict(POLICY, GROUP, READS_PREMIUM, "see $Premium->Band$"));

        assertEquals(Map.of(
                "$Tier->Band$", ADMITTED,
                "$Tier->Nope$", "INVALID_CATEGORY",
                "$Tier->$", "NO_CATEGORY",
                "$Premium->Band$", "FIELD_NO_ENUM"), verdicts);
    }

    /**
     * The value suffix and the category suffix are ALTERNATIVES, not decorations of one another: combining
     * them fails to parse in either order.
     *
     * <p><b>And the doubled arrow does not have one class.</b> Which gate reports depends on the FIRST
     * name: a declared category reaches the trailing-syntax check ({@code MVK_UNEXPECTED_TOKEN}), an
     * undeclared one is rejected by the category gate first, and a non-Enumeration field by the kind gate
     * before either. So "a doubled arrow is a parse failure" and "the category gate reports first" are each
     * true of one row and neither generalizes — this table is what separates them.
     */
    @Test
    void theTwoSuffixesAreAlternativesAndADoubledArrowHasNoSingleClass() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("value then arrow", verdict(POLICY, GROUP, READS_TIER, "see $Tier.value->Band$"));
        verdicts.put("arrow then value", verdict(POLICY, GROUP, READS_TIER, "see $Tier->Band.value$"));
        verdicts.put("doubled, first DECLARED", verdict(POLICY, GROUP, READS_TIER, "see $Tier->Band->Band$"));
        verdicts.put("doubled, first UNDECLARED", verdict(POLICY, GROUP, READS_TIER, "see $Tier->Nope->Nope$"));
        verdicts.put("doubled, not an enum", verdict(POLICY, GROUP, READS_PREMIUM, "see $Premium->Band->Band$"));

        assertEquals(Map.of(
                "value then arrow", "MVK_UNEXPECTED_TOKEN",
                "arrow then value", "MVK_UNEXPECTED_TOKEN",
                "doubled, first DECLARED", "MVK_UNEXPECTED_TOKEN",
                "doubled, first UNDECLARED", "INVALID_CATEGORY",
                "doubled, not an enum", "FIELD_NO_ENUM"), verdicts);
    }

    /**
     * What the category form is exempt from is the value suffix's <b>non-starred</b> gate, NOT the field
     * membership every field parameter shares. The pair is a within-model control: one condition, one field,
     * only the suffix differs.
     */
    @Test
    void onlyTheValueSuffixNeedsANonStarredReference() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("plain", verdict(POLICY, GROUP, READS_STARRED_SEVERITY, "see $Claims/Severity$"));
        verdicts.put("value", verdict(POLICY, GROUP, READS_STARRED_SEVERITY, "see $Claims/Severity.value$"));
        verdicts.put("category", verdict(POLICY, GROUP, READS_STARRED_SEVERITY, "see $Claims/Severity->Band$"));

        assertEquals(Map.of(
                "plain", ADMITTED,
                "value", "PARAM_VALUE_WITH_STARREFERENCE",
                "category", ADMITTED), verdicts);
    }

    /** …while membership binds all three forms alike, so "neither of the value suffix's gates" is narrow. */
    @Test
    void fieldMembershipBindsEveryFieldFormIncludingTheCategoryOne() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("plain", verdict(POLICY, GROUP, READS_REFERENCE, "see $Tier$"));
        verdicts.put("category", verdict(POLICY, GROUP, READS_REFERENCE, "see $Tier->Band$"));

        assertEquals(Map.of("plain", "INVALID_FIELD", "category", "INVALID_FIELD"), verdicts);
    }

    // ---- the quoting rule ------------------------------------------------------------------------

    /**
     * The parameter producer's quoting rule is NARROWER than the condition language's: a field named
     * {@code index} needs no quotes in a message (the exempt list), while one named {@code For} does — and
     * an unnecessary quote is transparent.
     *
     * <p>The two field names also separate the two grammars. Both must be quoted in the CONDITION, which
     * {@link #READS_INDEX}'s own admission (asserted by the harness before every row) demonstrates for
     * {@code index}; only {@code For} must be quoted again in the message.
     */
    @Test
    void anExemptTerminalNeedsNoQuotesInAMessageButANonExemptOneDoes() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("index unquoted", verdict(POLICY, GROUP, READS_INDEX, "see $index$"));
        verdicts.put("index quoted", verdict(POLICY, GROUP, READS_INDEX, "see $'index'$"));
        verdicts.put("For unquoted", verdict(POLICY, GROUP, READS_FOR, "see $For$"));
        verdicts.put("For quoted", verdict(POLICY, GROUP, READS_FOR, "see $'For'$"));

        assertEquals(Map.of(
                "index unquoted", ADMITTED,
                "index quoted", ADMITTED,
                "For unquoted", "MVK_UNEXPECTED_TOKEN",
                "For quoted", ADMITTED), verdicts);
    }

    /**
     * The exemption settles the disambiguation the bare clause leaves open: {@code value} is NOT one of the
     * names a field may end with unquoted, so {@code $Premium.value$} is field + suffix and never a field
     * literally named {@code Premium.value}.
     */
    @Test
    void aTrailingValueTerminalIsTheSuffixAndNotPartOfTheName() {
        assertEquals(ADMITTED, verdict(POLICY, GROUP, READS_PREMIUM, "see $Premium.value$"));
    }

    // ---- the Base Year terminal ------------------------------------------------------------------

    /**
     * A parameter need not be a field at all: {@code $BaseYear$} with an optional signed offset is a
     * terminal of its own, gated only on the model declaring a Base Year, with no bound on the offset.
     */
    @Test
    void theBaseYearTerminalTakesAnUnboundedSignedOffset() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("bare", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear$"));
        verdicts.put("+3", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear+3$"));
        verdicts.put("-1", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear-1$"));
        verdicts.put("+9999", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear+9999$"));
        verdicts.put("no Base Year declared", verdict(NO_BASE_YEAR, GROUP, READS_REFERENCE, "see $BaseYear$"));
        verdicts.put("offset with no digits", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear+$"));
        verdicts.put("offset that is not a number", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear+x$"));
        verdicts.put("space instead of a sign", verdict(POLICY, GROUP, READS_REFERENCE, "see $BaseYear 3$"));

        assertEquals(Map.of(
                "bare", ADMITTED,
                "+3", ADMITTED,
                "-1", ADMITTED,
                "+9999", ADMITTED,
                "no Base Year declared", "NO_BASE_YEAR",
                "offset with no digits", "MVK_EXPECTED_TOKEN_NOT_FOUND",
                "offset that is not a number", "MVK_UNEXPECTED_TOKEN",
                "space instead of a sign", "MVK_UNEXPECTED_TOKEN"), verdicts);
    }

    // ---- the semantic-index key ------------------------------------------------------------------

    /**
     * <b>The load-bearing pair.</b> A keyed parameter's gate is on the INDEX, not on the field: against a
     * condition that reads only {@code Claims/Payout For "C-1"}, the keyed parameter naming a DIFFERENT
     * field of that group is admitted, while the unkeyed spelling of that same field is refused. Every
     * refusing row quotes a field and a key together, so only the admitted row separates the two accounts.
     */
    @Test
    void theKeyedParameterIsGatedOnTheIndexAndNotOnTheField() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("keyed, field ABSENT from the condition",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/ClaimNo For \"C-1\"$"));
        verdicts.put("unkeyed spelling of that same field",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/ClaimNo$"));
        verdicts.put("keyed the same way as the condition",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/Payout For \"C-1\"$"));

        assertEquals(Map.of(
                "keyed, field ABSENT from the condition", ADMITTED,
                "unkeyed spelling of that same field", "INVALID_FIELD",
                "keyed the same way as the condition", ADMITTED), verdicts);
    }

    /** The index must match the condition's — a different key, or none at all, is its own class. */
    @Test
    void theConditionMustUseTheSameIndexTheSameWay() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("condition keyed differently",
                verdict(POLICY, GROUP, READS_OTHER_KEY, "see $Claims/Payout For \"C-1\"$"));
        verdicts.put("condition reads the row plainly",
                verdict(POLICY, CLAIMS, READS_ROW, "see $Payout For \"C-1\"$", "../Payout"));
        verdicts.put("  …and its unkeyed control",
                verdict(POLICY, CLAIMS, READS_ROW, "see $Payout$", "../Payout"));
        verdicts.put("field-valued key, condition keyed the same",
                verdict(POLICY, GROUP, READS_FIELD_KEYED, "see $Claims/Payout For Adjuster$"));
        verdicts.put("literal key against a field-keyed condition",
                verdict(POLICY, GROUP, READS_FIELD_KEYED, "see $Claims/Payout For \"C-1\"$"));

        assertEquals(Map.of(
                "condition keyed differently", "MVK_INDEX_FOR_ERROR_TEXT_INVALID",
                "condition reads the row plainly", "MVK_INDEX_FOR_ERROR_TEXT_INVALID",
                "  …and its unkeyed control", ADMITTED,
                "field-valued key, condition keyed the same", ADMITTED,
                "literal key against a field-keyed condition", "MVK_INDEX_FOR_ERROR_TEXT_INVALID"), verdicts);
    }

    /** The key comes LAST, is a quoted literal or a field path, and may not itself be keyed. */
    @Test
    void theKeyComesLastAndCannotBeNested() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("suffix BEFORE the key",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/Payout.value For \"C-1\"$"));
        verdicts.put("suffix AFTER the key",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/Payout For \"C-1\".value$"));
        verdicts.put("unquoted bare-word key",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/Payout For C-1$"));
        verdicts.put("a key that is itself keyed",
                verdict(POLICY, GROUP, READS_KEYED_PAYOUT, "see $Claims/Payout For Claims/ClaimNo For \"C-1\"$"));

        assertEquals(Map.of(
                "suffix BEFORE the key", ADMITTED,
                "suffix AFTER the key", "MVK_UNEXPECTED_TOKEN",
                "unquoted bare-word key", "INVALID_ENTITY",
                "a key that is itself keyed", "ILLEGAL_SEMANTIC_INDEX"), verdicts);
    }

    // ---- fixture -----------------------------------------------------------------------------------

    private static String verdict(String json, String group, String condition, String message) {
        return verdict(json, group, condition, message, ERROR_FIELD);
    }

    private static String verdict(String json, String group, String condition, String message, String errorField) {
        return KernelLaws.messageVerdict(json, group, errorField, condition, "en_US", message);
    }

    private static String render(Condition condition) {
        return new DslRenderer().render(condition);
    }

    /**
     * A claims policy. {@code index} and {@code For} are deliberate name collisions with parameter
     * terminals — one exempt from the quoting rule, one not — and {@code Claims} is index-keyed so the
     * keyed-parameter rows have an index to be gated on.
     */
    private static String policy(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("claims-policy").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Policy", policy -> policy
                        .stringField("Reference", f -> { })
                        .numberField("Premium", f -> f.fractionDigits(0, 2))
                        .enumField("Tier", f -> f.value("BASIC").value("PREMIUM")
                                .category("Band", "STANDARD", "PRIORITY"))
                        .stringField("index", f -> { })
                        .stringField("For", f -> { })
                        .stringField("Adjuster", f -> { })
                        .group("Claims", claims -> claims.repeatable(20).indexField("ClaimNo")
                                .stringField("ClaimNo", f -> { })
                                .stringField("Payout", f -> { })
                                .enumField("Severity", f -> f.value("LOW").value("HIGH")
                                        .category("Band", "STANDARD", "PRIORITY"))))
                .build().toJson();
    }
}
