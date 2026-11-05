package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The undeclared {@code --message} locale pre-check reads the model's header before the model loader does, so an
 * unreadable model must keep the loader's input identity there too ({@code RK_MODEL_INPUT_INVALID}, exit 2) rather
 * than surface as an internal error. Every route that runs the pre-check is a row; the {@code patterns} row
 * without {@code --message} is {@code LocalFailureBoundaryTest.aScaffoldLoadFailureKeepsItsInputIdentity}.
 */
class MessageLocalePrecheckInputIdentityTest {

    @Test
    void anUnreadableModelIsTheInputsRefusalOnEveryPrecheckRoute(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("broken.json");
        Files.writeString(model, "not JSON");
        List<String> ruleCheck = List.of("-m", model.toString(), "rule", "check", "--field", "/Order/End",
                "--condition", "FieldNotFilled(/Order/End)", "--code", "END", "--message", "en_US=Enter an end.");
        List<List<String>> routes = List.of(
                ruleCheck,
                concat(ruleCheck, "--engine", "portable"),
                List.of("-m", model.toString(), "patterns", "date-order", "--arg", "earlier=/Order/Start",
                        "--arg", "later=/Order/End", "--message", "en_US=End after start."));
        Map<String, String> observed = new LinkedHashMap<>();
        for (List<String> route : routes) {
            var result = CliTestSupport.run(route.toArray(String[]::new));
            String code = new ObjectMapper().readTree(result.out()).path("diagnostics").path(0).path("code").asText();
            observed.put(String.join(" ", route.subList(2, route.size())), code + " exit " + result.code());
        }
        Map<String, String> expected = new LinkedHashMap<>();
        observed.keySet().forEach(route -> expected.put(route, "RK_MODEL_INPUT_INVALID exit 2"));
        assertEquals(expected, observed);
    }

    private static List<String> concat(List<String> head, String... tail) {
        return java.util.stream.Stream.concat(head.stream(), java.util.Arrays.stream(tail)).toList();
    }
}
