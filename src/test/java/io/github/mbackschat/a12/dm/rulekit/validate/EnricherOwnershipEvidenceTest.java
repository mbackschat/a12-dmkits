package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

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
 * <p><b>The root fix is a mechanism, not another special case.</b> The kernel <em>delimits what it substitutes</em>:
 * 146 of the 148 templates carrying a {@code %s} wrap it in quotes, and the two that do not substitute a group path
 * or a digit count — never an operator name. So only a <b>quoted</b> token is evidence of the construct at fault;
 * unquoted words are fixed prose that says nothing about this invocation. That kills both cases above while keeping
 * the true positive ({@code At 'Abs'It is not allowed to use constants.}), and it degrades to "unestablished"
 * rather than to a wrong owner.
 *
 * <p>It also makes a <b>stronger</b> claim available, which closes review's separate finding: when the message
 * quotes an operator that is <em>not</em> among the documenters, the evidence in hand does not merely fail to
 * establish the documenter — it <b>positively excludes</b> it. Then even the hedged code-level corrective must be
 * withheld, because the tool would otherwise name an operator its own input contradicts.
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

    /** The true positive must survive: the kernel QUOTES the operand it substituted, and that is real evidence. */
    @Test
    void aQuotedIdentifierIsEvidence() {
        ValidationResult result = check("FieldFilled(UnitPrice) And Abs(-5) > [UnitPrice]");

        Diagnostic d = coded(result, "MVK_NO_CONST");
        assertTrue(d.summary().contains("'Abs'"), () -> "precondition: the kernel names Abs in quotes: " + d);
        assertEquals(
                io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog.load().byId("Abs").orElseThrow().meaning(),
                d.explain().orElse(""),
                () -> "a quoted operator IS established, so its meaning may be attached: " + d);
    }

    /**
     * The positive-exclusion case (review finding). {@code MVK_NOT_SORTABLE} is documented for {@code MaxValue} and
     * <em>not</em> for {@code MinValue}, so a {@code MinValue} rejection used to be corrected with MaxValue's advice
     * — while the kernel message in hand said {@code At 'MinValue'}. The quoted evidence excludes the documenter, so
     * no operator may be named at all.
     */
    @Test
    void aQuotedOperatorThatIsNotTheDocumenterExcludesItRatherThanBeingIgnored() {
        ValidationResult result = check("FieldFilled(UnitPrice) And MinValue(CustomerName, ProductCode) > 0");

        Diagnostic d = result.diagnostics().stream()
                .filter(x -> x.summary().contains("'MinValue'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a MinValue rejection, got: " + result.diagnostics()));
        assertFalse(d.fix().orElse("").contains("MaxValue"),
                () -> "the message names MinValue, so MaxValue is EXCLUDED, not merely unestablished — and its "
                        + "curated corrective is advice about a different code: " + d);
        // Resolving against the WHOLE catalog rather than only the documenters buys something the finding did not
        // ask for: the quoted operator IS the culprit, so ITS meaning is correct to attach even though it documents
        // no example for this code. Being a documenter gates the `fix`, not the identification.
        assertEquals(
                io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog.load().byId("MinValue").orElseThrow()
                        .meaning(),
                d.explain().orElse(""),
                () -> "the operator the kernel QUOTED is the one explained: " + d);
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
