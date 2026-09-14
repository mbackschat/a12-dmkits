package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.edit.EditSession;
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
 * <p><b>Extended 2026-09-07 from a12-kernel-lean's {@code SPEC-2026-09-07-10}</b>, which read the checker and
 * found ten refusal classes where this file carried three. Three of their added axes are measured here — the
 * Enumeration index field that admits the {@code ->Category} alternative, the cross-check against the RULE's
 * OWN condition, and the non-repeatable-group class they could not author — each re-derived on our fixtures
 * rather than carried on their report. Recorded as [KF280].
 *
 * <p>Still unmeasured: what an admitted {@code index(…).value} RENDERS, the German arm, the computation
 * carrier, and the three cross-field consistency classes. A COMPOSED index is not unmeasured but
 * <b>unreachable</b>: see {@link #aComposedIndexIsUnreachableFromAnA12ModelSoItsClassCannotFire()}.
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

    /**
     * Two groups on ONE path, each declaring an index field of the same SHORT NAME.
     *
     * <p>The match in {@code checkIndexFieldRef} is by short name over every index field on the error
     * entity's path, so this is the shape where it finds two — the second of the terminal's five gates, and
     * the one no grid had reached.
     */
    private static final String AMBIGUOUS = DocumentModelDraft.named("claims-ambiguous-index").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .group("Claims", claims -> claims.repeatable(20).indexField("Ref")
                            .stringField("Ref", f -> { })
                            .group("Items", items -> items.repeatable(9).indexField("Ref")
                                    .stringField("Ref", f -> { })
                                    .stringField("Payout", f -> { }))))
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

    /**
     * The SECOND gate: a short name matching two index fields on the error entity's path.
     *
     * <p>Its own fixture, because the ambiguity is a property of the whole path and would change every other
     * row of the shared one. The control is the same parameter naming the UNambiguous index field.
     */
    @Test
    void aShortNameMatchingTwoIndexFieldsOnThePathIsAmbiguous() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no parameter at all — control", inAmbiguousRow("row"));
        actual.put("the short name both groups declare", inAmbiguousRow("row $index(Ref).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no parameter at all — control", ADMITTED);
        expected.put("the short name both groups declare", "INVALID_INDEX_FIELD_AMBIGUOUS");
        assertEquals(expected, actual, "a short name matching two index fields on the path is ambiguous");
    }

    /** In the inner repeating group, whose path carries BOTH index fields named `Ref`. */
    private static String inAmbiguousRow(String message) {
        BoundModel ambiguous = BoundModel.ofJson(AMBIGUOUS);
        return KernelLaws.messageVerdict(AMBIGUOUS, "/Policy/Claims/Items", "../Payout",
                render(Dsl.fieldFilled(ambiguous.field("/Policy/Claims/Items/Payout"))), "en_US", message);
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
        actual.put("a name no field carries, and NO suffix either", inRow("row $index(Nope)$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a non-index field of the same group", WRONG_ARGUMENT);
        expected.put("a field outside the group", WRONG_ARGUMENT);
        expected.put("a name no field carries", WRONG_ARGUMENT);
        expected.put("the index field spelled as a PATH", WRONG_ARGUMENT);
        // The ARGUMENT gate is the first of the five `checkIndexFieldRef` applies and the missing-suffix one
        // is the last, so an unknown name draws this class rather than `INVALID_INDEX_FIELD_PARAMETER` —
        // the row that lets a checker report this class without first reproducing the repeatability gates
        // that sit between them.
        expected.put("a name no field carries, and NO suffix either", WRONG_ARGUMENT);
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
    // ---- the axes a12-kernel-lean's SPEC-2026-09-07-10 added ------------------------------------

    /** The same shape with an ENUMERATION index field carrying a declared category. */
    private static final String ENUM_POLICY = enumPolicy(true);

    private static String enumPolicy(boolean repeating) {
        return DocumentModelDraft.named("enum-index").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .group("Claims", claims -> {
                        if (repeating) claims.repeatable(20);
                        claims.indexField("Tier")
                            .enumField("Tier", e -> e.value("BASIC").value("PREMIUM")
                                    .category("Band", "STANDARD", "PRIORITY"))
                            .stringField("Payout", f -> { });
                    }))
            .build().toJson();
    }

    private static final BoundModel E = BoundModel.ofJson(ENUM_POLICY);

    /** A plain per-row read of the ENUM index field — no star, no key. */
    private static final String READS_ENUM_ROW = render(Dsl.fieldFilled(E.field("/Policy/Claims/Tier"))
            .and(Dsl.fieldNotFilled(E.field("/Policy/Claims/Payout"))));

    private static String inEnumRow(String condition, String message) {
        return KernelLaws.messageVerdict(ENUM_POLICY, CLAIMS, "../Payout", condition, "en_US", message);
    }

    /**
     * The category alternative is not merely live, it is ADMITTED once the index field is an Enumeration —
     * the row {@link #theCategoryAlternativeIsLiveAndGatedOnAnEnumerationIndexField()} could not reach. An
     * undeclared category name is the discriminator: it separates "the arrow form is admitted here" from
     * "the arrow form is ignored here".
     */
    @Test
    void anEnumerationIndexFieldAdmitsTheCategoryAlternativeAndGatesTheCategoryName() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("$index(Tier).value$", inEnumRow(READS_ENUM_ROW, "row $index(Tier).value$"));
        actual.put("$index(Tier)->Band$ — declared", inEnumRow(READS_ENUM_ROW, "row $index(Tier)->Band$"));
        actual.put("$index(Tier)->Nope$ — undeclared", inEnumRow(READS_ENUM_ROW, "row $index(Tier)->Nope$"));
        actual.put("no parameter at all — control", inEnumRow(READS_ENUM_ROW, "row"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("$index(Tier).value$", ADMITTED);
        expected.put("$index(Tier)->Band$ — declared", ADMITTED);
        expected.put("$index(Tier)->Nope$ — undeclared", "INVALID_CATEGORY");
        expected.put("no parameter at all — control", ADMITTED);
        assertEquals(expected, actual,
                "an Explain consumer must resolve a category mapping at this terminal, not only a stored value");
    }

    /**
     * <b>The parameter is cross-checked against the RULE'S OWN CONDITION</b>, which is the axis a checker
     * validating the parameter against the model alone cannot see: the identical parameter is admitted beside
     * a plain reference and refused beside a starred or a {@code For}-keyed one. Each refused row's own
     * parameter-free control is admitted, so the condition is legal and the refusal is the parameter's.
     */
    @Test
    void theParameterIsCrossCheckedAgainstTheRulesOwnConditionsReferenceToTheIndexField() {
        // Both conditions are POSITIVE on the error field: a negative leaf beside an iterating read draws
        // MVK_NEG_CONDITION_IN_ITERATION, which would refuse the CONDITION and measure nothing about the
        // parameter (the harness's own control catches that, and did).
        String starred = render(Dsl.fieldFilled(E.field("/Policy/Claims/Payout"))
                .and(Dsl.atLeastOneFieldFilled(E.flatten("/Policy/Claims*/Tier"))));
        String keyed = render(Dsl.fieldFilled(E.field("/Policy/Claims/Payout"))
                .and(E.field("/Policy/Claims/Tier").semanticIndex("BASIC").filled()));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain reference — the admitted baseline",
                inEnumRow(READS_ENUM_ROW, "row $index(Tier).value$"));
        actual.put("a STARRED reference, control", inEnumRow(starred, "row"));
        actual.put("a STARRED reference", inEnumRow(starred, "row $index(Tier).value$"));
        actual.put("a For-KEYED reference, control", inEnumRow(keyed, "row"));
        actual.put("a For-KEYED reference", inEnumRow(keyed, "row $index(Tier).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain reference — the admitted baseline", ADMITTED);
        expected.put("a STARRED reference, control", ADMITTED);
        expected.put("a STARRED reference", "INVALID_INDEX_FIELD_REP_ITER");
        expected.put("a For-KEYED reference, control", ADMITTED);
        expected.put("a For-KEYED reference", "INVALID_INDEX_FIELD_REP_ITER");
        assertEquals(expected, actual,
                "validating the parameter against the model alone admits a parameter the kernel refuses");
    }

    /**
     * KF280 distinguishes an admitted index-field declaration from the refused message parameter;
     * only the repeatability builder call differs from the repeating control model.
     */
    @Test
    void aNonRepeatableIndexedGroupIsAdmittedByTheKernelAndRefusesTheParameter() {
        String nonRepeatable = enumPolicy(false);
        var claims = EditSession.read(nonRepeatable).group(CLAIMS);
        String condition = render(Dsl.fieldFilled(claims.field("Tier"))
                .and(Dsl.fieldNotFilled(claims.field("Payout"))));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the model itself — control", KernelLaws.messageVerdict(
                nonRepeatable, CLAIMS, "../Payout", condition, "en_US", "row"));
        actual.put("$index(Tier).value$", KernelLaws.messageVerdict(
                nonRepeatable, CLAIMS, "../Payout", condition, "en_US", "row $index(Tier).value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the model itself — control", ADMITTED);
        expected.put("$index(Tier).value$", "INVALID_INDEX_FIELD_NONREP_ENUM");
        assertEquals(expected, actual,
                "the kernel admits the DECLARATION and refuses the PARAMETER, so the class is the "
                        + "parameter's and our builder's earlier refusal has no kernel basis");
    }

    /**
     * <b>{@code INVALID_INDEX_FIELD_COMPOSED_INDEX} is UNREACHABLE from any A12 document model</b>, so there
     * is nothing to author and this row records the enumeration instead of a verdict. Its trigger is
     * {@code indexedGroup.getIndexFieldNames().size() > 1}, and every link that could make that set larger
     * than one was attacked in the 30.8.1 checkout (tracked sources, {@code /build/} and delombok excluded):
     *
     * <ul>
     * <li>{@code md-model}'s {@code Group} declares {@code indexFieldNames} {@code final} and writes it in
     *     ONE place, its constructor, from a single nullable {@code String} — exactly three occurrences of the
     *     name in the whole {@code kernel-md} tree (the {@code final} declaration plus that constructor's two
     *     writes), and no setter or adder.</li>
     * <li>That constructor has one caller, {@code ValidationModelConverter.createGroupFromGroupType}, which
     *     derives the name from {@code IGroup.getIndexField()} — an {@code Optional} of ONE reference.</li>
     * <li>So the DM-model surface has no plural spelling to serialize: a composed index is an H-model
     *     concept, which is consistent with the class existing at all.</li>
     * </ul>
     *
     * <p>Consequence for the tool: {@code dmtool group modify --index-field <fieldName>} taking exactly one
     * name is CORRECT, not the authoring limitation a12-kernel-lean's entry reads it as. Recall limit: this is
     * a source enumeration over the A12 path; another {@code IEC} implementation may populate several, and
     * a reflective writer would escape the search.
     */
    @Test
    void aComposedIndexIsUnreachableFromAnA12ModelSoItsClassCannotFire() {
        // The typed surface carries the same singleness, which is the half a test CAN assert: one name per
        // group, set by one call, with no plural spelling to reach for.
        assertEquals(1, java.util.Arrays.stream(
                        io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder.class.getMethods())
                .filter(m -> m.getName().equals("indexField")).count(),
                "one indexField setter, taking one name — the A12 model has no plural form to expose");
    }
}
