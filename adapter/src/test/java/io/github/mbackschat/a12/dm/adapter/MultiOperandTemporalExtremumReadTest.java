package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The READ half of DG38 — a {@code MaxValue}/{@code MinValue} over <b>several</b> temporal operands.
 *
 * <p><b>The defect this locks was a read-path one, which is the sharper half.</b> {@code DateListAggregate}
 * held a single scope and the converter's {@code temporalExtremumKind} bailed out on any list longer than one
 * entity, so a kernel-legal {@code MaxValue(FiledOn, ClosedOn)} over two DATE fields fell through to the
 * NUMERIC arm and came back Opaque: {@code rule read} reported
 * {@code converter error … MaxValue takes number fields; /Claim/FiledOn is DATE}. A legal model was therefore
 * unreadable, not merely unauthorable — the failure mode the "check the READ path too" rule names.
 *
 * <p>Each row below is a WITHIN-model pair: the same two-operand extremum authored typed and read back, and the
 * same condition put to the real kernel. The kernel row is what makes the round-trip row meaningful — a
 * converter that round-tripped some other text would satisfy the first assertion alone. The operands are
 * deliberately spelled DIFFERENTLY ({@code yyyy-MM-dd} beside {@code dd.MM.yyyy}) because that is the pair the
 * kernel admits by component SET, so the fixture cannot be satisfied by a same-format shortcut
 * ({@code rulekit.validate.laws.TemporalAggregateComponentSetLawsTest} owns that gate).
 */
class MultiOperandTemporalExtremumReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String claimJson() {
        return DocumentModelDraft.named("motor-claim").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", s -> { })
                        .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("ClosedOn", d -> d.format("dd.MM.yyyy"))
                        .dateField("ReopenedOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("PolicyYear", d -> d.format("yyyy"))
                        .dateFragmentField("RenewalYear", f -> f.fragmentFormat("yyyy"))
                        .dateField("YearTarget", d -> d.format("yyyy"))
                        .timeField("ShiftStart", t -> { })
                        .timeField("ShiftEnd", t -> { })
                        .timeField("ShiftAudit", t -> { })
                        .group("Visits", v -> v.repeatable(20)
                                .dateField("VisitOn", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    /**
     * The CROSS-KIND row. {@code MaxValue(<DATE yyyy>, <DATE_FRAGMENT yyyy>)} is kernel-admitted — the gate
     * separates temporal CLASSES, and DATE and DATE_FRAGMENT share one — and the authoring side was widened to
     * match. The read side was not: its classifier kept kind EQUALITY, so this list fell through to the numeric
     * arm and came back {@code Opaque}, the identical symptom the class-level fix was supposed to have removed.
     * Both directions now share one definition ({@code DateListAggregate.temporalClassOf}).
     */
    @Test
    void roundTripsACrossKindDateExtremum() {
        BoundModel m = BoundModel.ofJson(claimJson());
        Condition latest = m.date("/Claim/YearTarget")
                .ne(Dsl.latestOf(m.field("/Claim/PolicyYear"), m.field("/Claim/RenewalYear")));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "CrossKindRule", "/Claim/YearTarget", latest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Claim/CrossKindRule"))));
        ValidationResult admitted = new RuleValidator().check(claimJson(),
                new DraftRule("/Claim", "CrossKindKernel", "../YearTarget",
                        "FieldFilled(YearTarget) And [/Claim/YearTarget] != MaxValue(PolicyYear, RenewalYear)",
                        "C", "ERROR"));
        assertTrue(admitted.ok(), () -> "the kernel must admit the cross-kind extremum this row reads back, got: "
                + admitted.diagnostics());
    }

    @Test
    void roundTripsATwoFieldDateExtremum() {
        BoundModel m = BoundModel.ofJson(claimJson());
        Condition latest = m.date("/Claim/ReopenedOn")
                .ne(Dsl.latestOf(m.field("/Claim/FiledOn"), m.field("/Claim/ClosedOn")));
        Condition earliest = m.date("/Claim/ReopenedOn")
                .ne(Dsl.earliestOf(m.field("/Claim/FiledOn"), m.field("/Claim/ClosedOn")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "LatestRule", "/Claim/ReopenedOn", latest))
                .add(rule(m, "EarliestRule", "/Claim/ReopenedOn", earliest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Claim/LatestRule"))));
        assertEquals(RENDER.render(earliest), RENDER.render(converted(adapter.read("/Claim/EarliestRule"))));
    }

    /** The mixed shape — one direct field beside a starred list — since the converter types each entity apart. */
    @Test
    void roundTripsADirectFieldBesideAStarredList() {
        BoundModel m = BoundModel.ofJson(claimJson());
        Condition latest = m.date("/Claim/ReopenedOn")
                .ne(Dsl.latestOf(m.field("/Claim/FiledOn"), m.flatten("/Claim/Visits*/VisitOn")));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "MixedRule", "/Claim/ReopenedOn", latest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Claim/MixedRule"))));
    }

    /** The TIME arm, so the widening is not DATE-only — the converter picks the kind off the field operands. */
    @Test
    void roundTripsATwoFieldTimeExtremum() {
        BoundModel m = BoundModel.ofJson(claimJson());
        Condition latest = m.time("/Claim/ShiftAudit")
                .ne(Dsl.latestTimeOf(m.field("/Claim/ShiftStart"), m.field("/Claim/ShiftEnd")));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "ShiftRule", "/Claim/ShiftAudit", latest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Claim/ShiftRule"))));
    }

    /**
     * A {@code Having} filter on a starred operand SURVIVES the multi-operand read. This is the third root the
     * same sweep turned up: {@code toFieldOperand} rebuilt a wildcard entity through {@code BoundModel.flatten}
     * and silently DROPPED its filter, so every multi-operand aggregate and value-list read turned a narrowed
     * rule into a broad one. Two call sites had each worked around it locally by calling {@code flattenScope}
     * themselves, which is the tell for a root-level bug; the fix is in {@code toFieldOperand}, and this row is
     * what stops it regressing. A dropped filter round-trips to a DIFFERENT, still-legal condition, so only an
     * exact-text assertion can catch it.
     */
    @Test
    void aHavingFilterOnAMultiOperandExtremumSurvivesTheRead() {
        BoundModel m = BoundModel.ofJson(claimJson());
        Condition latest = m.date("/Claim/ReopenedOn").ne(Dsl.latestOf(
                m.field("/Claim/FiledOn"),
                m.flatten("/Claim/Visits*/VisitOn").having(m.date("/Claim/Visits/VisitOn").after(
                        m.date("/Claim/FiledOn")))));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FilteredRule", "/Claim/ReopenedOn", latest)));
        String read = RENDER.render(converted(adapter.read("/Claim/FilteredRule")));
        assertTrue(read.contains("Having"), () -> "the Having filter must survive the read, got: " + read);
        assertEquals(RENDER.render(latest), read);
    }

    /** The kernel row that makes the round-trips above about a LEGAL shape rather than about our own text. */
    @Test
    void aTwoFieldDateExtremumIsKernelValid() {
        ValidationResult r = new RuleValidator().check(claimJson(),
                new DraftRule("/Claim", "LatestRule", "../ReopenedOn",
                        "FieldFilled(ReopenedOn) And [/Claim/ReopenedOn] != MaxValue(FiledOn, ClosedOn)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Claim")).name(name).errorField(m.field(errorField)).when(cond)
                .code(name).severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
