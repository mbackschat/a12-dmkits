package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS — the rule-message <b>{@code $#…$} GROUP position</b>, which resolves the same words
 * differently from the parameter NAME position [KF198] covers. There {@code RootGroup} draws
 * {@code INVALID_ENTITY} and {@code RuleGroup} draws {@code INVALID_PARAMETER}; here both are admitted
 * shorthands. Reported as {@code SPEC-2026-08-29-04} by a12-kernel-lean and measured here.
 *
 * <p><b>The admitted set is the rule's own group plus its ancestors</b> — containment running the opposite way
 * from the computation declaring-group gate ({@link ComputationDeclaringGroupLawsTest}): there the written
 * field must lie at or below the declaring group, here the NAMED group must contain the rule's.
 *
 * <p><b>The condition is positive on purpose.</b> A negative condition inside an iterating rule is refused
 * {@code MVK_NEG_CONDITION_IN_ITERATION} before the error-text checker runs, so every row would report the
 * condition's verdict instead of the message's. {@code KernelLaws.messageVerdict} additionally runs a
 * plain-text control per row, which is what makes that a caught mistake rather than a silent one.
 *
 * <p>Own domain (an insurance policy with a separate ledger root).
 */
class RuleMessageGroupParameterLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String INVALID_GROUP = "INVALID_GROUP";
    private static final String INVALID_ROOT_GROUP = "INVALID_ROOT_GROUP";

    /**
     * Two roots, and under the first an ancestor chain deep enough to separate "the rule's own group", "a
     * parent", and "the chain's root" — plus a descendant of the rule's group, a sibling of it, and a branch
     * that shares only the root.
     */
    private static final String POLICY = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", p -> p
                    .group("Cover", c -> c
                            .group("Sections", s -> s.repeatable(20)
                                    .field("SectionNo", FieldKind.STRING)
                                    .numberField("Amount", n -> n.fractionDigits(0))
                                    .group("Details", d -> d.field("Note", FieldKind.STRING)))
                            .group("Exclusions", e -> e.field("ExclusionNote", FieldKind.STRING)))
                    .group("Admin", a -> a.field("AdminRef", FieldKind.STRING)))
            .rootGroup("Ledger", l -> l
                    .group("Entries", e -> e
                            .field("EntryRef", FieldKind.STRING)
                            .numberField("EntryAmount", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final String RULE_GROUP = "/Policy/Cover/Sections";
    private static final String ERROR_FIELD = "../Amount";
    private static final String CONDITION = "FieldFilled(/Policy/Cover/Sections/Amount)";

    /** The verdict on one {@code $#…$} argument, with everything else held fixed. */
    private static String groupParam(String argument) {
        return KernelLaws.messageVerdict(POLICY, RULE_GROUP, ERROR_FIELD, CONDITION, "en_US",
                "Section row $#" + argument + "$ needs an amount.");
    }

    /** The admitted set: the rule's own group, every ancestor of it, and the two keyword shorthands. */
    @Test
    void theGroupPositionAdmitsTheRulesOwnGroupItsAncestorsAndTheTwoShorthands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the rule's own group", groupParam(RULE_GROUP));
        actual.put("its parent", groupParam("/Policy/Cover"));
        actual.put("the chain's root", groupParam("/Policy"));
        actual.put("RuleGroup — the near endpoint", groupParam("RuleGroup"));
        actual.put("RootGroup — the far endpoint", groupParam("RootGroup"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the rule's own group", ADMITTED);
        expected.put("its parent", ADMITTED);
        expected.put("the chain's root", ADMITTED);
        expected.put("RuleGroup — the near endpoint", ADMITTED);
        expected.put("RootGroup — the far endpoint", ADMITTED);
        assertEquals(expected, actual, "the named group must CONTAIN the rule's group");
    }

    /** Everything a declared root contains that does not contain the rule — one code, four relations. */
    @Test
    void aDeclaredGroupThatDoesNotContainTheRuleIsRefusedOnContainment() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a descendant of the rule's group", groupParam("/Policy/Cover/Sections/Details"));
        actual.put("a sibling of the rule's group", groupParam("/Policy/Cover/Exclusions"));
        actual.put("a branch sharing only the root", groupParam("/Policy/Admin"));
        actual.put("a group under the OTHER declared root", groupParam("/Ledger/Entries"));
        actual.put("the other declared root itself", groupParam("/Ledger"));
        actual.put("a relative spelling of a real ancestor", groupParam("Cover"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a descendant of the rule's group", INVALID_GROUP);
        expected.put("a sibling of the rule's group", INVALID_GROUP);
        expected.put("a branch sharing only the root", INVALID_GROUP);
        expected.put("a group under the OTHER declared root", INVALID_GROUP);
        expected.put("the other declared root itself", INVALID_GROUP);
        expected.put("a relative spelling of a real ancestor", INVALID_GROUP);
        assertEquals(expected, actual, "the position takes an ABSOLUTE path, and containment decides");
    }

    /**
     * An absolute argument resolves in <b>two gates the kernel reports apart</b>: the first segment must name a
     * declared root, whatever follows it, and only under a declared root does containment decide.
     */
    @Test
    void anUndeclaredFirstSegmentIsItsOwnGateAndTheMatchIsCaseSensitive() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an undeclared root, alone", groupParam("/Nope"));
        actual.put("an undeclared root, with a child", groupParam("/Nope/Sections"));
        actual.put("the right root in the wrong case", groupParam("/policy"));
        actual.put("a declared root, non-containing child — control", groupParam("/Policy/Admin"));
        actual.put("a declared root, UNDECLARED child", groupParam("/Policy/NoSuchGroup"));
        actual.put("a declared root, and a FIELD rather than a group", groupParam("/Policy/Admin/AdminRef"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an undeclared root, alone", INVALID_ROOT_GROUP);
        expected.put("an undeclared root, with a child", INVALID_ROOT_GROUP);
        expected.put("the right root in the wrong case", INVALID_ROOT_GROUP);
        expected.put("a declared root, non-containing child — control", INVALID_GROUP);
        // PREDICTED `INVALID_ENTITY` for the undeclared child, from a source read of
        // `CheckIdNameImpl.checkAndCreateFullName`, whose absolute arm answers that for an unresolvable
        // candidate. Measured `INVALID_GROUP`: in this position `resolveAndStoreEntity`'s group branch is
        // what answers, so the code does NOT turn on whether the address exists. Both cells collapse onto
        // one class, which makes the law simpler than the chain it is written as — and is why a checker may
        // test containment alone here without first asking whether the group is there.
        expected.put("a declared root, UNDECLARED child", INVALID_GROUP);
        expected.put("a declared root, and a FIELD rather than a group", INVALID_GROUP);
        assertEquals(expected, actual, "the root gate answers before containment is asked");
    }

    /**
     * The quoting rule, and the separator it does <b>not</b> hide: a quoted segment reaches the same group as
     * its bare spelling, while a quoted KEYWORD stops being one. The terminal match is therefore on the RAW
     * argument rather than on a quote-stripped one — which is the separator between the two tempting accounts
     * of the escape.
     */
    @Test
    void quotingASegmentIsTransparentButQuotingAKeywordRemovesItsTerminalReading() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("every segment quoted", groupParam("/'Policy'/'Cover'"));
        actual.put("RootGroup quoted", groupParam("'RootGroup'"));
        actual.put("RuleGroup quoted", groupParam("'RuleGroup'"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("every segment quoted", ADMITTED);
        expected.put("RootGroup quoted", INVALID_GROUP);
        expected.put("RuleGroup quoted", INVALID_GROUP);
        assertEquals(expected, actual, "a quoted keyword is looked up as a group name of that spelling");
    }

    /** A malformed spelling is refused LEXICALLY, before either resolution gate — on three distinct classes. */
    @Test
    void aMalformedGroupArgumentIsRefusedOnItsOwnLexicalClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unbalanced quote", groupParam("/'Policy"));
        actual.put("an empty quoted segment", groupParam("/''"));
        actual.put("a trailing separator", groupParam("/Policy/"));
        actual.put("a doubled separator", groupParam("/Policy//Cover"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unbalanced quote", "MVK_LEXER_STANDARD_ERROR");
        expected.put("an empty quoted segment", "MVK_LEXER_STANDARD_ERROR");
        expected.put("a trailing separator", "MVK_INCOMPLETE_INPUT");
        expected.put("a doubled separator", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "lexing answers before resolution");
    }

    /**
     * Two of the retired terminals get their own code here, which is what shows the kernel RECOGNISES them and
     * refuses them on the modern route rather than failing to find a group of that name. The remaining rows are
     * the control that separates "recognised and retired" from "not a terminal in this position at all" — the
     * form-line and form-name words, and an index expression, are looked up as group names like anything else.
     */
    @Test
    void theRetiredTerminalsAreRecognisedAndRefusedRatherThanLookedUp() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Zeile", groupParam("Zeile"));
        actual.put("Usb", groupParam("Usb"));
        actual.put("Zeile quoted", groupParam("'Zeile'"));
        actual.put("Vordruckzeile", groupParam("Vordruckzeile"));
        actual.put("Vordruckname", groupParam("Vordruckname"));
        actual.put("index(1)", groupParam("index(1)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Zeile", "PARAM_INVALID_IN_NEW_WORLD");
        expected.put("Usb", "PARAM_INVALID_IN_NEW_WORLD");
        expected.put("Zeile quoted", INVALID_GROUP);
        expected.put("Vordruckzeile", INVALID_GROUP);
        expected.put("Vordruckname", INVALID_GROUP);
        expected.put("index(1)", INVALID_GROUP);
        assertEquals(expected, actual, "a recognised-but-retired terminal is not a missing group");
    }

    /**
     * {@code RootGroup} names the root of the <b>rule's own</b> ancestor chain, not a model-wide root. The
     * second root is what separates the readings: a rule declared under it admits the shorthand while the
     * FIRST-declared root draws {@code INVALID_GROUP} from there.
     *
     * <p>Admission alone cannot separate "the chain's root" from "admitted unconditionally", since the chain
     * root always contains the rule — so the refusal row is the one carrying the claim.
     */
    @Test
    void theRootGroupShorthandIsTheRulesOwnChainRootAndNotTheModelsFirstRoot() {
        String ledgerCondition = "FieldFilled(/Ledger/Entries/EntryAmount)";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("RootGroup, from a rule under the SECOND root",
                KernelLaws.messageVerdict(POLICY, "/Ledger/Entries", "../EntryAmount", ledgerCondition, "en_US",
                        "Entry $#RootGroup$ needs an amount."));
        actual.put("the FIRST root by name, from that same rule",
                KernelLaws.messageVerdict(POLICY, "/Ledger/Entries", "../EntryAmount", ledgerCondition, "en_US",
                        "Entry $#/Policy$ needs an amount."));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RootGroup, from a rule under the SECOND root", ADMITTED);
        expected.put("the FIRST root by name, from that same rule", INVALID_GROUP);
        assertEquals(expected, actual, "the shorthand is chain-relative, so a model-wide reading is excluded");
    }

    /**
     * <b>{@code RuleGroup} is refused when the rule sits in a ROOT group, even though the rule's own group is
     * in the admitted set from everywhere else</b> (added 2026-09-05). Every fixture above puts the rule three
     * levels down, so "the rule's own group plus its ancestors, at any depth" was stated over a population that
     * never reached the root locus — the constant no row inside that set could expose.
     *
     * <p><b>The gate belongs to the WORD, not to the position.</b> The two rows beside it name the very same
     * group and are admitted: {@code RootGroup} resolves to it as the chain root, and the absolute
     * {@code /Order} names it outright. So the kernel is not refusing a reference to that group — it is
     * refusing that one spelling, on its own code, which is why this does not weaken the containment account.
     *
     * <p><b>The control moves exactly one variable.</b> Same model, same message, same shape of condition, the
     * rule one level lower — and the shorthand is admitted. Two models could not say that: they differ in
     * every way nobody thought about.
     *
     * <p>Reported for the COMPUTATION carrier by a12-kernel-lean, whose {@code spec/11-messages-and-custom.md}
     * flagged the validation-rule crossing as unmeasured; they then measured that crossing and told us. This
     * row is our own measurement of it, on our own carrier — their report is what prompted it, not its basis.
     */
    @Test
    void theRuleGroupShorthandIsRefusedAtTheROOTLocusWhileTheSameGroupIsNameableTwoOtherWays() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("RuleGroup, rule IN the root group", atRootLocus("RuleGroup"));
        actual.put("RootGroup, same rule", atRootLocus("RootGroup"));
        actual.put("the root by absolute path, same rule", atRootLocus("/Order"));
        actual.put("RuleGroup, rule ONE LEVEL down — control", oneLevelDown("RuleGroup"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RuleGroup, rule IN the root group", "RULEGROUP_IMMEDIATELY_BELOW_ROOT");
        expected.put("RootGroup, same rule", ADMITTED);
        expected.put("the root by absolute path, same rule", ADMITTED);
        expected.put("RuleGroup, rule ONE LEVEL down — control", ADMITTED);
        assertEquals(expected, actual, "the refusal is of the WORD at that locus, not of the group it names");
    }

    /**
     * A second, deliberately shallow model so the rule can sit either directly in the root group or one level
     * below it, leaving the rule's LOCUS as the only difference between the rows above. Own domain (retail
     * ordering), kept separate from {@code POLICY} rather than bolted onto it, because adding a root-level
     * field there would move a variable under every other row in this class.
     */
    private static final String ORDER = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Lines", l -> l.repeatable(9).field("Sku", FieldKind.STRING)))
            .build().toJson();

    private static String atRootLocus(String argument) {
        return KernelLaws.messageVerdict(ORDER, "/Order", "../Reference",
                "FieldFilled(/Order/Reference)", "en_US", "Order $#" + argument + "$ needs a reference.");
    }

    private static String oneLevelDown(String argument) {
        return KernelLaws.messageVerdict(ORDER, "/Order/Lines", "../Sku",
                "FieldFilled(/Order/Lines/Sku)", "en_US", "Line $#" + argument + "$ needs a sku.");
    }
}
