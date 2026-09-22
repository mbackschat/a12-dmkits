package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The grouping backstop on the read path: a stored {@code { … }} group around a SIMPLE operand may not be
 * dropped from a converted readback, because dropping it can turn a rule the kernel accepts into one the
 * same kernel rejects.
 *
 * <p><b>Why the loss is not the converter's.</b> A12 records grouping only on a composite:
 * {@code ParseTreeCreator.exitBRACKET_OP} calls {@code setGeklammert()} only when the braced operand is an
 * {@code ICompositeOperation}, so a brace around a bare clock constant leaves no trace in the parse tree we
 * read. The checker, which walks the ANTLR contexts instead ({@code CheckOpUtils} matches
 * {@code BRACKET_OPContext}), does see it — and treats it as decisive: {@code {Today} == Today} is admitted
 * and {@code Today == Today} draws {@code MVK_INVALID_COMPARE_TO_CONST}. So the two texts are NOT
 * interchangeable while their parse trees are indistinguishable, and a converted read is free to hand back
 * an illegal replacement for a legal rule while reporting itself converted — reported against dmtool 0.13.0
 * by a12-kernel-lean on 2026-09-08 and reproduced at {@code e7ce81063}.
 *
 * <p>The three counting backstops beside this one ({@code verifyOuterPreserved} and friends) cannot reach it:
 * they count what the source parse tree carries, and this is exactly the construct it does not carry. The
 * guard therefore counts groups in the stored TEXT and lets the kernel arbitrate — see
 * {@link #aRedundantGroupOnACompositeStillReadsBack}, which is the false positive that arbitration exists
 * to prevent.
 */
class GroupingPreservedReadTest {

    private static final String MODEL = DocumentModelDraft.named("grouping-order").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .numberField("Total", f -> f.fractionDigits(2)))
            .build().toJson();

    private static String withRule(String condition) {
        return ModelWriter.addRule(MODEL, "/Order", "GroupingProbe", "../Reference", "GRP",
                condition, "ERROR", List.of(en("x")));
    }

    private static ReadResult read(String condition) {
        return KernelAdapter.of(BoundModel.ofJson(withRule(condition))).read("/Order/GroupingProbe");
    }

    /**
     * The premise, measured rather than assumed: the braced form is admitted and the unbraced one is not.
     * Without this row the guard below could pass over a kernel that had stopped caring about the brace.
     */
    @Test
    void theBraceIsWhatMakesTheStoredRuleLegal() {
        String braced = "FieldFilled(/Order/Reference) And {Today} == Today";
        KernelAdapter adapter = KernelAdapter.of(BoundModel.ofJson(withRule(braced)));
        assertTrue(adapter.diagnose("/Order/GroupingProbe", braced).ok(),
                "the kernel admits the braced clock comparison");
        assertFalse(adapter.diagnose("/Order/GroupingProbe",
                        "FieldFilled(/Order/Reference) And Today == Today").ok(),
                "the kernel refuses the same comparison unbraced — the brace is load-bearing");
    }

    @Test
    void aBracedClockOperandIsNeverConvertedIntoTheUnbracedRule() {
        String braced = "FieldFilled(/Order/Reference) And {Today} == Today";
        ReadResult result = read(braced);

        ReadResult.Opaque opaque = assertInstanceOf(ReadResult.Opaque.class, result,
                "a readback that cannot carry the brace must degrade to Opaque, not publish the unbraced rule");
        assertEquals(braced, opaque.canonicalText(), "the stored condition is preserved verbatim");
        assertTrue(opaque.reason().contains("group"),
                () -> "the reason names the dropped grouping: " + opaque.reason());
    }

    /** The doubled brace is the same loss twice over, and the unbraced result is equally illegal. */
    @Test
    void aDoublyBracedClockOperandDegradesToo() {
        assertInstanceOf(ReadResult.Opaque.class, read("FieldFilled(/Order/Reference) And {{Today}} == Today"),
                "a doubled group is dropped by the same kernel path and must degrade the same way");
    }

    /**
     * The arbitration's job. A group the renderer's own precedence rules make redundant is dropped
     * faithfully — the kernel accepts the rendering exactly as it accepted the source — so the rule must
     * still read back typed. A guard that degraded on the missing brace alone would lose these.
     */
    @Test
    void aRedundantGroupOnACompositeStillReadsBack() {
        assertInstanceOf(ReadResult.Converted.class,
                read("FieldFilled(/Order/Reference) And {[/Order/Total] + 1} > 0"),
                "a precedence-redundant group is not a loss: the kernel admits the rendering too");
    }

    /** The ordinary case stays untouched: no group in the source, nothing to arbitrate, no kernel re-parse. */
    @Test
    void anUngroupedConditionIsUnaffected() {
        assertInstanceOf(ReadResult.Converted.class, read("FieldFilled(/Order/Reference)"),
                "a condition carrying no group must take the guard's cheap exit");
    }

    // ---- the sibling site: a computation's precondition is a condition, and loses the group the same way ----

    private static String withComputation(String precondition) {
        return ModelWriter.addComputation(MODEL, DraftComputation.builder()
                .groupPath("/Order").name("TotalComp").computedFieldRelPath("../Total")
                .commonPrecondition(precondition)
                .alternatives(List.of(new DraftComputation.Alternative(null, "1")))
                .build(), List.of(en("x")));
    }

    private static ReadComputation readComputation(String precondition) {
        return KernelAdapter.of(BoundModel.ofJson(withComputation(precondition)))
                .readAllComputations().get("/Order/TotalComp");
    }

    /**
     * The same defect one site over, found by the sweep rather than by the report: a computation's
     * precondition is an ordinary condition, so the kernel drops its group on a simple operand exactly as it
     * does for a rule, and {@code computation read} published {@code Today == Today} with
     * {@code converted:true} — a precondition {@code computation modify --dry-run} then refuses
     * {@code MVK_INVALID_COMPARE_TO_CONST}. Measured on the live binary at {@code e7ce81063}.
     */
    @Test
    void aComputationPreconditionIsNeverConvertedIntoTheUnbracedForm() {
        ReadComputation result = readComputation("{Today} == Today");
        ReadComputation.Opaque opaque = assertInstanceOf(ReadComputation.Opaque.class, result,
                "a computation whose precondition loses its group must degrade, not publish the unbraced slot");
        assertTrue(opaque.reason().contains("group"),
                () -> "the reason names the dropped grouping: " + opaque.reason());
    }

    /** The cheap exit on the computation side too — an ungrouped computation is read typed as before. */
    @Test
    void anUngroupedComputationIsUnaffected() {
        assertInstanceOf(ReadComputation.Converted.class, readComputation("FieldFilled(/Order/Reference)"),
                "a computation carrying no group must be untouched by the guard");
    }
}
