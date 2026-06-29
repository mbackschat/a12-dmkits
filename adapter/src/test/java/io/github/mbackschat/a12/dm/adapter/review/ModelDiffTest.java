package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** MODEL-REVIEW spike — TDD for the structural diff engine (task #3): load + element matching. */
class ModelDiffTest {

    private static final String FEE_CAP = "/Subscription/Addons/FeeCapRule";

    private static String base() {
        return readResource("models/subscription.dm.json");
    }

    /** A valid head: {@code base} + one rulekit-authored rule (the proven valid-model idiom). */
    private static String withRule(String json) {
        BoundModel m = BoundModel.ofJson(json);
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule").errorField(fee).when(fee.gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee too high."), de("Monatsgebühr zu hoch.")).build();
        return m.add(rule).toJson();
    }

    @Test
    void detectsAddedAndRemovedRule() {
        String base = base();
        String head = withRule(base);

        assertTrue(has(ModelDiff.between(base, head), ElementKind.RULE, ChangeKind.ADDED, FEE_CAP),
                "the new rule must be reported ADDED");
        // the inverse direction reports it REMOVED — diff is directional
        assertTrue(has(ModelDiff.between(head, base), ElementKind.RULE, ChangeKind.REMOVED, FEE_CAP),
                "base-from-head must report the rule REMOVED");
    }

    @Test
    void detectsAddedField() {
        String base = base();
        String head = EditSession.read(base)
                .addField("/Subscription/Addons", "Promo", FieldKind.NUMBER)
                .validate().toJson();

        assertTrue(has(ModelDiff.between(base, head), ElementKind.FIELD, ChangeKind.ADDED,
                "/Subscription/Addons/Promo"), "the new field must be reported ADDED");
    }

    @Test
    void identicalAndReformattedShowNoChanges() {
        String base = base();
        assertEquals(0, ModelDiff.between(base, base).changes().size(), "identical models must diff to nothing");

        // the whole point of a STRUCTURAL diff: a reformat (different whitespace, same model) is NOT a change
        String reformatted = io.github.mbackschat.a12.dm.modelkit.ModelDocument.read(base).toJson();
        assertEquals(0, ModelDiff.between(base, reformatted).changes().size(),
                "a pure reformat must not register as a change");
    }

    private static boolean has(ModelDiff diff, ElementKind kind, ChangeKind change, String id) {
        return diff.changes().stream().anyMatch(
                (ElementChange c) -> c.element() == kind && c.change() == change && c.id().equals(id));
    }

    private static String readResource(String path) {
        try (InputStream in = ModelDiffTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
