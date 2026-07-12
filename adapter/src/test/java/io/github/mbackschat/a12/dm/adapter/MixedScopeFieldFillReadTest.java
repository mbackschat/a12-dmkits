package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — a field-fill quantifier over a <em>mixed</em> group+field scope:
 * {@code FieldsNotCollectivelyFilled(Section, Other/Note)} where {@code Section} is a group and
 * {@code Other/Note} a field. The kernel expands each group to its contained fields; the converter makes
 * that explicit (a field-list quantifier over {@code Section/Line1, Section/Line2, Other/Note}). Own
 * domain: a form with a "Section" block and a separate note field.
 */
class MixedScopeFieldFillReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String formJson() {
        return DocumentModelDraft.named("form").locale("en_US")
                .rootGroup("Form", f -> f
                        .group("Section", s -> s.stringField("Line1", b -> { }).stringField("Line2", b -> { }))
                        .group("Other", o -> o.stringField("Note", b -> { })))
                .build().toJson();
    }

    @Test
    void readsAMixedGroupFieldScopeByExpandingTheGroup() {
        String rule = "FieldsNotCollectivelyFilled(Section, Other/Note)";
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(inject(formJson(), rule)).read("/Form/MixedRule"),
                "a mixed group+field field-fill quantifier should convert");
        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains(
                "FieldsNotCollectivelyFilled(/Form/Section/Line1, /Form/Section/Line2, /Form/Other/Note)"),
                rendered);
        // the expanded form is itself kernel-valid — a sound rewrite of the group-scoped form
        assertTrue(new RuleValidator().check(formJson(), new DraftRule("/Form", "R2", "../Other/Note",
                "FieldsNotCollectivelyFilled(Section/Line1, Section/Line2, Other/Note)", "C", "ERROR")).ok(),
                "the expanded field-list form must be kernel-valid");
    }

    /** Inject a raw-text rule into the /Form group (the mixed-scope form can't be built via the typed DSL). */
    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Form/MixedRule\", \"name\": \"MixedRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Other/Note\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}
