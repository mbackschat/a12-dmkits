package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Message tokens that read a key-selected row: {@code $Field.value For key$}, {@code $Field For key$} and
 * {@code $Field->Category For key$}, the suffix written before the key. The kernel admits each beside a
 * condition that selects the same field under the same spelled key (KERNEL-FINDINGS KF412).
 *
 * <p>Own domain (an insurance policy whose claims are keyed by reference).
 */
class KeyedMessageTokenTest {

    private static final String POLICY = DocumentModelDraft.named("policy-keyed-message").locale("en_US")
            .rootGroup("Policy", p -> p.stringField("Holder", f -> { }).stringField("Pick", f -> { })
                    .group("Claims", c -> c.repeatable(5).indexField("Ref")
                            .stringField("Ref", f -> { }).stringField("Note", f -> { })
                            .enumField("Status", e -> e.value("OPEN").value("SHUT").category("Band", "HIGH", "LOW"))))
            .build().toJson();

    private final GroupEditor policy = EditSession.read(POLICY).group("/Policy");
    private final StringRef note = policy.str("Claims/Note");
    private final EnumRef status = policy.enumOf("Claims/Status");

    @Test
    void aLiteralKeyFollowsTheSuffixAndDoublesItsQuotes() {
        assertEquals("$/Policy/Claims/Note.value For \"c\"\"1\"$", Dsl.fieldValue(note.semanticIndex("c\"1")));
        assertEquals("$/Policy/Claims/Note For \"c1\"$", Dsl.fieldLabel(note.semanticIndex("c1")));
        assertEquals("$/Policy/Claims/Status->Band For \"c1\"$",
                Dsl.fieldCategory(status.category("Band"), "c1"));
    }

    @Test
    void aFieldKeyIsWrittenAsItsPath() {
        assertEquals("$/Policy/Claims/Note.value For /Policy/Pick$",
                Dsl.fieldValue(note.semanticIndex(policy.field("Pick"))));
        assertEquals("$/Policy/Claims/Status->Band For /Policy/Pick$",
                Dsl.fieldCategory(status.category("Band"), policy.field("Pick")));
    }

    @Test
    void anUnkeyedCategoryTokenNamesTheFieldAndItsCategory() {
        assertEquals("$/Policy/Claims/Status->Band$", Dsl.fieldCategory(status.category("Band")));
    }

    @Test
    void theKernelAdmitsEachKeyedTokenBesideTheMatchingKeyedRead() {
        StringRef holder = policy.str("Holder");
        SemanticIndex selected = note.semanticIndex("c1");
        SemanticIndex selectedStatus = status.semanticIndex("c1");
        var rule = policy.rule(r -> r.name("KeyedNote").code("KEYED_NOTE").errorField(holder)
                .when(holder.filled().and(selected.filled()).and(selectedStatus.filled()))
                .message(en(Dsl.fieldValue(selected) + " / " + Dsl.fieldLabel(selected) + " / "
                        + Dsl.fieldCategory(status.category("Band"), "c1"))).build());
        var checked = new RuleValidator().check(POLICY, rule.draft());
        assertTrue(checked.ok(), () -> "keyed message tokens must be kernel-admitted: " + checked.diagnostics());
    }

    @Test
    void theKernelAdmitsFieldKeyedTokensBesideTheSameFieldKey() {
        FieldRef pick = policy.field("Pick");
        SemanticIndex selected = note.semanticIndex(pick);
        var rule = policy.rule(r -> r.name("PickedNote").code("PICKED_NOTE").errorField(policy.str("Pick"))
                .when(policy.str("Pick").filled().and(selected.filled()).and(status.semanticIndex(pick).filled()))
                .message(en(Dsl.fieldValue(selected) + " / " + Dsl.fieldCategory(status.category("Band"), pick)))
                .build());
        var checked = new RuleValidator().check(POLICY, rule.draft());
        assertTrue(checked.ok(), () -> "field-keyed message tokens must be kernel-admitted: " + checked.diagnostics());
    }
}
