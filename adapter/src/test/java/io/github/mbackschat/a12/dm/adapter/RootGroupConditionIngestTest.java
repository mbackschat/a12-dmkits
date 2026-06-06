package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10-D — root-group presence ({@code GroupFilled}/{@code GroupNotFilled} on a <b>root</b> group). The
 * kernel routes these to {@code IRootGroupCondition} (a {@code Vordruck} check) rather than the usual
 * group-presence node; the converter maps them back to the same {@code GroupRef.filled()/notFilled()}.
 * Own domain: a two-form document (two root groups) authored via modelkit.
 */
class RootGroupConditionIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** A document with two root groups (forms) — so GroupFilled/GroupNotFilled on a root is exercisable. */
    private static BoundModel forms() {
        return BoundModel.ofJson(DocumentModelDraft.named("forms").locale("en_US")
                .rootGroup("Main", g -> g.field("Name", FieldKind.STRING))
                .rootGroup("Optional", g -> g.field("Note", FieldKind.STRING))
                .build().toJson());
    }

    @Test
    void roundTripsRootGroupPresence() {
        BoundModel m = forms();
        // each rule self-references its OWN root group (cross-root refs draw MVK_TOO_MANY_PARENT_GROUPS)
        Condition mainFilled = m.group("/Main").filled();          // GroupFilled(/Main)
        Condition optionalAbsent = m.group("/Optional").notFilled(); // GroupNotFilled(/Optional)

        var cases = new java.util.LinkedHashMap<String, Condition>();
        cases.put("/Main/MainFilledRule", mainFilled);
        cases.put("/Optional/OptionalAbsentRule", optionalAbsent);
        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "/Main", "/Main/Name", "MainFilledRule", mainFilled))
                .add(rule(m, "/Optional", "/Optional/Note", "OptionalAbsentRule", optionalAbsent)));

        assertTrue(RENDER.render(mainFilled).contains("GroupFilled(/Main)"), RENDER.render(mainFilled));
        assertTrue(RENDER.render(optionalAbsent).contains("GroupNotFilled(/Optional)"), RENDER.render(optionalAbsent));
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "root-group round-trip mismatch for " + fullName));
    }

    @Test
    void rootGroupPresenceIsKernelValid() {
        BoundModel m = forms();
        Condition c = m.group("/Main").filled();
        ValidationResult r = new RuleValidator().check(m.toJson(),
                new DraftRule("/Main", "MainFilledRule", "../Name", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String groupPath, String errorFieldPath, String name, Condition cond) {
        return m.rule(m.group(groupPath)).name(name).errorField(m.field(errorFieldPath)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }
}
