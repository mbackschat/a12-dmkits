package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A rule's possible causes, authored as handles and stored as A12's relative paths from the rule's group
 * ([KERNEL-FINDINGS KF682]). The kernel is the oracle for the derived spelling: it refuses every entry that is
 * not the one it admits, so an accepted rule proves each entry, and an unreferenced cause proves the filter
 * reaches it at all.
 */
class RulePossibleCausesTest {

    private static final String MODEL = DocumentModelDraft.named("claims").locale("en_US")
            .rootGroup("Policy", p -> p.numberField("Premium", f -> { })
                    .stringField("Holder", f -> { })
                    .group("Rider", r -> r.stringField("Code", f -> { }))
                    .group("Lines", l -> l.repeatable(5).indexField("Key")
                            .stringField("Key", f -> { })
                            .numberField("Amount", f -> { })))
            .build().toJson();

    private final GroupEditor policy = EditSession.read(MODEL).group("/Policy");
    private final GroupEditor lines = EditSession.read(MODEL).group("/Policy/Lines");

    @Test
    void causesAreSpelledFromTheRulesGroupAndTheKernelAdmitsEveryOne() {
        NumberRef amount = lines.num("Amount");
        NumberRef premium = policy.num("Premium");
        // tag::rule-possible-causes[]
        Rule rule = lines.rule(r -> r.name("AmountAbovePremium").code("ABOVE").errorField(amount)
                .when(Dsl.groupFilled(lines.ref()).and(amount.gt(premium)))
                .possibleCauses(PossibleCauses.filled(amount.field(), premium.field())  // filled values to blame
                        .andNotFilled(lines.ref()))                                    // the empty row to blame
                .message(en("The amount exceeds the premium.")).build());
        rule.possibleCauseFilter();   // ifFilled ["Amount", "../Premium"], ifNotFilled ["./"]
        // end::rule-possible-causes[]

        assertEquals(Optional.of(new PossibleCauseFilter(List.of("Amount", "../Premium"), List.of("./"))),
                rule.possibleCauseFilter());
        ValidationResult verdict = new RuleValidator().check(MODEL, rule.draft());
        assertTrue(verdict.ok(), () -> "A12 admits each derived entry: " + verdict.diagnostics());
    }

    @Test
    void aCauseTheConditionDoesNotReadReachesTheKernelsCheck() {
        NumberRef premium = policy.num("Premium");
        Rule rule = policy.rule(r -> r.name("PremiumKnown").code("KNOWN").errorField(premium)
                .when(premium.filled().and(Dsl.groupFilled(policy.group("Rider"))))
                .possibleCauses(PossibleCauses.filled(policy.group("Rider"), policy.str("Holder").field()))
                .message(en("Check the premium.")).build());

        List<String> codes = new RuleValidator().check(MODEL, rule.draft()).diagnostics().stream()
                .map(Diagnostic::code).flatMap(Optional::stream).toList();
        assertEquals(List.of("MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED"), codes);
    }

    @Test
    void theSameCauseTwiceInOneListIsRefused() {
        NumberRef premium = policy.num("Premium");
        Rule rule = policy.rule(r -> r.name("PremiumKnown").code("KNOWN").errorField(premium)
                .when(premium.filled()).possibleCauses(PossibleCauses.filled(premium.field(), premium.field()))
                .message(en("Check the premium.")).build());

        List<String> codes = new RuleValidator().check(MODEL, rule.draft()).diagnostics().stream()
                .map(Diagnostic::code).flatMap(Optional::stream).toList();
        assertEquals(List.of("CUSTOM_REF_ERROR_ENTITY_IF_FILLED_DUPLICATE"), codes);
    }

    @Test
    void aWrittenFilterReadsBackAsWritten() {
        NumberRef premium = policy.num("Premium");
        Rule rule = policy.rule(r -> r.name("PremiumKnown").code("KNOWN").errorField(premium)
                .when(premium.filled()).possibleCauses(PossibleCauses.notFilled(premium.field()))
                .message(en("Check the premium.")).build());

        String written = ModelWriter.addRule(MODEL, rule);

        assertEquals(rule.possibleCauseFilter(), ModelIndex.ofSelfContained(written).rules().get(0).possibleCauses());
        Rule unfiltered = policy.rule(r -> r.name("PremiumKnown").code("KNOWN").errorField(premium)
                .when(premium.filled()).message(en("Check the premium.")).build());
        assertEquals(Optional.empty(), ModelIndex.ofSelfContained(ModelWriter.addRule(MODEL, unfiltered))
                .rules().get(0).possibleCauses(), "a rule without a filter reads as one");
    }
}
