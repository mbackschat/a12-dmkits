package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;

/**
 * <b>What counts as evidence that a given operator raised a kernel code</b> — the fourth and (by construction) last
 * repair of one defect class.
 *
 * <p>The class: an operator-specific {@code fix}/{@code explain} attached to a diagnostic the operator did not
 * cause. Three earlier repairs each narrowed the previous case and left the next — {@code findFirst} (an arbitrary
 * pick among many documenters) → {@code firstMeaning} (an arbitrary <em>explain</em> among many) →
 * {@code size()==1} (an arbitrary owner among <em>unknowns</em>, since the catalog is a partial map). The fourth
 * instance, found by review: the surviving repair searched the <b>whole kernel message</b> for any documenter's
 * identifier, so an operator got established from the kernel's own <b>boilerplate prose</b>.
 *
 * <p>The two live cases, both measured against the shipped {@code kernel-core-parser} bundle:
 *
 * <ul>
 *   <li>{@code MVK_INVALID_COMPARE_TO_YESNO} is documented only by the operator {@code True}, and its message ends
 *       <em>"…or with the constants <b>True</b> or False."</em> — so <em>every</em> occurrence of that code was
 *       explained as "the boolean constant 'true'", including one raised by comparing a boolean to a string. The
 *       message has no {@code %s} at all, so nothing about the culprit was ever measured;</li>
 *   <li>{@code MVK_NO_INDEX_FIELD}'s message begins <em>"<b>For</b> '%s' there is no repeatable group…"</em>, and
 *       {@code For} is {@code SemanticIndex}'s English keyword.</li>
 * </ul>
 *
 * <p><b>The fix has two halves, and the first alone was worse than the defect.</b> Only a <em>delimited</em> token is
 * evidence about this invocation — unquoted words are fixed prose, identical for every occurrence of the code. That
 * kills both cases above while keeping the true positive ({@code At 'Abs'It is not allowed to use constants.}). But
 * the first attempt also <em>widened</em> the electable set to the whole catalog, and the kernel delimits
 * <b>values</b> too, so a quoted enum S-value spelled like an operator name elected that operator. Election stays
 * restricted to the code's documenters; the delimiters only narrow it.
 *
 * <p>A <b>stronger</b> claim was briefly attempted and withdrawn: that naming a non-documenter positively
 * <em>excludes</em> the documenters, so even the hedge should be withheld. It cannot be established from a message
 * alone, precisely because a delimited span may be a value.
 */
class EnricherOwnershipEvidenceTest {

    /**
     * The {@code True}-in-boilerplate case. The condition contains no {@code True} token and uses no {@code True}
     * operator; the discriminating fact is that the identical wrong explain also fired for the catalog's own
     * {@code == "yes"} example, so it cannot have come from reading the condition.
     */
    @Test
    void anIdentifierInTheKernelsFixedProseIsNotEvidence() {
        ValidationResult result = check("[ExpressShipping] == [CustomerName]");

        Diagnostic d = coded(result, "MVK_INVALID_COMPARE_TO_YESNO");
        assertTrue(d.explain().isEmpty(),
                () -> "the word True here is the kernel's own boilerplate, not the construct at fault, so no "
                        + "operator MEANING may be asserted: " + d);
        // The corrective may still ride — but only in its HEDGED, code-level form. This message quotes nothing, so
        // ownership is unestablished rather than excluded, and "the corrective applies if that is what you used" is
        // an honest thing to say. What was wrong before was the unhedged `explain`, which claims a culprit.
        assertTrue(d.fix().orElse("").startsWith("this code is documented for the construct(s) below"),
                () -> "an unestablished owner gets the hedge, never an asserted one: " + d);
    }

