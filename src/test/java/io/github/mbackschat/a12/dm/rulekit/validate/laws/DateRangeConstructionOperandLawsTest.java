package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * KERNEL-FINDINGS §6 — which operands a {@code DateRange(start, finish)} CONSTRUCTION admits, and the measured
 * basis for the refusals {@code Dsl.dateRange} performs client-side (the "a client-side refusal needs a measured
 * kernel basis" rule; opened as IG140, closed by this file).
 *
 * <p>The gate has two layers and they report differently, which is the whole reason to enumerate rather than
 * sample. The <b>grammar</b> admits only an {@code entitySpec} in either slot, so a point-in-time constant, a
 * shifted date, or a date literal is a parse error ({@code MVK_UNEXPECTED_TOKEN}) — it never reaches a type
 * check. Everything that IS an entity then passes a <b>semantic</b> gate that rejects with
 * {@code MVK_WRONG_DATE_FORMAT_FOR_OP}: a non-date field, a date whose {@code datePrecision} is partial, a
 * date-time, a range, and two operands whose formats carry different date components.
 *
 * <p><b>What that means for the typed surface.</b> Every refusal {@code Dsl.dateRange} makes is inside the
 * measured refusal set, so none is broader than its basis: the operand-must-be-an-entity-reference check mirrors
 * the grammar rows, and the DATE/DATE_FRAGMENT kind check mirrors the non-date field rows. It also confirms the
 * two shapes the surface deliberately ADMITS — a date fragment (on either side, including mixed with a date) and
 * a semantically indexed cell. Three refusals are left to the kernel rather than pre-checked, which is the safe
 * direction (delegation can only under-report): a partial-precision date, a wildcard operand, and mismatched
 * components. The rows below prove the kernel actually catches all three, so the delegation is real rather than
 * assumed.
 */
class DateRangeConstructionOperandLawsTest {

    private static final String MODEL = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", p -> p
                    .dateField("Start", d -> d.format("dd.MM.yyyy"))
                    .dateField("End", d -> d.format("dd.MM.yyyy"))
                    .dateField("MonthOnly", d -> d.format("MM"))
                    .dateField("MonthOnly2", d -> d.format("MM"))
                    .dateField("DayOptional", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateFragmentField("FragFrom", d -> d.fragmentFormat("MM"))
                    .dateFragmentField("FragTo", d -> d.fragmentFormat("MM"))
                    .dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("Stamp2", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .numberField("Qty", n -> n.fractionDigits(0))
                    .stringField("Note", s -> { })
                    .dateRangeField("Window", d -> d.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("MonthWindow", d -> d.format("MM").separator("/"))
                    .group("Lines", l -> l.repeatable(20).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .dateField("LineDate", d -> d.format("dd.MM.yyyy"))
                            .dateField("LineEnd", d -> d.format("dd.MM.yyyy"))))
            .build().toJson();

    /**
     * The kernel's verdict on one condition — {@code accepted} or the reported code. {@code errorRel} must name a
     * field the condition references, or the unrelated {@code MVK_ERROR_FIELD_NOT_REFERENCED} masks the row.
     */
    private static String verdict(String condition, String errorRel) {
        var result = new RuleValidator().check(MODEL,
                new DraftRule("/Policy", "Probe", errorRel, condition, "PROBE", "ERROR"));
        return result.ok() ? "accepted"
                : result.diagnostics().stream().findFirst().flatMap(d -> d.code()).orElse("rejected (no code)");
    }

    private static String verdict(String condition) {
        return verdict(condition, "../End");
    }

    @Test
    void theGrammarAdmitsOnlyAnEntityReferenceInEitherConstructionSlot() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("Today", verdict("DateRange(Today, /Policy/End) == [/Policy/Window]"));
        verdicts.put("BaseYear", verdict("DateRange(BaseYear, /Policy/End) == [/Policy/Window]"));
        verdicts.put("AddMonths", verdict("DateRange(AddMonths(/Policy/Start, 1), /Policy/End) == [/Policy/Window]"));
        verdicts.put("date literal", verdict("DateRange(\"01.01.2020\", /Policy/End) == [/Policy/Window]"));
        // The finish slot is gated by the same grammar rule, not only the start slot.
        verdicts.put("Today as finish",
                verdict("DateRange(/Policy/Start, Today) == [/Policy/Window]", "../Start"));

        assertEquals(Map.of(
                "Today", "MVK_UNEXPECTED_TOKEN",
                "BaseYear", "MVK_UNEXPECTED_TOKEN",
                "AddMonths", "MVK_UNEXPECTED_TOKEN",
                "date literal", "MVK_UNEXPECTED_TOKEN",
                "Today as finish", "MVK_UNEXPECTED_TOKEN"), verdicts);
    }

    @Test
    void anEntityOperandThenPassesASemanticDateGate() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        // Accepted: the shapes the typed surface admits.
        verdicts.put("two dates, one format",
                verdict("DateRange(/Policy/Start, /Policy/End) == [/Policy/Window]"));
        verdicts.put("two MM dates",
                verdict("DateRange(/Policy/MonthOnly, /Policy/MonthOnly2) == [/Policy/MonthWindow]", "../MonthOnly2"));
        verdicts.put("two fragments",
                verdict("DateRange(/Policy/FragFrom, /Policy/FragTo) == [/Policy/MonthWindow]", "../FragTo"));
        verdicts.put("fragment and date",
                verdict("DateRange(/Policy/FragFrom, /Policy/MonthOnly) == [/Policy/MonthWindow]", "../MonthOnly"));
        verdicts.put("semantic index",
                verdict("DateRange(/Policy/Lines/LineDate For \"L1\", /Policy/End) == [/Policy/Window]"));
        verdicts.put("both indexed",
                verdict("DateRange(/Policy/Lines/LineDate For \"L1\", /Policy/Lines/LineEnd For \"L2\")"
                        + " == [/Policy/Window]", "../Window"));
        // Refused: the kind rows the typed surface pre-checks…
        verdicts.put("number field", verdict("DateRange(/Policy/Qty, /Policy/End) == [/Policy/Window]"));
        verdicts.put("string field", verdict("DateRange(/Policy/Note, /Policy/End) == [/Policy/Window]"));
        verdicts.put("date-time field",
                verdict("DateRange(/Policy/Stamp, /Policy/Stamp2) == [/Policy/Window]", "../Window"));
        verdicts.put("range field", verdict("DateRange(/Policy/Window, /Policy/End) == [/Policy/Window]"));
        // …and the three it delegates.
        verdicts.put("partial precision", verdict("DateRange(/Policy/DayOptional, /Policy/End) == [/Policy/Window]"));
        verdicts.put("differing components",
                verdict("DateRange(/Policy/Start, /Policy/MonthOnly) == [/Policy/Window]", "../Window"));
        verdicts.put("wildcard", verdict("DateRange(/Policy/Lines*/LineDate, /Policy/End) == [/Policy/Window]"));
        // The group axis has its OWN code, and which one you get depends on the star: an UNSTARRED group asks
        // for a star (MVK_NO_WILDCARD, the generic gate), while a STARRED group and the rule-context group are
        // refused by the construction's own checker as not-a-field (MVK_NO_GROUPS_ALLOWED). Neither is
        // reachable through the typed surface, whose operands are DateOperand and a group is not one.
        verdicts.put("unstarred group", verdict("DateRange(/Policy/Lines, /Policy/End) == [/Policy/Window]"));
        verdicts.put("starred group", verdict("DateRange(/Policy/Lines*, /Policy/End) == [/Policy/Window]"));
        verdicts.put("rule-context group", verdict("DateRange(RuleGroup, /Policy/End) == [/Policy/Window]"));
        // An unstarred repeating CELL also reports MVK_NO_WILDCARD here — but that is the rule's iteration
        // scope talking, not the construction, and the row above it in this file proves it by moving only the
        // error field. Kept so the code is recorded; read it with that method, never alone.
        verdicts.put("unstarred repeating cell (error field outside the group)",
                verdict("DateRange(/Policy/Lines/LineDate, /Policy/End) == [/Policy/Window]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two dates, one format", "accepted");
        expected.put("two MM dates", "accepted");
        expected.put("two fragments", "accepted");
        expected.put("fragment and date", "accepted");
        expected.put("semantic index", "accepted");
        expected.put("both indexed", "accepted");
        expected.put("number field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("string field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("date-time field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("range field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("partial precision", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("differing components", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("wildcard", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("unstarred group", "MVK_NO_WILDCARD");
        expected.put("starred group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("rule-context group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("unstarred repeating cell (error field outside the group)", "MVK_NO_WILDCARD");
        assertEquals(expected, verdicts);
    }

    /**
     * A FOURTH delegated constraint, and the only context-dependent one: the `$` outer-iteration marker. The
     * typed surface admits it — {@code DateRef.outer()} yields an ordinary {@code FieldValue} — and that is
     * correct, because the shape is legal where a filter supplies the outer iteration. At top level there is
     * none to correlate with, and the kernel says so. Two rows, because either alone reads as a flat verdict.
     */
    @Test
    void theOuterIterationMarkerIsLegalOnlyWhereAFilterSuppliesTheIteration() {
        assertEquals("MVK_INVALID_OUTER_ITERATION",
                verdict("DateRange($/Policy/Start, /Policy/End) == [/Policy/Window]", "../Start"));
        assertEquals("accepted",
                verdict("AllFieldsFilled(/Policy/Lines*/Sku Having"
                        + " ([/Policy/Window] == DateRange($/Policy/Start, LineDate)))", "../Lines/Sku"));
    }

    /**
     * The client mirror: what the typed surface refuses is a subset of what the rows above measured, and the
     * shapes it admits are the accepted ones. A refusal here with no refusal above would be the defect the
     * measured-basis rule exists to catch.
     */
    /**
     * The discriminator a cold review supplied, and it falsifies the reading the first version of this file
     * published. `MVK_NO_WILDCARD` on an unstarred repeating operand is a property of the RULE'S ITERATION
     * SCOPE, not of the construction: hold the condition fixed and move only the error field into the
     * repeating group, and the same operand is accepted.
     */
    @Test
    void theUnstarredRepeatingRefusalIsTheRulesIterationScopeNotTheConstruction() {
        String condition = "DateRange(/Policy/Lines/LineDate, /Policy/End) == [/Policy/Window]";
        assertEquals("MVK_NO_WILDCARD", verdict(condition, "../End"),
                "error field OUTSIDE the repeating group: the rule cannot iterate it");
        assertEquals("accepted", verdict(condition, "../Lines/LineDate"),
                "the SAME condition with the error field INSIDE the repeating group — one variable moved");
    }

    @Test
    void theTypedSurfaceRefusesNothingTheKernelAccepts() {
        BoundModel model = BoundModel.ofJson(MODEL);

        // Grammar-refused above ⇒ refused here.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(Dsl.today(), model.date("/Policy/End")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(model.date("/Policy/Start"), Dsl.today()));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(Dsl.addMonths(model.date("/Policy/Start"), 1), model.date("/Policy/End")));
        // BaseYear is a DateOperand too (Dsl.baseYear() returns a DateExpr), so its grammar row needs a mirror
        // like the other three — it had none until a cold review counted them.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(Dsl.baseYear(), model.date("/Policy/End")));
        // Kind-refused above ⇒ refused here. A DateRef over a non-date FieldRef is the only way to reach the
        // check, since BoundModel.date already refuses the path.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.dateRange(new DateRef(new FieldRef("/Policy/Qty", ValueKind.NUMBER)),
                        model.date("/Policy/End")));

        // Accepted above ⇒ built here, in the same renderings the rows measured.
        DslRenderer renderer = new DslRenderer();
        assertEquals("DateRange(/Policy/Start, /Policy/End)",
                renderer.render(Dsl.dateRange(model.date("/Policy/Start"), model.date("/Policy/End"))));
        assertEquals("DateRange(/Policy/FragFrom, /Policy/FragTo)",
                renderer.render(Dsl.dateRange(model.dateFragment("/Policy/FragFrom"),
                        model.dateFragment("/Policy/FragTo"))));
        assertEquals("DateRange(/Policy/FragFrom, /Policy/MonthOnly)",
                renderer.render(Dsl.dateRange(model.dateFragment("/Policy/FragFrom"),
                        model.date("/Policy/MonthOnly"))));
        assertEquals("DateRange(/Policy/Lines/LineDate For \"L1\", /Policy/End)",
                renderer.render(Dsl.dateRange(model.date("/Policy/Lines/LineDate").forIndex("L1"),
                        model.date("/Policy/End"))));

        // Delegated: the surface builds these, and the rows above show the kernel rejecting them. Asserted on
        // the FULL rendering like every other row — a startsWith("DateRange(") would have passed for a mangled
        // operand, which is weaker than the rest of this file and was flagged as such.
        assertEquals("DateRange(/Policy/DayOptional, /Policy/End)",
                renderer.render(Dsl.dateRange(model.date("/Policy/DayOptional"), model.date("/Policy/End"))),
                "a partial-precision date is built here and left to the kernel");
        assertEquals("DateRange(/Policy/Start, /Policy/MonthOnly)",
                renderer.render(Dsl.dateRange(model.date("/Policy/Start"), model.date("/Policy/MonthOnly"))),
                "component compatibility is left to the kernel");
        assertEquals("DateRange([$]/Policy/Start, /Policy/End)".replace("[$]", "$"),
                renderer.render(Dsl.dateRange(model.date("/Policy/Start").outer(), model.date("/Policy/End"))),
                "the outer-iteration marker is built here; its legality is contextual and kernel-arbitrated");
    }
}
