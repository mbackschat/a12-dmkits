package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Holds the whole-surface reads an agent makes, the {@code manifest} index, the {@code manifest --full} document
 * (every verb's help) and the agent skill, to their budgets and to the current contract. Each is one budget, because
 * an agent reads each whole.
 *
 * <p>The skill is the N6 material under test, and condensing it requires re-verifying every claim against the binary,
 * so this guard bounds its growth without asking for a rewrite.
 */
class ManifestAndSkillProseGuardTest {

    private static final Path SKILL = Path.of("../skill/SKILL.md");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theManifestAndTheSkillStayWithinTheirBudgets() throws IOException {
        Map<String, Integer> actual = Map.of("manifest", measuredManifest().length(), "manifest --full",
                measuredManifest("--full").length(), "skill/SKILL.md", Files.readString(SKILL).length());
        List<String> defects = AgentProse.budgetDefects(actual, AgentProse.budget("surfaces"));
        assertTrue(defects.isEmpty(), () -> AgentProse.budgetFailure("surfaces", defects));
    }

    @Test
    void theManifestAndTheSkillCarryNoHistory() throws IOException {
        List<String> hits = new ArrayList<>();
        collect(MAPPER.readTree(manifest("--full")), hits);
        hits.addAll(AgentProse.proseDefects("skill/SKILL.md", Files.readString(SKILL)).stream()
                .filter(defect -> defect.contains(": history ")).toList());
        assertTrue(hits.isEmpty(), () -> AgentProse.proseFailure(hits));
    }

    private static String manifest(String... args) {
        CliTestSupport.Result result = CliTestSupport.run(Stream.concat(Stream.of("manifest"), Stream.of(args))
                .toArray(String[]::new));
        assertEquals(0, result.code(), result::err);
        return result.out();
    }

    /**
     * The manifest as every machine serves it: without the build-identity {@code version} block, and with the
     * {@code mcp} address defaults that picocli interpolates from {@code IP}, {@code HOST} and {@code PORT} put back
     * to their fallbacks, so the budget does not move with the checkout's state or the shell's environment.
     */
    static String measuredManifest(String... args) throws IOException {
        ObjectNode tree = (ObjectNode) MAPPER.readTree(manifest(args));
        tree.remove("version");
        String host = firstSet(System.getenv("IP"), System.getenv("HOST"), "127.0.0.1");
        String port = firstSet(System.getenv("PORT"), "8080");
        return MAPPER.writeValueAsString(tree).replace("variable when one is set, else " + host + ".",
                "variable when one is set, else 127.0.0.1.").replace("variable when one is set, else " + port + ";",
                "variable when one is set, else 8080;");
    }

    private static String firstSet(String... values) {
        return Stream.of(values).filter(value -> value != null && !value.isEmpty()).findFirst().orElseThrow();
    }

    private static void collect(JsonNode node, List<String> hits) {
        if (node.isTextual()) {
            hits.addAll(AgentProse.proseDefects("manifest", node.asText()));
        }
        node.forEach(child -> collect(child, hits));
    }
}
