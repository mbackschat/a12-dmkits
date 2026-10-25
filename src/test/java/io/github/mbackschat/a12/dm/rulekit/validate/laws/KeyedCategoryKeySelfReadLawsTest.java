package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * KERNEL-FINDINGS KF182 on a keyed category: a computation whose operation reads a category keyed BY its own target,
 * {@code [Lines/Grade -> Tier For Pick]} into {@code Pick}, is A12's projected self-read, the class the bare
 * {@code [Lines/Sku For Pick]} draws ({@code ComputedTargetProjectedReadLawsTest}). The key is what is matched, not
 * the category's field, so the literal-keyed and other-field-keyed reads are the accepted controls. The typed
 * builder names the same class before the kernel is asked (2026-10-25 cold review, R14). Own domain (an order's lines).
 */
class KeyedCategoryKeySelfReadLawsTest {

    private static final String PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD";

    private static final String ORDER = DocumentModelDraft.named("order-keyed-category-key").locale("en_US")
            .rootGroup("Order", o -> o
                    .stringField("Pick", f -> f.maxLength(10))
                    .stringField("Other", f -> f.maxLength(10))
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> f.maxLength(10))
                            .enumField("Grade", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))))
            .build().toJson();

    private final GroupEditor order = EditSession.read(ORDER).group("/Order");
    private final CategoryRef tier = order.enumOf("Lines/Grade").category("Tier");

    private static List<String> kernel(String operation) {
        var result = new RuleValidator().check(ORDER, new DraftComputation("/Order", "PickComp", "../Pick", null,
                List.of(new DraftComputation.Alternative(null, operation)), List.of()));
        return result.diagnostics().stream().map(d -> d.code().orElse("(no code)")).toList();
    }

    @Test
    void aCategoryKeyedByTheComputedFieldIsTheProjectedSelfRead() {
        assertEquals(List.of(PROJECTED), kernel("[/Order/Lines/Grade -> Tier For /Order/Pick]"));
        assertEquals(List.of(PROJECTED), kernel("[/Order/Lines/Sku For /Order/Pick]"));
        assertEquals(List.of(), kernel("[/Order/Lines/Grade -> Tier For \"a\"]"));
        assertEquals(List.of(), kernel("[/Order/Lines/Grade -> Tier For /Order/Other]"));
        assertEquals(List.of(PROJECTED), kernel("[/Order/Lines/Grade -> Tier For /Order/Pick] + \"-x\""));
        assertEquals(List.of(), kernel("[/Order/Lines/Grade -> Tier For /Order/Other] + \"-x\""));
    }

    /** Through a concatenation, so the builder's kind gate passes and the reference fold decides (DG159 tracks
     *  that gate refusing the bare category into a text field, which A12 admits above). */

    @Test
    void theTypedBuilderNamesTheSameClass() {
        PrecheckException refused = assertThrows(PrecheckException.class, () -> order.computation(order.str("Pick"),
                b -> b.name("PickComp").calc(tier.forIndex(order.field("Pick")).plus("-x"))
                        .message(Dsl.en("The picked tier.")).build()));
        assertEquals(Optional.of(PROJECTED), refused.diagnostic().code(), refused::getMessage);

        Computation otherKeyed = order.computation(order.str("Pick"), b -> b.name("PickComp")
                .calc(tier.forIndex(order.field("Other")).plus("-x")).message(Dsl.en("The picked tier.")).build());
        assertEquals(List.of(), new RuleValidator().check(ORDER, otherKeyed.draft()).diagnostics());
    }
}
