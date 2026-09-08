package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles;
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import picocli.CommandLine;

class LocalFailureBoundaryTest {

    private static final class Reader extends BaseCommand {
        private final RuntimeException failure;

        Reader(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Integer call() {
            throw withFailureContext("reader failed: ", failure);
        }
    }

    @Test
    void unexpectedReaderFailuresEscapeWithTheirCause() {
        for (RuntimeException defect : new RuntimeException[] {
                new NullPointerException("reader defect"), new IllegalArgumentException("reader defect")}) {
            var reader = new Reader(defect);
            new CommandLine(reader).setErr(new PrintWriter(new StringWriter()));
            var escaped = assertThrows(RuntimeException.class, reader::call);
            assertSame(defect, escaped.getCause());
            assertEquals(70, BaseCommand.internalErrorEnvelope(CliTestSupport.identity("model", "read"), escaped)
                    .exitCode());
        }
    }

    @Test
    void typedInputFailuresKeepTheirIdentity() {
        var preparation = assertThrows(ModelPreparationError.class,
                () -> ModelWorkspace.fromSources(List.of()).prepare("missing"));
        for (RuntimeException refusal : new RuntimeException[] {
                new ModelInputException("bad input"), new UnresolvedReferenceException("library", "missing"),
                new ModelConsistencyException(List.of(new Notification(Severity.ERROR, "kernel refusal"))),
                preparation}) {
            assertSame(refusal, assertThrows(RuntimeException.class, new Reader(refusal)::call));
        }
    }

    @Test
    void aScaffoldLoadFailureKeepsItsInputIdentity(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("broken.json");
        Files.writeString(model, "not JSON");
        var result = CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/Start", "--arg", "later=/Order/End");
        var envelope = new ObjectMapper().readTree(result.out());
        assertEquals("RK_MODEL_INPUT_INVALID", envelope.path("diagnostics").path(0).path("code").asText());
        assertEquals(2, result.code(), result::out);
    }

    @Test
    void aReaderDefectReachesTheRealCliMapper(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        for (RuntimeException defect : new RuntimeException[] {
                new NullPointerException("reader sentinel"), new IllegalArgumentException("reader sentinel")}) {
            var output = new FailOnceOutput(defect);
            var error = new StringBuilder();
            int exit = Cli.run(new String[] {"-m", model.toString(), "model", "read"}, output, error);
            assertEquals(70, exit, error::toString);
            var envelope = new ObjectMapper().readTree(output.content.toString());
            assertEquals("error", envelope.path("outcome").asText());
            assertEquals("RK_INTERNAL_ERROR", envelope.path("diagnostics").path(0).path("code").asText());
            assertFalse(envelope.has("valid"));
            assertFalse(envelope.has("verification"));
            assertTrue(error.toString().contains(defect.toString()), error::toString);
        }
    }

    private static final class FailOnceOutput implements Appendable {
        private final StringBuilder content = new StringBuilder();
        private RuntimeException failure;

        FailOnceOutput(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Appendable append(CharSequence text) {
            if (failure != null) {
                var thrown = failure;
                failure = null;
                throw thrown;
            }
            content.append(text);
            return this;
        }

        @Override
        public Appendable append(CharSequence text, int start, int end) {
            return append(text.subSequence(start, end));
        }

        @Override
        public Appendable append(char value) {
            return append(String.valueOf(value));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", " ", "[]", "{\"structure\":{\"count\":-1}}"})
    void invalidProfileInputRemainsAnInputFailure(String json, @TempDir Path dir) throws Exception {
        Path profile = dir.resolve("invalid.profile.json");
        Files.writeString(profile, json);
        var result = CliTestSupport.run("profile", "compare", "--target", profile.toString(),
                "--actual", profile.toString());
        assertEquals(2, result.code(), result::out);
    }

    @Test
    void anUnboundedProfileRemainsAnInputFailure(@TempDir Path dir) throws Exception {
        String model = Files.readString(CliTestSupport.orderRuled(dir));
        ObjectNode profile = (ObjectNode) new ObjectMapper().readTree(ModelProfiles.extract(model));
        ((ObjectNode) profile.at("/structure/repeatability")).put("unboundedCount", 1);
        Path input = dir.resolve("unbounded.profile.json");
        Files.writeString(input, profile.toString());
        var result = CliTestSupport.run("profile", "synthesize", "--input", input.toString());
        assertEquals(2, result.code(), result::out);
        assertTrue(result.err().contains("requires bounded repeatable groups"), result::err);
    }
}
