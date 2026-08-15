package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Refactor;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.RefactorKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * {@code CG16} — the DIFF surfaces over a rule whose operand is a <b>GROUP</b>. The sibling
 * {@code GroupReachedReaderRiskTest} covers the two reader surfaces; these are the two the first slice left
 * out, and both fail in the direction that reads as reassurance.
 *
 * <p><b>Why a group operand is the blind spot.</b> A rule may name a group and no field inside it — the kernel
 * expands it recursively — so the rule's reference set contains a GROUP path and no field path. Every surface
 * that models "what a rule references" as a set of field paths therefore sees an empty answer and reports the
 * safe-looking thing: a removed group with live referrers is classified as an ordinary structural removal, and
 * a group rename that every referrer followed is not recognized as a refactor at all.
 */
class GroupReferenceDiffTest {

    private static final String ASSESSMENT = "/Policy/Assessment";
    private static final String RULE = "/Policy/AssessmentComplete";

    /**
     * A policy model whose only rule names the GROUP {@code group} and no field inside it. {@code Archive} is
     * referenced by nothing, so a row that passes for every group would pass here too — it is the control.
     */
    private static String modelNaming(String group) {
        String base = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .field("Reference", FieldKind.STRING)
                        .group(group.substring(group.lastIndexOf('/') + 1), a -> a.field("Observed", FieldKind.NUMBER))
                        .group("Archive", ar -> ar.field("Note", FieldKind.STRING)))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(m -> m.rule(m.group("/Policy")).name("AssessmentComplete")
                .errorField(m.str("/Policy/Reference"))
                .when(m.str("/Policy/Reference").filled()
                        .and(Dsl.notAllFieldsFilled(m.flattenGroup(group))))
                .code("ASSESS_INCOMPLETE").severity(Severity.ERROR)
                .message(new LocalizedText("en_US", "The assessment is incomplete")).build());
        return session.validate().toJson();
    }

    /**
     * The same model with BOTH groups — and the rule naming one of them — gone. Removing the control group
     * too is what makes the control a control: it has to be a REMOVED group change that simply has no
     * referrer, not a group that never changed.
     */
    private static String withoutTheGroups() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.field("Reference", FieldKind.STRING))
                .build().toJson();
    }

    /**
     * <b>A removed GROUP with a live referrer must say so.</b> The FIELD arm has carried
     * {@code REFERENCED_ELEMENT_REMOVED} since the first slice; the GROUP arm was handed no referrers at all,
     * so removing the one thing a rule actually names came back as a bare {@code STRUCTURE_REMOVED} — the same
     * classification an unreferenced group gets, which is precisely the distinction a reviewer needs.
     */
    @Test
    void aRemovedGroupWithAReferrerIsFlagged() {
        ModelDiff diff = ModelDiff.between(modelNaming(ASSESSMENT), withoutTheGroups());
        ElementChange removed = groupChange(diff, ASSESSMENT);
        assertTrue(removed.reasons().contains("REFERENCED_ELEMENT_REMOVED"),
                () -> "a rule names this group directly; its removal is not an ordinary structural removal: "
                        + removed.reasons());
    }

    /** The CONTROL: an unreferenced group's removal must NOT gain the reason, or the row above is vacuous. */
    @Test
    void aRemovedGroupWithNoReferrerIsNotFlagged() {
        ModelDiff diff = ModelDiff.between(modelNaming(ASSESSMENT), withoutTheGroups());
        ElementChange archive = groupChange(diff, "/Policy/Archive");
        assertEquals(ChangeKind.REMOVED, archive.change(), "the control group is removed too");
        assertTrue(!archive.reasons().contains("REFERENCED_ELEMENT_REMOVED"),
                () -> "nothing reads /Policy/Archive: " + archive.reasons());
    }

    /**
     * <b>A group RENAME that its referrer followed is a refactor.</b> {@code groupRefactors} matches a
     * rename/move against each rule's dropped/gained references, and those came from {@code fieldPaths()}
     * alone — so a group path could never appear on either side and the fold never fired for a group. The
     * change-set showed a RENAMED group and an unrelated-looking MODIFIED rule, which is the raw material the
     * fold exists to interpret.
     */
    @Test
    void aRenamedGroupWithAReferrerFoldsIntoOneRefactor() {
        List<Refactor> refactors =
                ModelDiff.between(modelNaming(ASSESSMENT), modelNaming("/Policy/Evaluation")).refactors();
        assertEquals(1, refactors.size(), () -> "one refactor group, got: " + refactors);
        Refactor r = refactors.get(0);
        assertEquals(RefactorKind.RENAME, r.kind());
        assertEquals(ASSESSMENT, r.from());
        assertEquals("/Policy/Evaluation", r.to());
        assertEquals(List.of(RULE), r.referrers(), () -> "the rule that followed the rename: " + r);
    }

    private static ElementChange groupChange(ModelDiff diff, String id) {
        return diff.changes().stream()
                .filter(c -> c.element() == ElementKind.GROUP && c.id().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("no GROUP change for " + id
                        + " in " + diff.changes()));
    }
}
