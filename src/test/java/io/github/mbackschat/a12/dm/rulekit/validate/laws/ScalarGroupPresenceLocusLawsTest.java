package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §1 — <b>scalar {@code GroupFilled} admission turns on the OPERAND's repeatability and the
 * ERROR FIELD's locus. The rule's own group decides neither</b> (measured 2026-09-04). A repeatable operand is
 * admitted only when the error field sits inside a repetition; a non-repeatable operand — a root group
 * included — carries no repetition to bind and is admitted either way.
 *
 * <p><b>Why this exists.</b> {@code skill/SKILL.md} told agents that plain {@code GroupFilled(Group)} is
 * "valid only from <em>inside</em> the iterating group, never as a model-level reference", which is false for
 * every non-repeatable operand.
 *
 * <p><b>The first version of this file got the AXIS wrong, and that is the lesson worth keeping.</b> It paired
 * "outside" as {@code (rule group /Order, error field ../CustomerName, conjunct FieldFilled(CustomerName))}
 * against "inside" as {@code (rule group /Order/Items, error field ../Sku, conjunct FieldFilled(Sku))} — three
 * variables moving together — and attributed the split to the rule's group. {@link #theRulesGroupIsNotTheAxis}
 * is the row that refutes it: hold the condition and the ABSOLUTE error field fixed, move only
 * {@code --group}, and nothing changes. [KF185]'s own heading had said "the error field's LOCUS selects the
 * class" all along, and its lock
 * {@code GroupListDiagnosticIdentityLawsTest.theErrorFieldLocusSelectsTheClassForAnUnstarredRepeatableOperand}
 * already held the rule group fixed across every row. A cold review caught it; the corrected table below moves
 * one variable per test.
 *
 * <p><b>What this file adds over that lock</b> is the second axis: it holds the error field fixed and moves the
 * OPERAND across the repeatability boundary, which is the half the skill sentence got wrong. The error-field
 * pair reappears here as the control that separates the two axes, not as a second statement of it — that fact
 * is owned by the class named above.
 *
 * <p><b>The absolute conjunct is deliberate.</b> A relative one (`FieldFilled(Items/Sku)`) resolves from the
 * rule's group, so moving {@code --group} would silently move what the conjunct names too and the rule-group
 * control would measure resolution instead of admission. Absolute references resolve identically from every
 * host, which is what leaves exactly one variable in play.
 *
 * <p><b>Why the conjunct is there at all, stated narrowly.</b> A {@code GroupFilled} operand that ENCLOSES the
 * error field satisfies the error-field reference on its own (the catalog's own {@code GroupFilled} gotcha), so
 * only the non-enclosing rows actually need it — {@code GroupFilled(/Order)} and
 * {@code GroupFilled(/Order/Items)} with the error field inside {@code Items} are admitted bare. It is held
 * constant in every row regardless, because varying it would be a second variable.
 *
 * <p><b>The negative sibling is included rather than excluded.</b> An earlier draft left
 * {@code GroupNotFilled} out on the grounds that {@code MVK_NEG_CONDITION_IN_ITERATION} would move a second
 * variable; measured, that gate fires in NONE of these rows, because the positive conjunct already guards the
 * row. So the negative arm doubles the table's reach at no cost — see {@link #theNegativeSiblingSplitsTheSame}.
 */
class ScalarGroupPresenceLocusLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NO_WILDCARDS_ALLOWED = "MVK_NO_WILDCARDS_ALLOWED";
    private static final String INVALID_ENTITY = "MVK_INVALID_ENTITY";

    /** Every row hosts the rule here; only {@link #theRulesGroupIsNotTheAxis} moves it. */
    private static final String HOST = "/Order";

    /** The error field OUTSIDE every repetition, and its absolute conjunct. */
    private static final String OUT = "../CustomerName";
    private static final String OUT_REF = "FieldFilled(/Order/CustomerName)";

    /** The error field INSIDE the repeatable {@code Items}, and its absolute conjunct. */
    private static final String IN = "../Items/Sku";
    private static final String IN_REF = "FieldFilled(/Order/Items/Sku)";

    /**
     * Axis 1 — the operand crosses the repeatability boundary while the rule group, the error field and the
     * conjunct all stay fixed. This is the axis the skill sentence got wrong.
     */
    @Test
    void operandRepeatabilityDecidesAdmission() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-repeatable operand", verdict(HOST, OUT, OUT_REF + " And GroupFilled(/Order/ShippingAddress)"));
        actual.put("the root group", verdict(HOST, OUT, OUT_REF + " And GroupFilled(/Order)"));
        actual.put("repeatable operand", verdict(HOST, OUT, OUT_REF + " And GroupFilled(/Order/Items)"));
        actual.put("repeatable operand, STARRED", verdict(HOST, OUT, OUT_REF + " And GroupFilled(/Order/Items*)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-repeatable operand", ADMITTED);
        expected.put("the root group", ADMITTED);
        // The only operand with rows to bind, and the only one refused from this error-field locus.
        expected.put("repeatable operand", NO_WILDCARD);
        expected.put("repeatable operand, STARRED", NO_WILDCARDS_ALLOWED);
        assertEquals(expected, actual, "a non-repeatable operand carries no repetition to bind, so it is admitted");
    }

    /**
     * Axis 2 — the error field crosses INTO a repetition while the operand stays the repeatable group. The
     * conjunct follows the error field because the reference gate demands it; both spellings are absolute, so
     * nothing about resolution moves with it.
     */
    @Test
    void theErrorFieldsLocusDecidesItForARepeatableOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("error field outside every repetition",
                verdict(HOST, OUT, OUT_REF + " And GroupFilled(/Order/Items)"));
        actual.put("error field inside the operand's own repetition",
                verdict(HOST, IN, IN_REF + " And GroupFilled(/Order/Items)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("error field outside every repetition", NO_WILDCARD);
        expected.put("error field inside the operand's own repetition", ADMITTED);
        assertEquals(expected, actual, "primarily locked by GroupListDiagnosticIdentityLawsTest; here it is the "
                + "control that separates the error-field axis from the operand axis above");
    }

    /**
     * The row that refutes this file's own first framing: the rule's group is inert. The condition and the
     * ABSOLUTE error field are byte-identical across the pair, so {@code --group} is the only thing that moves.
     */
    @Test
    void theRulesGroupIsNotTheAxis() {
        String condition = IN_REF + " And GroupFilled(/Order/Items)";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule hosted at the root", verdict("/Order", IN, condition));
        actual.put("rule hosted at the repeatable group itself", verdict("/Order/Items", "../Sku", condition));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule hosted at the root", ADMITTED);
        expected.put("rule hosted at the repeatable group itself", ADMITTED);
        assertEquals(expected, actual, "same condition, same absolute error field, different host — no change");
    }

    /** The negative sibling splits identically, and the iteration gate fires in none of these rows. */
    @Test
    void theNegativeSiblingSplitsTheSame() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-repeatable operand", verdict(HOST, OUT, OUT_REF + " And GroupNotFilled(/Order/ShippingAddress)"));
        actual.put("repeatable operand, error field outside",
                verdict(HOST, OUT, OUT_REF + " And GroupNotFilled(/Order/Items)"));
        actual.put("repeatable operand, error field inside",
                verdict(HOST, IN, IN_REF + " And GroupNotFilled(/Order/Items)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-repeatable operand", ADMITTED);
        expected.put("repeatable operand, error field outside", NO_WILDCARD);
        expected.put("repeatable operand, error field inside", ADMITTED);
        assertEquals(expected, actual, "no MVK_NEG_CONDITION_IN_ITERATION anywhere — the conjunct guards the row");
    }

    /**
     * The RELATIVE spelling is where the rule's group does matter — for RESOLUTION, never for admission. This
     * is the distinction whose absence made the wrong axis so easy to write: both effects are reached by
     * changing {@code --group}, and only one of them is about whether the operand binds.
     */
    @Test
    void aRelativeSpellingResolvesFromTheRulesGroup() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("child name, from its parent", verdict(HOST, OUT, OUT_REF + " And GroupFilled(ShippingAddress)"));
        actual.put("the same name, from a host that does not contain it",
                verdict("/Order/Items", "../Sku", IN_REF + " And GroupFilled(ShippingAddress)"));
        actual.put("the ROOT's own bare name, from the root", verdict(HOST, OUT, OUT_REF + " And GroupFilled(Order)"));
        actual.put("parent navigation, from inside", verdict("/Order/Items", "../Sku", IN_REF + " And GroupFilled(..)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("child name, from its parent", ADMITTED);
        expected.put("the same name, from a host that does not contain it", INVALID_ENTITY);
        // Not an admission verdict: a bare root name is not a child of itself. `..` is how an author reaches out.
        expected.put("the ROOT's own bare name, from the root", INVALID_ENTITY);
        expected.put("parent navigation, from inside", ADMITTED);
        assertEquals(expected, actual, "relative resolution is from the rule's group — a separate axis from admission");
    }

    /**
     * The confound the single-root rows cannot see: in the order fixture every root reference IS the error
     * field's enclosing group, so "a root group is admitted" and "the enclosing group is admitted" fit every
     * row equally. A second, disjoint root separates them.
     */
    @Test
    void aNonEnclosingNonRepeatableRootIsAdmittedFromInsideARepetition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-enclosing non-repeatable root, error field inside the other root",
                verdictOn(TWO_ROOT, "/Persons", "../Name",
                        "FieldFilled(/Persons/Name) And GroupFilled(/Header)"));
        actual.put("the ENCLOSING repeatable root — control",
                verdictOn(TWO_ROOT, "/Persons", "../Name",
                        "FieldFilled(/Persons/Name) And GroupFilled(/Persons)"));
        actual.put("that repeatable root seen from outside every repetition",
                verdictOn(TWO_ROOT, "/Header", "../Title",
                        "FieldFilled(/Header/Title) And GroupFilled(/Persons)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-enclosing non-repeatable root, error field inside the other root", ADMITTED);
        expected.put("the ENCLOSING repeatable root — control", ADMITTED);
        // The repeatability rule holds for a ROOT operand too, which the single-root fixture cannot show.
        expected.put("that repeatable root seen from outside every repetition", NO_WILDCARD);
        assertEquals(expected, actual, "non-repeatable is the reason a root is admitted — not that it is a root");
    }

    /** A repeatable root beside a plain one — the only shape that separates "root" from "enclosing". */
    private static final String TWO_ROOT = DocumentModelDraft.named("multiform").locale("en_US")
            .rootGroup("Header", h -> h.field("Title", FieldKind.STRING))
            .rootGroup("Persons", p -> p.repeatable(50).field("Name", FieldKind.STRING))
            .build().toJson();

    private static String verdict(String group, String errorField, String condition) {
        return verdictOn(Fixtures.orderJson(), group, errorField, condition);
    }

    /** The kernel's verdict — {@link KernelLaws#ADMITTED} or the comma-joined classes it reported. */
    private static String verdictOn(String json, String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(json, group, errorField, condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }
}
