package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * F10 ingest tail — a value list whose entry is a <em>field</em>, not a constant:
 * {@code FieldValueIncludedInValueList(Choice, [Allowed])}. The kernel's value list carries operations, so an
 * entry can be a field reference; the converter builds the membership with a field-valued entry. Own domain:
 * a choice field checked against a single allowed-value field.
 */
class ValueListFieldEntryIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String pickJson() {
        return DocumentModelDraft.named("pick").locale("en_US")
                .rootGroup("Pick", p -> p
                        .stringField("Choice", s -> { })
                        .stringField("Allowed", s -> { }))
                .build().toJson();
    }

    @Test
    void ingestsAValueListWhoseEntryIsAField() {
        String rule = "FieldFilled(Choice) And FieldValueIncludedInValueList(Choice, [Allowed])";
        var converted = assertInstanceOf(Ingest.Converted.class,
                KernelAdapter.ofJson(inject(pickJson(), rule)).ingest("/Pick/PickRule"),
                "a value list with a field entry should convert");
        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("FieldValueIncludedInValueList(/Pick/Choice, [/Pick/Allowed])"), rendered);
    }

    /** Inject a raw-text rule into the /Pick group (a field-valued value list can't be built via the typed DSL). */
    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Pick/PickRule\", \"name\": \"PickRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Choice\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}
