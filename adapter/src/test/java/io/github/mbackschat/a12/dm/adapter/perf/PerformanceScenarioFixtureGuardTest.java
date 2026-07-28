package io.github.mbackschat.a12.dm.adapter.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

import io.github.mbackschat.a12.dm.interpreter.eval.DocumentJsonKt;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentKt;
import io.github.mbackschat.a12.dm.interpreter.gen.DocumentSinkKt;

/**
 * Freshness guard for the shared JVM/Node/native performance fixtures. The models come directly from
 * {@link PerfCatalog}, whose rules are authored through modelkit/EditSession/rulekit.
 */
class PerformanceScenarioFixtureGuardTest {

    /**
     * Comparison and re-indentation only. Configured so re-indenting can never re-normalize a number: a DEFAULT
     * mapper would rewrite a canonical {@code 250.00} as {@code 250.0} and a beyond-double decimal as scientific
     * notation, quietly making the committed fixture something the canonical writer never emits.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    private static final String FIXTURE_DIR_PROPERTY = "performance.fixture.dir";

    @Test
    void committedFixturesMatchTheTypedPerformanceCatalog() throws IOException {
        var b1 = PerfCatalog.INSTANCE.b1();
        var b2 = PerfCatalog.INSTANCE.b2();
        var rules48 = PerfCatalog.INSTANCE.manyRules(48, false);
        var rules192 = PerfCatalog.INSTANCE.manyRules(192, false);
        var expected = new LinkedHashMap<String, String>();
        expected.put("b1.dm.json", b1.getKernelModel());
        expected.put("b2.dm.json", b2.getKernelModel());
        expected.put("many-rules-48.dm.json", rules48.getKernelModel());
        expected.put("many-rules-192.dm.json", rules192.getKernelModel());
        expected.put("many-rules-48.instance.json", instanceJson(rules48, 100));
        var directory = Path.of(System.getProperty(FIXTURE_DIR_PROPERTY));

        if (Boolean.getBoolean("performance.fixtures.fix")) {
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
                    () -> path + " drifted from its typed performance-catalog author");
        }
    }

    /**
     * The fixture is A12's canonical Document JSON, written by the ONE writer that shape has —
     * {@code Document.toDocumentJson}. This guard used to carry its own near-identical sink; a second writer for
     * a shape A12 defines is exactly what CLAUDE.md "NEVER invent a shape A12 already defines" forbids, so the
     * generator now fills an interpreter {@code Document} and the interpreter's codec writes it.
     */
    private static String instanceJson(PerfScenario scenario, int scale) throws JsonProcessingException {
        var document = DocumentKt.newDocument(scenario.getInterpModel());
        scenario.getInstance().invoke(DocumentSinkKt.asSink(document), scale, 0);
        var canonical = DocumentJsonKt.toDocumentJson(document, scenario.getInterpModel());
        // Re-indented only so the committed fixture stays reviewable; the canonical writer emits compact JSON
        // because A12 takes the pretty-printer as caller configuration, not as part of the format.
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(canonical))
                + System.lineSeparator();
    }
}
