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
 * Locks the KERNEL-FINDINGS "{@code ;;} comments are concrete syntax" entry: the kernel condition DSL
 * <b>tolerates</b> {@code ;;} line comments (grammar {@code Base_ConditionLexer.Comment}), but they are
 * concrete syntax <b>not carried in the parsed AST</b> — so an AST-based read→re-render <b>drops</b> them
 * (the "#3 lossiness"), and rulekit <b>discloses</b> that loss rather than reformatting silently.
 */
class ConditionCommentLossinessTest {

    // a condition carrying a ;; line comment — injected verbatim via the text/spec path (the typed builder
    // never authors comments). errorEntityRelPath + the condition use the field relative to the rule group.
    private static final String COMMENTED =
            ";; cap the monthly fee\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000";

    private static String modelWithCommentedRule() {
        String structure = DocumentModelDraft.named("subscription").locale("en_US").locale("de_DE")
                .rootGroup("Subscription", s -> s
                        .group("Addons", a -> a
                                .repeatable(99)
                                .numberField("MonthlyFee", n -> n.fractionDigits(2))))
                .build().toJson();
        return EditSession.read(structure)
                .addRule("/Subscription/Addons", "FeeCap", "../MonthlyFee", "FEE_CAP", COMMENTED, "ERROR",
                        List.of(Dsl.en("Monthly fee is too high."), Dsl.de("Monatsgebühr ist zu hoch.")))
                .validate()   // the kernel ACCEPTS the comment → ;; comments are tolerated
                .toJson();
    }

    @Test
    void theKernelToleratesCommentsButReRenderDropsThemAndDisclosesIt() {
        KernelAdapter adapter = KernelAdapter.ofJson(modelWithCommentedRule());

        // the comment is concrete syntax — the condition still reads cleanly to a typed AST
        ReadResult read = adapter.read("/Subscription/Addons/FeeCap");
        assertTrue(read instanceof ReadResult.Converted, "a commented condition still converts to a typed AST");

        // re-rendering from the AST drops the comment — disclosed as lossiness (never silent), and the
        // re-rendered text carries no ;; comment
        RenderedDiff diff = adapter.diff("/Subscription/Addons/FeeCap", ((ReadResult.Converted) read).condition());
        assertTrue(diff.lossiness().stream().anyMatch(note -> note.contains("comments")),
                "the dropped comment must be disclosed as lossiness: " + diff.lossiness());
        assertFalse(diff.after().contains(";;"), "the re-rendered condition carries no ;; comment");
    }
}
