package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The {@code export} verb tested at the {@link Cli#run} seam (CLI-SPEC §3 — the fast inner loop: no
 * packaging, no spawned process). {@code Cli.run} is pure (returns an exit code, writes to passed
 * sinks), so each verb is a sub-second unit test. The agent-files-as-API line shapes (finding 13)
 * are pinned here.
 */
class ExportVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A subscription model with one rule added, written to a temp file the CLI reads. */
    private static Path modelWithRule(Path dir) throws Exception {
        BoundModel base = BoundModel.ofJson(fixture());
        Rule rule = base.rule(base.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(base.num("/Subscription/Addons/MonthlyFee"))
                .when(base.num("/Subscription/Addons/MonthlyFee").gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(Dsl.en("Too high."), Dsl.de("Zu hoch.")).build();
        Path file = dir.resolve("subscription.dm.json");
        Files.writeString(file, base.add(rule).toJson());
        return file;
    }

    private static String export(Path model, String artifact) {
        StringBuilder out = new StringBuilder();
        int code = Cli.run(new String[]{"-m", model.toString(), "export", artifact}, out, new StringBuilder());
        assertEquals(0, code, "export should succeed");
        return out.toString();
    }

    @Test
    void fieldsAreJsonlOneObjectPerLine(@TempDir Path dir) throws Exception {
        List<String> lines = export(modelWithRule(dir), "fields").lines().toList();
        for (String line : lines) {
            JsonNode n = MAPPER.readTree(line); // every line independently parseable
            assertTrue(n.hasNonNull("path") && n.hasNonNull("kind"), () -> "field line: " + line);
        }
        String fee = lines.stream().filter(l -> l.contains("MonthlyFee")).findFirst().orElseThrow();
        JsonNode n = MAPPER.readTree(fee);
        assertEquals("NUMBER", n.get("kind").asText());
        assertEquals("/Subscription/Addons", n.get("group").asText());
        assertEquals("/Subscription/Addons", n.get("repeatsUnder").asText(),
                "the repeatable ancestor is stamped so iteration scope is greppable");
    }

    @Test
    void enumFieldCarriesItsDomain(@TempDir Path dir) throws Exception {
        String tier = export(modelWithRule(dir), "fields").lines()
                .filter(l -> l.contains("/Subscription/Tier")).findFirst().orElseThrow();
        JsonNode values = MAPPER.readTree(tier).get("values");
        assertTrue(values.isArray() && values.size() > 0, () -> "enum domain listed: " + tier);
    }

    @Test
    void groupsCarryRepeatability(@TempDir Path dir) throws Exception {
        String addons = export(modelWithRule(dir), "groups").lines()
                .filter(l -> l.contains("\"/Subscription/Addons\"")).findFirst().orElseThrow();
        assertTrue(MAPPER.readTree(addons).get("repeatable").asBoolean(), () -> "Addons repeats: " + addons);
    }

    @Test
    void rulesAreJsonlWithConditionAndHonestResolution(@TempDir Path dir) throws Exception {
        List<String> lines = export(modelWithRule(dir), "rules").lines().toList();
        assertEquals(1, lines.size(), "the one added rule is listed");
        JsonNode n = MAPPER.readTree(lines.getFirst());
        assertEquals("/Subscription/Addons/FeeCapRule", n.get("rule").asText());
        assertEquals("errorCondition", n.get("slot").asText());
        assertEquals("FEE_CAP", n.get("code").asText());
        assertTrue(n.get("condition").asText().contains("1000"), "raw condition text is greppable");
        assertTrue(n.get("referencedFields").isNull(),
                "pre-adapter the core cannot resolve refs from raw text — null, not guessed");
        assertEquals("heuristic", n.get("resolution").asText());
    }

    @Test
    void cardIsMarkdownStampedWithIdAndCounts(@TempDir Path dir) throws Exception {
        String card = export(modelWithRule(dir), "card");
        assertTrue(card.startsWith("#"), "model-card is markdown");
        assertTrue(card.contains("Subscription"), "stamped with the model id");
        assertTrue(card.contains("/Subscription/Addons"), "group skeleton present");
        assertTrue(card.contains("repeatable"), "repeatability shown");
    }

    @Test
    void exportIsDrivenByTheUniversalModelOption(@TempDir Path dir) throws Exception {
        // probe 01-de: export was the only verb that ignored -m and required a positional <model.json>, so an
        // agent that learned -m everywhere couldn't drive it. It now uses the universal -m like every verb —
        // and with no artifact arg defaults to the model card.
        StringBuilder out = new StringBuilder();
        int code = Cli.run(new String[]{"-m", modelWithRule(dir).toString(), "export"}, out, new StringBuilder());
        assertEquals(0, code, "`-m <model> export` succeeds (the call that used to fail)");
        assertTrue(out.toString().startsWith("#"), "the default artifact is the model card");
    }

    @Test
    void exportWithoutAModelGivesTheStandardCorrectiveNotAStackTrace(@TempDir Path dir) {
        StringBuilder err = new StringBuilder();
        int code = Cli.run(new String[]{"export", "card"}, new StringBuilder(), err);
        assertEquals(2, code, "no -m → a clean failure (fail() exit code)");
        assertTrue(err.toString().contains("-m"), () -> "names the universal model option: " + err);
    }

    @Test
    void outDirWritesAllFourArtifacts(@TempDir Path dir) throws Exception {
        Path model = modelWithRule(dir);
        Path ctx = dir.resolve("ctx");
        StringBuilder err = new StringBuilder();
        int code = Cli.run(new String[]{"-m", model.toString(), "export", "--out-dir", ctx.toString()},
                new StringBuilder(), err);
        assertEquals(0, code, () -> "export --out-dir should succeed: " + err);
        for (String name : List.of("fields.jsonl", "groups.jsonl", "rules.jsonl", "model-card.md")) {
            Path f = ctx.resolve(name);
            assertTrue(Files.exists(f), () -> "wrote " + name);
            assertFalse(Files.readString(f).isBlank(), () -> name + " non-empty");
        }
    }

    @Test
    void unknownVerbFailsWithNonZeroExit() {
        StringBuilder err = new StringBuilder();
        int code = Cli.run(new String[]{"frobnicate"}, new StringBuilder(), err);
        assertNotEquals(0, code, "unknown verb is an error");
        assertFalse(err.toString().isBlank(), "and says why on stderr");
    }

    private static String fixture() {
        try (InputStream in = ExportVerbTest.class.getClassLoader().getResourceAsStream("models/subscription.dm.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
