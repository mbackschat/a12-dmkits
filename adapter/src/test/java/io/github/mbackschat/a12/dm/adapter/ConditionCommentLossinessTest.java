package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;

/**
 * Locks the KERNEL-FINDINGS "{@code ;;} comments are concrete syntax" entry. The kernel condition DSL
 * <b>tolerates</b> {@code ;;} line comments (grammar {@code Base_ConditionLexer.Comment}); they are
 * concrete syntax <b>not carried in the parsed AST</b>, so an AST-based read→re-render cannot reproduce
 * them on its own. rulekit closes the common gap: the rule's <b>leading comment is preserved by
 * default</b> through read→modify→write-back ({@code Comments.preserveLeading}), and any
 * <em>inline/trailing</em> comment beyond it is still re-rendered away — <b>disclosed</b>, never silent.
 */
class ConditionCommentLossinessTest {

    private static String modelWithRule(String condition) {
        String structure = DocumentModelDraft.named("subscription").locale("en_US").locale("de_DE")
                .rootGroup("Subscription", s -> s
                        .group("Addons", a -> a
                                .repeatable(99)
                                .numberField("MonthlyFee", n -> n.fractionDigits(2))))
                .build().toJson();
        return EditSession.read(structure)
                .addRule("/Subscription/Addons", "FeeCap", "../MonthlyFee", "FEE_CAP", condition, "ERROR",
                        List.of(Dsl.en("Monthly fee is too high."), Dsl.de("Monatsgebühr ist zu hoch.")))
                .validate()   // the kernel ACCEPTS the comment → ;; comments are tolerated
                .toJson();
    }

    @Test
    void aLeadingCommentIsPreservedThroughReExpressNotDroppedNorFlaggedLossy() {
        String json = modelWithRule(";; cap the monthly fee\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000");
        KernelAdapter adapter = KernelAdapter.ofJson(json);

        // the comment is concrete syntax — the condition still reads cleanly to a typed AST
        ReadResult read = adapter.read("/Subscription/Addons/FeeCap");
        assertTrue(read instanceof ReadResult.Converted, "a commented condition still converts to a typed AST");

        // diff carries the leading comment on both sides (write-back preserves it), so it is NOT lossy
        RenderedDiff diff = adapter.diff("/Subscription/Addons/FeeCap", ((ReadResult.Converted) read).condition());
        assertTrue(diff.after().contains(";; cap the monthly fee"),
                "the leading comment is carried over, not dropped: " + diff.after());
        assertTrue(diff.lossiness().isEmpty(),
                "a leading-only comment is fully preserved, so nothing is disclosed lossy: " + diff.lossiness());
    }

    @Test
    void anInlineOrTrailingCommentIsStillDroppedAndDisclosed() {
        // a comment beyond the leading one (here trailing the whole condition) cannot ride the AST — it is
        // re-rendered away on write-back, and the diff says so up front.
        String json = modelWithRule("FieldFilled(MonthlyFee) And [MonthlyFee] > 1000 ;; legacy threshold");
        KernelAdapter adapter = KernelAdapter.ofJson(json);

        ReadResult read = adapter.read("/Subscription/Addons/FeeCap");
        assertTrue(read instanceof ReadResult.Converted, "the condition still converts to a typed AST");

        RenderedDiff diff = adapter.diff("/Subscription/Addons/FeeCap", ((ReadResult.Converted) read).condition());
        assertTrue(diff.lossiness().stream().anyMatch(note -> note.contains("inline/trailing comments")),
                "the dropped inline/trailing comment must be disclosed: " + diff.lossiness());
        assertFalse(diff.after().contains(";; legacy threshold"),
                "the inline/trailing comment is re-rendered away: " + diff.after());
    }
}
