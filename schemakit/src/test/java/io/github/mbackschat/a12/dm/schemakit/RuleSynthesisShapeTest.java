package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Tier-A+ rule synthesis (SCHEMAKIT-SPEC §4): a JSON Schema keyword with no field-config home becomes a real
 * a12 rule on import, authored through the typed surface and kernel-accepted. Here: {@code exclusiveMinimum}
 * / {@code exclusiveMaximum} → strict-bound rules (a12 field bounds are inclusive-only).
 */
class RuleSynthesisShapeTest {

    @Test
    void exclusiveBoundsSynthesizeKernelAcceptedRules() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ratio": { "type": "integer", "exclusiveMinimum": 0, "exclusiveMaximum": 100 }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        // the synthesized rules are kernel-valid …
        assertKernelAccepts(imported.dmJson());
        // … present as real conditions (error when the strict bound is violated) …
        assertTrue(imported.dmJson().contains("[ratio] <= 0"), "exclusiveMinimum → an error rule [ratio] <= 0");
        assertTrue(imported.dmJson().contains("[ratio] >= 100"), "exclusiveMaximum → an error rule [ratio] >= 100");
        // … and recorded as Tier-A+ dispositions (two of them)
        assertTrue(imported.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count() == 2,
                "both strict bounds are recorded as Tier-A+ synthesized rules");
    }

    @Test
    void dependentRequiredSynthesizesAConditionalRequirednessRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "card": { "type": "string" },
                    "cvv":  { "type": "string" }
                  },
                  "dependentRequired": { "card": ["cvv"] }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        assertKernelAccepts(imported.dmJson());
        // "if card is present, cvv is required" → error when card filled AND cvv not filled
        assertTrue(imported.dmJson().contains("FieldFilled(card)"));
        assertTrue(imported.dmJson().contains("FieldNotFilled(cvv)"));
        assertTrue(imported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS),
                "dependentRequired is a Tier-A+ synthesized rule");
    }

    @Test
    void constSynthesizesAnEqualityRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "kind":    { "type": "string",  "const": "ORDER" },
                    "version": { "type": "integer", "const": 1 }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        // both consts become kernel-valid equality rules (error when the value differs from the const)
        assertKernelAccepts(imported.dmJson());
        assertTrue(imported.dmJson().contains("CONST_KIND"), "string const → a CONST_KIND rule");
        assertTrue(imported.dmJson().contains("CONST_VERSION"), "integer const → a CONST_VERSION rule");
        assertTrue(imported.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count() == 2,
                "both consts are Tier-A+ synthesized rules");
    }

    @Test
    void multipleOfSynthesizesAModuloRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": { "qty": { "type": "integer", "multipleOf": 5 } }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        assertKernelAccepts(imported.dmJson());
        // divisibility as a modulo rule: x − floor(x/N)·N ≠ 0  (a12 has no modulo operator)
        assertTrue(imported.dmJson().contains("RoundDown"), "multipleOf composes via RoundDown (floor)");
        assertTrue(imported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS),
                "multipleOf is a Tier-A+ synthesized rule");
    }
    @Test
    void declaredPatternMessagesDoNotInterpretThePatternAsMessageParameters() {
        List<Executable> checks = new ArrayList<>();
        for (String pattern : List.of("^x$", "^\\$x$")) checks.add(() -> {
            String control = DocumentModelDraft.named("literal-pattern-control").locale("en_US")
                    .rootGroup("Root", g -> g.stringField("code", f -> f.pattern(pattern, "Check the code format.")))
                    .build().toJson();
            assertKernelAccepts(control);
            String source = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("type", "object", "properties", java.util.Map.of("code",
                            java.util.Map.of("type", "string", "pattern", pattern))));
            String imported = new JsonSchemaImporter().importSchema(source).dmJson();
            assertEquals(pattern, ModelDocument.read(imported).fieldPattern("/Root/code").orElseThrow());
            assertKernelAccepts(imported);
        });
        assertAll(checks);
    }

    @Test
    void generatedRuleMessagesDoNotInterpretPatternConstEnumOrDiscriminatorLiterals() {
        String structure = DocumentModelDraft.named("literal-message-control").locale("en_US")
                .rootGroup("Root", g -> g.stringField("code", f -> { }).stringField("mode", f -> { })
                        .stringField("kind", f -> { }).group("tags", tags -> tags.repeatable(4)
                                .stringField("value", f -> { })))
                .build().toJson();
        var root = EditSession.read(structure).group("/Root");
        var code = root.str("code");
        String codePath = code.field().path().value();
        String groupPath = root.ref().path().value();
        String tagsPath = root.group("tags").path().value();
        String codeName = code.field().path().segments().getLast();
        String modeName = root.str("mode").field().path().segments().getLast();
        String kindName = root.str("kind").field().path().segments().getLast();
        String valueName = root.str("tags/value").field().path().segments().getLast();
        List<Executable> checks = new ArrayList<>();
        String pattern = "^x$";
        var patternConstraint = RuleSynthesis.ValueConstraint.scalar(RuleSynthesis.ConstraintKind.PATTERN, pattern);
        var enabled = List.of(new RuleSynthesis.FieldValue(modeName, "enabled"));
        for (RuleSynthesis.Need need : List.of(
                new RuleSynthesis.ForbiddenPattern(codePath, pattern),
                new RuleSynthesis.ThenConstrained(groupPath, enabled, codeName, patternConstraint),
                new RuleSynthesis.ElseConstrained(groupPath, enabled, codeName, patternConstraint),
                new RuleSynthesis.ContainsCount(tagsPath, List.of(new RuleSynthesis.Clause(valueName, patternConstraint)),
                        RuleSynthesis.ContainsSubject.SCALAR_VALUE, false, 0))) checks.add(() -> {
            var control = EditSession.read(structure);
            var r = control.group(groupPath);
            var field = r.str(codeName);
            r.addRule(b -> b.name("PatternControl").code("PATTERN_CONTROL").errorField(field).when(field.filled().and(field.matches(pattern)))
                    .message(Dsl.en("Check the code format.")).build());
            assertKernelAccepts(control.toJson());
            String result = RuleSynthesis.apply(structure, List.of(need), TranscodeReport.builder(), null);
            var rules = ModelIndex.ofSelfContained(result).rules();
            assertEquals(1, rules.size(), need.toString());
            assertTrue(rules.getFirst().errorCondition().contains(new DslRenderer().render(Dsl.text(pattern).expression())), need.toString());
            assertKernelAccepts(result);
        });
        for (String literal : List.of("$", "$Missing$")) {
            var constant = RuleSynthesis.ValueConstraint.scalar(RuleSynthesis.ConstraintKind.CONST, literal);
            var choices = RuleSynthesis.ValueConstraint.ofEnum(List.of(literal, "safe"));
            var conditions = List.of(new RuleSynthesis.FieldValue(modeName, literal));
            for (RuleSynthesis.Need need : List.of(
                    new RuleSynthesis.ConstValue(codePath, literal),
                    new RuleSynthesis.ForbiddenConst(codePath, literal),
                    new RuleSynthesis.ForbiddenEnum(codePath, List.of(literal, "safe")),
                    new RuleSynthesis.DiscriminatedRequired(groupPath, modeName, literal, codeName, true),
                    new RuleSynthesis.DiscriminatedRequired(groupPath, modeName, literal, codeName, false),
                    new RuleSynthesis.MultiConditionalRequired(groupPath, List.of(conditions.getFirst(),
                            new RuleSynthesis.FieldValue(kindName, "safe")), codeName),
                    new RuleSynthesis.ThenConstrained(groupPath, conditions, codeName, constant),
                    new RuleSynthesis.ThenConstrained(groupPath, conditions, codeName, choices),
                    new RuleSynthesis.ElseConstrained(groupPath, conditions, codeName, constant),
                    new RuleSynthesis.ElseConstrained(groupPath, conditions, codeName, choices),
                    new RuleSynthesis.ContainsCount(tagsPath, List.of(new RuleSynthesis.Clause(valueName, constant)),
                            RuleSynthesis.ContainsSubject.SCALAR_VALUE, false, 0),
                    new RuleSynthesis.ContainsCount(tagsPath, List.of(new RuleSynthesis.Clause(valueName, choices)),
                            RuleSynthesis.ContainsSubject.SCALAR_VALUE, false, 0))) checks.add(() -> {
                var control = EditSession.read(structure);
                var r = control.group(groupPath);
                var field = r.str(codeName);
                r.addRule(b -> b.name("LiteralControl").code("LITERAL_CONTROL").errorField(field).when(field.filled().and(field.eq(literal)))
                        .message(Dsl.en("Check the specified value.")).build());
                assertKernelAccepts(control.toJson());
                String result = RuleSynthesis.apply(structure, List.of(need), TranscodeReport.builder(), null);
                var rules = ModelIndex.ofSelfContained(result).rules();
                assertEquals(1, rules.size(), need.toString());
                assertTrue(rules.getFirst().errorCondition().contains(new DslRenderer().render(Dsl.text(literal).expression())), need.toString());
                assertKernelAccepts(result);
            });
        }
        assertAll(checks);
    }

}
