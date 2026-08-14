package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The two ADAPTER-side readers of the reference inverse, over a rule whose operand is a <b>GROUP</b> —
 * {@code ModelReport}'s dead-field set and {@code FieldDiffProjector.hasReaders}. {@code CG16}'s reader half
 * changed both, and a cold review found neither had a test: the only coverage drove {@code model usage} through
 * the CLI, so two surfaces were advertised in javadoc and in CLI-SPEC §6 with nothing failing behind them.
 *
 * <p><b>The consequence each row pins.</b> A field read only through {@code AllFieldsFilled(/Policy/Assessment)}
 * used to land in {@code unreferenced} — the report's own headline, read as "safe to delete" — and to draw
 * neither {@code REFERENCED_ELEMENT_REMOVED} nor {@code RETYPE_AFFECTS_READERS}, so a diff called a
 * behaviour-changing edit low-risk.
 */
class GroupReachedReaderRiskTest {

    private static final String OBSERVED = "/Policy/Assessment/Observed";
    private static final String UNREAD = "/Policy/Archive/Note";

    /**
     * A rule naming the GROUP {@code /Policy/Assessment} and no field inside it; {@code /Policy/Archive/Note} is
     * reached by nothing and is the control that keeps every row below from passing vacuously.
     */
    private static String model(FieldKind observedKind) {
        String base = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .field("Reference", FieldKind.STRING)
                        .group("Assessment", a -> a.field("Observed", observedKind))
                        .group("Archive", ar -> ar.field("Note", FieldKind.STRING)))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(m -> m.rule(m.group("/Policy")).name("AssessmentComplete")
                .errorField(m.str("/Policy/Reference"))
                .when(m.str("/Policy/Reference").filled()
                        .and(Dsl.notAllFieldsFilled(m.flattenGroup("/Policy/Assessment"))))
                .code("ASSESS_INCOMPLETE").severity(Severity.ERROR)
                .message(new LocalizedText("en_US", "The assessment is incomplete")).build());
        return session.validate().toJson();
    }

    /** <b>A3</b> — the report's dead-field set must not call a group-reached field dead. */
    @Test
    void theDeadFieldSetExcludesAGroupReachedField() {
        ModelReport report = ModelReport.of(model(FieldKind.NUMBER));
        assertFalse(report.usage().unreferenced().contains(OBSERVED),
                () -> OBSERVED + " is read through AllFieldsFilled(/Policy/Assessment); the dead-field set is "
                        + "what a reader acts on: " + report.usage().unreferenced());
        assertTrue(report.usage().unreferenced().contains(UNREAD),
                () -> "the control: a genuinely unread field must STILL be reported dead, or this row is also "
                        + "satisfied by an empty set: " + report.usage().unreferenced());
    }

    /** <b>A4</b> — a retype of a group-reached field warns that it affects readers. */
    @Test
    void retypingAGroupReachedFieldIsFlaggedAsAffectingReaders() {
        ElementChange retyped = change(
                ModelDiff.between(model(FieldKind.NUMBER), model(FieldKind.STRING)), OBSERVED);
        assertTrue(retyped.reasons().contains("RETYPE_AFFECTS_READERS"),
                () -> "the rule reads Observed through its group, so a kind change affects it: "
                        + retyped.reasons());
        assertEquals(ModelDiff.Risk.HIGH, retyped.risk(), () -> retyped.reasons().toString());
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + id + " in " + diff.changes()));
    }
}
