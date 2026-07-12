package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * The comment side-channel end-to-end (carrier "c"): comments live off the AST in {@link Comments}, the
 * renderer emits them, and here they round-trip through the write path.
 *
 * <ol>
 *   <li><b>read → modify → write-back ⟹ the comment survives</b> — a rule already carrying a {@code ;;}
 *       comment is read, its condition is changed, and the comment is re-attached + persisted;</li>
 *   <li><b>add a comment + write ⟹ it exists</b> — an authored comment renders to {@code ;;} and lands in
 *       the persisted, kernel-valid model.</li>
 * </ol>
 *
 * <p>(The ergonomic typed-builder {@code .comment(...)} that would set comments on the typed write path is
 * the productization follow-on; the spike proves the capability through render-with-comments + write.)
 */
class CommentRoundTripTest {

    private static String subscription() {
        return DocumentModelDraft.named("subscription").locale("en_US").locale("de_DE")
                .rootGroup("Subscription", s -> s
                        .group("Addons", a -> a.repeatable(99).numberField("MonthlyFee", n -> n.fractionDigits(2))))
                .build().toJson();
    }

    private static List<LocalizedText> msgs() {
        return List.of(Dsl.en("Monthly fee is too high."), Dsl.de("Monatsgebühr ist zu hoch."));
    }

    @Test
    void addsACommentToARuleThatSurvivesTheWrite() {
        BoundModel bm = BoundModel.ofJson(subscription());
        NumberRef fee = bm.num("/Subscription/Addons/MonthlyFee");
        Condition c = fee.filled().and(fee.gt(1000));
        String withComment = new DslRenderer().render(c, Comments.none().before(c, "cap the monthly fee"));

        String json = EditSession.read(subscription())
                .addRule("/Subscription/Addons", "FeeCap", "../MonthlyFee", "FEE_CAP", withComment, "ERROR", msgs())
                .validate().toJson();

        assertTrue(json.contains(";; cap the monthly fee"), "the authored comment is persisted + kernel-valid");
    }

    @Test
    void preservesTheLeadingCommentByDefaultOnModifyWithNoManualSourcing() {
        // #31 — the read→modify→write-back loop carries the leading ;; comment ON ITS OWN: the caller
        // re-expresses only the condition (a bare AST, no comment), yet the comment survives the write.
        String commented = ";; cap the monthly fee\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000";
        String json0 = EditSession.read(subscription())
                .addRule("/Subscription/Addons", "FeeCap", "../MonthlyFee", "FEE_CAP", commented, "ERROR", msgs())
                .validate().toJson();
        String name = "/Subscription/Addons/FeeCap";

        String json1 = EditSession.read(json0)
                .modifyRule(name, bm -> bm.num("/Subscription/Addons/MonthlyFee").filled()
                        .and(bm.num("/Subscription/Addons/MonthlyFee").gt(500)))
                .validate().toJson();

        assertTrue(json1.contains(";; cap the monthly fee"), "the leading comment is preserved by default");
        assertTrue(json1.contains("> 500"), "the modification (1000 → 500) was applied");
    }

    @Test
    void readsACommentedRuleModifiesItAndTheCommentSurvives() {
        String commented = ";; cap the monthly fee\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000";
        String json0 = EditSession.read(subscription())
                .addRule("/Subscription/Addons", "FeeCap", "../MonthlyFee", "FEE_CAP", commented, "ERROR", msgs())
                .validate().toJson();
        String name = "/Subscription/Addons/FeeCap";

        // read it back (the comment is concrete syntax — the condition still converts) and SOURCE the comment
        KernelAdapter adapter = KernelAdapter.ofJson(json0);
        assertTrue(adapter.read(name) instanceof ReadResult.Converted, "the commented condition reads to a typed AST");
        String comment = Comments.leadingCommentOf(adapter.conditionText(name)).orElseThrow();

        // MODIFY the condition (tighten 1000 → 500), re-attaching the preserved comment, and write back
        NumberRef fee = BoundModel.ofJson(json0).num("/Subscription/Addons/MonthlyFee");
        Condition modified = fee.filled().and(fee.gt(500));
        String reText = new DslRenderer().render(modified, Comments.none().before(modified, comment));
        String json1 = EditSession.read(json0).modifyRule(name, reText).validate().toJson();

        assertTrue(json1.contains(";; cap the monthly fee"), "the comment survived read → modify → write-back");
        assertTrue(json1.contains("> 500"), "the modification (1000 → 500) was applied");
    }
}
