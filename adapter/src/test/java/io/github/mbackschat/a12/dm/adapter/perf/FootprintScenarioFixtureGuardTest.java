package io.github.mbackschat.a12.dm.adapter.perf;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.moreThanOneFieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Freshness guard for the canonical JVM/Node footprint models. The committed serialization is generated
 * from modelkit + EditSession + rulekit, so the multiplatform measurement consumes the same typed-authored
 * model without adding a JVM-only kit dependency to the standalone interpreter.
 */
class FootprintScenarioFixtureGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE_DIR_PROPERTY = "footprint.fixture.dir";

    @Test
    void committedModelsMatchTheTypedAuthoringSurface() throws IOException {
        var expected = Map.of(
                "simple-validation.dm.json", simpleValidation(),
                "equality-correlation.dm.json", equalityCorrelation(),
                "compute-overlay.dm.json", computeOverlay());
        var directory = Path.of(System.getProperty(FIXTURE_DIR_PROPERTY));

        if (Boolean.getBoolean("footprint.fixtures.fix")) {
            Files.createDirectories(directory);
            for (var fixture : expected.entrySet()) {
                Files.writeString(directory.resolve(fixture.getKey()), fixture.getValue());
            }
            return;
        }

        for (var fixture : expected.entrySet()) {
            var path = directory.resolve(fixture.getKey());
            assertEquals(
                    JSON.readTree(fixture.getValue()),
                    JSON.readTree(Files.readString(path)),
                    () -> path + " drifted from its typed modelkit/EditSession/rulekit author");
        }
    }

    private static String simpleValidation() {
        var structure = DocumentModelDraft.named("footprint-simple").locale("en_US")
                .rootGroup("FootprintSimple", root -> root
                        .group("Row", row -> row.repeatable(1_000_000)
                                .field("Value", FieldKind.STRING)))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var row = session.group("/FootprintSimple").group("Row");
        var editor = session.group(row.path().value());
        var value = editor.str("Value");
        editor.addRule(rule -> rule.name("PresentValue")
                .errorField(value)
                .when(value.filled())
                .code("SIMPLE_REQUIRED")
                .severity(Severity.ERROR)
                .message(en("The footprint row carries a value."))
                .build());
        return session.validate().toJson();
    }

    private static String equalityCorrelation() {
        var structure = DocumentModelDraft.named("footprint-correlation").locale("en_US")
                .rootGroup("FootprintCorrelation", root -> root
                        .group("Row", row -> row.repeatable(1_000_000)
                                .field("Key", FieldKind.STRING)))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var root = session.group("/FootprintCorrelation");
        var editor = session.group(root.group("Row").path().value());
        var key = editor.str("Key");
        var matchingKeys = root.flatten("Row*/Key").having(key.eq(key.outer()));
        editor.addRule(rule -> rule.name("EqualPair")
                .errorField(key)
                .when(key.filled().and(moreThanOneFieldFilled(matchingKeys)))
                .code("EQUAL_PAIR")
                .severity(Severity.ERROR)
                .message(en("The footprint row shares its key."))
                .build());
        return session.validate().toJson();
    }

    private static String computeOverlay() {
        var structure = DocumentModelDraft.named("footprint-compute").locale("en_US")
                .rootGroup("FootprintCompute", root -> root
                        .group("Row", row -> row.repeatable(1_000_000)
                                .indexField("Key")
                                .field("Key", FieldKind.STRING)
                                .field("Source", FieldKind.NUMBER)
                                .field("Intermediate", FieldKind.NUMBER)
                                .field("Correlated", FieldKind.NUMBER)
                                .field("Indexed", FieldKind.NUMBER)))
                .build()
                .toJson();
        var session = EditSession.read(structure);
        var root = session.group("/FootprintCompute");
        var editor = session.group(root.group("Row").path().value());
        var source = editor.num("Source");
        var intermediate = editor.num("Intermediate");
        var correlated = editor.num("Correlated");
        var indexed = editor.num("Indexed");
        var matchingIntermediate = root.flatten("Row*/Intermediate")
                .having(intermediate.eq(intermediate.outer()));

        editor.addComputation(intermediate, computation -> computation.name("BuildIntermediate")
                .calc(source.asNumber().plus(100).expression())
                .message(en("Build the intermediate footprint value."))
                .build());
        editor.addComputation(correlated, computation -> computation.name("CopyCorrelated")
                .when(moreThanOneFieldFilled(matchingIntermediate))
                .calc(intermediate.value())
                .message(en("Copy a correlated intermediate value."))
                .build());
        editor.addComputation(indexed, computation -> computation.name("ReadIndexed")
                .calc(source.forIndex("kb").expression())
                .message(en("Read the indexed footprint source."))
                .build());
        return session.validate().toJson();
    }
}
