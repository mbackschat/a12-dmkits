package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;

/**
 * Own-domain transfer of the computation <b>shapes</b> the real corpora use — kernel-mm and the A12
 * Tools workspaces (license-safe: copy the mechanism, re-express the domain). The computation peer of
 * {@code BaCorpusTest}: committed + always-run (not gated like the coverage harnesses), each shape
 * authored → added → ingested back to an identical {@link Computation} (render → extract → convert is
 * the identity). The corpora's vocabulary, in an invoice domain:
 *
 * <ul>
 *   <li><b>numeric aggregate</b> ({@code Subtotal = Sum(Lines* / Amount)}) — the dominant Corpus-1 calc;</li>
 *   <li><b>string constant default</b> ({@code Note = "Generated"}) — the dominant kernel-mm metamodel shape;</li>
 *   <li><b>boolean constant</b> ({@code Flagged = True}) — the kernel-mm {@code IBoolConstantOperation} shape.</li>
 * </ul>
 *
 * (Numeric field-value, arithmetic, and tiered/preconditioned shapes are covered by {@code ComputationIngestTest}.)
 */
class ComputationShapesTest {

    private static BoundModel invoice() {
        return BoundModel.ofJson(DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", inv -> inv
                        .field("CustomerName", FieldKind.STRING)
                        .numberField("Subtotal", n -> n.fractionDigits(2))
                        .field("Note", FieldKind.STRING)
                        .field("Flagged", FieldKind.BOOLEAN)
                        .dateTimeField("ClockIn", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .timeField("ClockTime", t -> t.format("HH:mm:ss"))
                        .enumField("TopStatus", e -> e.value("OPEN").value("CLOSED"))
                        .group("Lines", g -> g.repeatable(50)
                                .numberField("Amount", a -> a.fractionDigits(2))
                                .enumField("Status", e -> e.value("OPEN").value("CLOSED"))))
                .build().toJson());
    }

    @Test
    void timeFromDateTimeExtraction() {
        BoundModel m = invoice();
        Computation comp = m.computation(m.field("/Invoice/ClockTime")).name("ClockTimeComp")
                .calc(Dsl.timeFromDateTime(m.dateTime("/Invoice/ClockIn")).expression())
                .message(en("ClockTime is the time-of-day part of ClockIn.")).build();
        roundTrips(m, comp, "/Invoice/ClockTimeComp");
    }

    @Test
    void enumFirstFilledAggregate() {
        BoundModel m = invoice();
        Computation comp = m.computation(m.field("/Invoice/TopStatus")).name("TopStatusComp")
                .calc(Dsl.firstFilledEnum(m.flatten("/Invoice/Lines*/Status")).expression())
                .message(en("TopStatus is the first specified line status.")).build();
        roundTrips(m, comp, "/Invoice/TopStatusComp");
    }

    private static void roundTrips(BoundModel m, Computation comp, String fullName) {
        IngestedComputation result = KernelAdapter.of(m.add(comp)).ingestComputation(fullName);
        Computation got = assertInstanceOf(IngestedComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void numericAggregate() {
        BoundModel m = invoice();
        Computation comp = m.computation(m.num("/Invoice/Subtotal")).name("SubtotalComp")
                .calc(Dsl.sum(m.flatten("/Invoice/Lines*/Amount")).expression())
                .message(en("Subtotal is the sum of the line amounts.")).build();
        roundTrips(m, comp, "/Invoice/SubtotalComp");
    }

    @Test
    void stringConstantDefault() {
        BoundModel m = invoice();
        Computation comp = m.computation(m.field("/Invoice/Note")).name("NoteComp")
                .calc(new StringLiteral("Generated"))
                .message(en("Note carries the generated marker.")).build();
        roundTrips(m, comp, "/Invoice/NoteComp");
    }

    @Test
    void booleanConstant() {
        BoundModel m = invoice();
        Computation comp = m.computation(m.field("/Invoice/Flagged")).name("FlaggedComp")
                .calc(new BoolLiteral(true))
                .message(en("Flagged defaults to true.")).build();
        roundTrips(m, comp, "/Invoice/FlaggedComp");
    }
}