    /**
     * The true positive must survive: the kernel quotes the operand it substituted, and that is real evidence.
     *
     * <p>Paired below with the case that makes it revert-sensitive. On its own this test passes against the old
     * whole-message regex too — {@code \bAbs\b} matches inside {@code At 'Abs'It…} — so it locks the outcome without
     * locking the mechanism, which is how it was found to be vacuous.
     */
    @Test
    void aQuotedIdentifierIsEvidence() {
        ValidationResult result = check("FieldFilled(UnitPrice) And Abs(-5) > [UnitPrice]");

        Diagnostic d = coded(result, "MVK_NO_CONST");
        assertTrue(d.summary().contains("'Abs'"), () -> "precondition: the kernel names Abs in quotes: " + d);
        assertEquals(
                OperatorCatalog.load().byId("Abs").orElseThrow().meaning(),
                d.explain().orElse(""),
                () -> "a quoted operator IS established, so its meaning may be attached: " + d);
    }

    /**
     * A rejection whose message names an operator the catalog does <b>not</b> document for that code stays
     * <em>unestablished</em> — it gets the hedged, code-level corrective and no meaning.
     *
     * <p>An earlier version asserted something stronger: that naming a non-documenter positively <em>excluded</em> the
     * documenters, so even the hedge was withheld. That is unsound and was withdrawn. It required electing from the
     * whole catalog, which is exactly what let a quoted enum S-value elect an operator (see
     * {@link #aQuotedEnumValueSpelledLikeAnOperatorDoesNotElectIt}) — the message alone cannot distinguish an
     * operator from a value, so "the message named someone else" is not a fact it can establish. The hedge is the
     * honest disposition: it says which constructs the code is documented for and that the corrective applies only if
     * that is what you used.
     *
     * <p><b>The specimen must be a LIVE non-documenter pair, and it moved on 2026-09-07.</b> It used to be
     * {@code MinValue(...)} raising {@code MVK_NOT_SORTABLE}, a code only {@code MaxValue} documented. Closing G77
     * gave {@code MinValue} its own corrective for that code, so the quoted operator became a documenter and the
     * enricher correctly established it — which retires the pair rather than breaking the rule. {@code MinValue}
     * quoted on {@code MVK_DUPLICATE_PARAM2} is the replacement: that code is documented by {@code AllGroupsFilled}
     * alone, before and after that change, so the hedge is still the honest disposition here. If a later change
     * gives {@code MinValue} a corrective for THIS code too, re-point the specimen again — do not weaken the
     * assertions, because the invariant is about the enricher and not about any one pair.
     */
    @Test
    void aQuotedNonDocumenterLeavesOwnershipUnestablished() {
        ValidationResult result = check("FieldFilled(CustomerName) And MinValue(Items*, Items*/Count) > 0");

        Diagnostic d = result.diagnostics().stream()
                .filter(x -> x.summary().contains("'MinValue'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a MinValue rejection, got: " + result.diagnostics()));
        assertTrue(d.explain().isEmpty(),
                () -> "no owner was established, so no operator meaning may be asserted: " + d);
        assertTrue(d.fix().orElse("").contains("if that is what you used"),
                () -> "the corrective is offered HEDGED, naming the constructs the code is documented for: " + d);
    }

    /**
     * <b>A quoted span is not necessarily an operator.</b> The fifth instance of this defect class, and the one that
     * made the fourth repair strictly worse than the defect it fixed.
     *
     * <p>{@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON}'s template is
     * {@code '%s' is not valid in the used enumeration.} and the kernel substitutes the <em>author's enum
     * S-value</em>. When ownership resolution was widened from the code's documenters to the whole catalog, an enum
     * comparison against an ordinary value like {@code "Max"} therefore elected the {@code Max} <em>operator</em>:
     * the diagnostic gained {@code Max}'s meaning as the explanation of an enum-value error, and lost the correct
     * {@code EnumCategory} corrective (withheld because {@code Max} documents no example for this code). Worse on
     * both fields than the ambiguous-but-honest behaviour it replaced.
     *
     * <p>The control is what makes this a discriminating pair rather than a snapshot: the two cases differ <em>only</em>
     * in how the invalid constant is spelled. If the message could still elect from the whole catalog, the corrective
     * would flip between them — so the assertion that they agree is the assertion that the widening is gone.
     */
    @Test
    void aQuotedEnumValueSpelledLikeAnOperatorDoesNotElectIt() {
        Diagnostic collides = enrichKernel("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON",
                "Rule with path '/Order/ProbeRule': 'Max' is not valid in the used enumeration.");
        Diagnostic control = enrichKernel("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON",
                "Rule with path '/Order/ProbeRule': 'NOPE' is not valid in the used enumeration.");

        assertTrue(collides.explain().isEmpty(),
                () -> "'Max' here is an enum VALUE, not the Max operator — nothing may be explained: " + collides);
        assertEquals(control.fix(), collides.fix(),
                () -> "the corrective must not depend on how the invalid value happens to be spelled: "
                        + collides + " vs " + control);
        assertTrue(collides.fix().isPresent(),
                () -> "and the code's documenter still owes its hedged corrective: " + collides);
    }

