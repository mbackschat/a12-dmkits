package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.TestResources;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Refactor;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.RefactorKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * MODEL-REVIEW MR4 — a reference-rewriting refactor (a rename/move + the N referrers it rewrote) is
 * folded into ONE semantic operation: a non-destructive {@code data.refactors[]} overlay whose referrers
 * are the rules whose reference-delta is exactly the same P→P′ repoint. Both directions: a real refactor
 * groups its referrers, and a coincidental reference change (no element was actually renamed) is NOT
 * fabricated into a refactor. The raw change-set stays complete (the fold is an overlay, not a rewrite).
 */
class ModelDiffRefactorTest {

    private static final String ADDONS = "/Subscription/Addons";
    private static final String RULE = ADDONS + "/SurchargeRule";

    private static String base() {
        return TestResources.text("models/subscription.dm.json");
    }

    /** {@code subscription} + a NUMBER field {@code fieldName} in Addons + a rule guarding it. */
    private static String modelReferencing(String fieldName) {
        BoundModel m = BoundModel.ofJson(
                EditSession.read(base()).addField(ADDONS, fieldName, FieldKind.NUMBER).validate().toJson());
        NumberRef f = m.num(ADDONS + "/" + fieldName);
        Rule r = m.rule(m.group(ADDONS)).name("SurchargeRule").errorField(f).when(f.gt(100))
                .code("SURCHARGE").severity(Severity.ERROR)
                .message(en("Surcharge too high."), de("Zuschlag zu hoch.")).build();
        return m.add(r).toJson();
    }

    @Test
    void renameWithAReferrerIsGroupedAsOneRefactor() {
        // Surcharge → ExtraCharge, same group, rule rewritten to the new path
        String base = modelReferencing("Surcharge");
        String head = modelReferencing("ExtraCharge");

        ModelDiff diff = ModelDiff.between(base, head);
        assertEquals(1, diff.refactors().size(), () -> "one refactor group, got: " + diff.refactors());
        Refactor r = diff.refactors().get(0);
        assertEquals(RefactorKind.RENAME, r.kind());
        assertEquals(ADDONS + "/Surcharge", r.from());
        assertEquals(ADDONS + "/ExtraCharge", r.to());
        assertEquals(List.of(RULE), r.referrers(), () -> "the rewritten rule is the referrer: " + r);

        // NON-destructive: the raw RENAMED + the referrer's MODIFIED both still ride the change-set
        assertTrue(diff.changes().stream().anyMatch(c -> c.change() == ChangeKind.RENAMED
                && c.id().equals(ADDONS + "/Surcharge")), "the rename change stays in the change-set");
        assertTrue(diff.changes().stream().anyMatch(c -> c.change() == ChangeKind.MODIFIED
                && c.id().equals(RULE)), "the referrer's raw MODIFIED stays in the change-set");
    }

    @Test
    void moveWithAReferrerIsGroupedAsAMoveRefactor() {
        // same leaf name, different parent → a MOVE; the rule (guarding MonthlyFee) also reads Surcharge,
        // so it follows the new path — a valid model in both states (errorField stays under the rule's group)
        String base = surchargeReaderWithFieldIn(ADDONS);
        String head = surchargeReaderWithFieldIn("/Subscription/Billing");

        List<Refactor> refactors = ModelDiff.between(base, head).refactors();
        assertEquals(1, refactors.size(), () -> "one move refactor, got: " + refactors);
        assertEquals(RefactorKind.MOVE, refactors.get(0).kind());
        assertEquals(List.of(RULE), refactors.get(0).referrers());
    }

    @Test
    void aRenameWithoutReferrersMakesNoRefactorGroup() {
        // rename a field that NO rule references → a RENAMED change, but nothing to group → no refactor entry
        String base = EditSession.read(base()).addField(ADDONS, "Surcharge", FieldKind.NUMBER).validate().toJson();
        String head = EditSession.read(base()).addField(ADDONS, "ExtraCharge", FieldKind.NUMBER).validate().toJson();

        ModelDiff diff = ModelDiff.between(base, head);
        assertTrue(diff.refactors().isEmpty(), () -> "no referrers → no refactor group: " + diff.refactors());
        assertTrue(diff.changes().stream().anyMatch(c -> c.change() == ChangeKind.RENAMED),
                "the standalone rename is still reported");
    }

    @Test
    void aCoincidentalReferenceChangeIsNotFabricatedIntoARefactor() {
        // both Alpha and Beta exist in BOTH states (nothing renamed); the rule is edited to read Beta instead
        // of Alpha. That is a genuine REFERENCE_CHANGED — NOT a refactor fan-out. Must not invent a group.
        String base = ruleReading("Alpha");
        String head = ruleReading("Beta");

        ModelDiff diff = ModelDiff.between(base, head);
        assertTrue(diff.refactors().isEmpty(),
                () -> "a reference change with no matching rename must not become a refactor: " + diff.refactors());
    }

    /** {@code subscription} + BOTH Alpha and Beta fields + a rule reading {@code readField} (Alpha or Beta). */
    private static String ruleReading(String readField) {
        BoundModel m = BoundModel.ofJson(EditSession.read(base())
                .addField(ADDONS, "Alpha", FieldKind.NUMBER)
                .addField(ADDONS, "Beta", FieldKind.NUMBER).validate().toJson());
        NumberRef f = m.num(ADDONS + "/" + readField);
        Rule r = m.rule(m.group(ADDONS)).name("SurchargeRule").errorField(f).when(f.gt(100))
                .code("SURCHARGE").severity(Severity.ERROR)
                .message(en("Too high."), de("Zu hoch.")).build();
        return m.add(r).toJson();
    }

    /** {@code subscription} + a Surcharge field in {@code surchargeGroup}; the Addons rule guards MonthlyFee
     *  but also READS Surcharge, so moving Surcharge repoints the rule while the model stays valid. */
    private static String surchargeReaderWithFieldIn(String surchargeGroup) {
        BoundModel m = BoundModel.ofJson(
                EditSession.read(base()).addField(surchargeGroup, "Surcharge", FieldKind.NUMBER).validate().toJson());
        NumberRef fee = m.num(ADDONS + "/MonthlyFee");
        NumberRef sur = m.num(surchargeGroup + "/Surcharge");
        Rule r = m.rule(m.group(ADDONS)).name("SurchargeRule").errorField(fee)
                .when(fee.gt(100).and(sur.gt(0))).code("SURCHARGE").severity(Severity.ERROR)
                .message(en("Fee or surcharge invalid."), de("Gebühr oder Zuschlag ungültig.")).build();
        return m.add(r).toJson();
    }
}
