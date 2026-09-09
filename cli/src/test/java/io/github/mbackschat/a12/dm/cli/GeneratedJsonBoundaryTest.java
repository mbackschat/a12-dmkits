package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.io.InputDecorator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

@Isolated("restores the generated-JSON factory's input decorator after command fault injection")
class GeneratedJsonBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {"{\"references\":", "", " ", "{} {}"})
    void malformedGeneratedJsonIsAnInternalFailureWithItsParserCause(String json) {
        var failure = assertThrows(IllegalStateException.class,
                () -> BaseCommand.readGeneratedJson(json));
        assertInstanceOf(JsonProcessingException.class, failure.getCause());
        var envelope = BaseCommand.internalErrorEnvelope(CliTestSupport.identity("rule", "deps"), failure);
        assertEquals(70, envelope.exitCode());
        assertEquals("RK_INTERNAL_ERROR", envelope.toJson().path("diagnostics").path(0).path("code").asText());
        assertFalse(envelope.toJson().has("verification"));
    }

    @Test
    void validGeneratedJsonPreservesItsPayload() {
        var parsed = BaseCommand.readGeneratedJson("{\"message\":\"ready\",\"count\":2}");
        assertEquals("ready", parsed.path("message").asText());
        assertEquals(2, parsed.path("count").intValue());
    }

    enum Route { COMPUTATION_EXPLAIN, RULE_DEPS, COMPUTATION_MODIFY, RULE_MODIFY }

    @ParameterizedTest
    @EnumSource(Route.class)
    void commandsKeepDecoderFailuresInternalAndWriteNothing(Route route, @TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        String before = Files.readString(model);
        var adapter = KernelAdapter.ofJson(before);
        String rule = adapter.ruleFullNames().iterator().next();
        String computation = adapter.computationFullNames().iterator().next();
        var converted = assertInstanceOf(ReadComputation.Converted.class, adapter.readComputation(computation))
                .computation();
        var json = new ObjectMapper();
        var spec = json.createObjectNode();
        spec.put("computedField", converted.computedField().path().value());
        spec.put("name", converted.name());
        spec.putArray("alternatives").addObject()
                .put("operation", converted.draft().alternatives().getFirst().operation());
        var messages = spec.putArray("messages");
        converted.messages().forEach(message -> messages.addObject()
                .put("locale", message.locale()).put("text", message.text()));
        Path specPath = dir.resolve("computation.json");
        Files.writeString(specPath, spec.toString());
        String[] args = switch (route) {
            case COMPUTATION_EXPLAIN -> new String[] {"-m", model.toString(), "computation", "explain", computation};
            case RULE_DEPS -> new String[] {"-m", model.toString(), "rule", "deps", rule};
            case COMPUTATION_MODIFY -> new String[] {"-m", model.toString(), "computation", "modify", computation,
                    "--spec", specPath.toString()};
            case RULE_MODIFY -> new String[] {"-m", model.toString(), "rule", "modify", rule,
                    "--condition", adapter.canonicalConditionText(rule)};
        };

        // Jackson's decorator corrupts the decoder input while leaving the real command catches under test.
        // Isolated + finally keep this factory mutation from changing another test's input (CLI-CONFORMANCE §1).
        var field = BaseCommand.class.getDeclaredField("JSON");
        field.setAccessible(true);
        var factory = ((ObjectMapper) field.get(null)).getFactory();
        var original = factory.getInputDecorator();
        var calls = new AtomicInteger();
        CliTestSupport.Result failure;
        try {
            factory.setInputDecorator(new InputDecorator() {
                @Override
                public Reader decorate(IOContext context, Reader input) {
                    calls.incrementAndGet();
                    return new StringReader("{\"broken\":");
                }

                @Override
                public InputStream decorate(IOContext context, InputStream input) {
                    return null;
                }

                @Override
                public InputStream decorate(IOContext context, byte[] input, int offset, int length) {
                    return null;
                }
            });
            failure = CliTestSupport.run(args);
        } finally {
            factory.setInputDecorator(original);
        }
        assertEquals(1, calls.get(), "the command must reach its generated-output decoder");
        assertEquals(70, failure.code(), failure::out);
        var envelope = json.readTree(failure.out());
        assertEquals("RK_INTERNAL_ERROR", envelope.path("diagnostics").path(0).path("code").asText());
        assertTrue(failure.err().contains("JsonEOFException"), failure::err);
        assertFalse(envelope.path("written").asBoolean());
        assertFalse(envelope.has("verification"));
        assertEquals(before, Files.readString(model));
        var control = CliTestSupport.run(args);
        assertEquals(0, control.code(), control::err);
    }
}