    /** The enricher over a verbatim kernel message — the real input shape, with nothing else varying. */
    private static Diagnostic enrichKernel(String code, String summary) {
        return new DiagnosticEnricher(OperatorCatalog.load())
                .enrich(Diagnostic.precheck(Optional.of(code), summary,
                        new Diagnostic.Where((String) null, Optional.empty()),
                        Optional.empty(), Optional.empty()));
    }

    /**
     * <b>A unique kernel raise site IS evidence, and it is the only sound way to own a message that names no
     * operator.</b> The counterpart to the case above: tightening election to positively-established ownership
     * dropped this {@code explain}, which had been <em>correct</em> — the unsound sole-documenter rule happened to
     * name the right owner. A right answer from wrong reasoning is not a reason to keep the reasoning, but it is a
     * reason to look for a sound basis, and here there was one.
     *
     * <p>What makes this basis different from the sole-<em>documenter</em> shortcut: that reasoned from <em>our
     * catalog's</em> coverage, a partial map, so one documenter was equally consistent with an undocumented sibling
     * having raised the code. This reasons from the <em>kernel's source</em> — an enumerated search over the
     * consult-only checkout (kernel 30.8.1) finding exactly one raise site, {@code CheckSemanticIndexImpl:125},
     * with every other hit being the {@code ParserErrorTypesEnum} declaration or a message/documentation template.
     *
     * <p>Discriminating by construction: the EN template is {@code For '%s' there is no repeatable group with index
     * field.}, so the only delimited span is a <b>path</b>. The message-based source therefore cannot reach this
     * code, and the structured source has no operator either — remove the {@code SOLE_RAISE_SITE} entry and this
     * test fails. (The unquoted {@code For} in that sentence is {@code SemanticIndex}'s English keyword, which is
     * exactly the boilerplate the first case above proves is not evidence: the right owner, for a reason that would
     * be wrong anywhere else.)
     */
    @Test
    void aCodeWithAUniqueKernelRaiseSiteIsOwnedByThatConstruct() {
        ValidationResult result = check(
                "FieldFilled(CustomerName) And [BillingAddress/PostalCode For \"x\"] == \"y\"");

        Diagnostic d = coded(result, "MVK_NO_INDEX_FIELD");
        assertFalse(d.summary().contains("'SemanticIndex'") || d.summary().contains("'For'"),
                () -> "precondition: the kernel delimits a PATH here, never the operator — so the message-based "
                        + "source cannot establish this owner: " + d);
        assertEquals(
                OperatorCatalog.load().byId("SemanticIndex")
                        .orElseThrow().meaning(),
                d.explain().orElse(""),
                () -> "the code has one raise site, so the construct is established and its meaning may ride: " + d);
        assertFalse(d.fix().orElse("").contains("if that is what you used"),
                () -> "an established owner gets its own corrective, not the hedge: " + d);
    }

    private static ValidationResult check(String condition) {
        return new RuleValidator().check(Fixtures.orderJson(),
                new DraftRule("/Order", "ProbeRule", "../CustomerName", condition, "PROBE", "ERROR"));
    }

    private static Diagnostic coded(ValidationResult result, String code) {
        return result.diagnostics().stream()
                .filter(d -> d.code().map(code::equals).orElse(false))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected " + code + ", got: " + result.diagnostics()));
    }
}
