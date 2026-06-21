package io.github.mbackschat.a12.dm.cli.config;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code config} group (CLI-SPEC §5) — model-level document configuration: {@code read}
 * (the whole {@code modelConfig} into {@code data}) and {@code modify} (set decimal-separator / time-zone /
 * condition-language; at least one is required). No add/remove — config is a fixed object, not a collection.
 */
class ConfigVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void readReturnsTheConfig(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode data = j.path("data");
        assertTrue(data.has("decimalSeparator"));
        assertTrue(data.has("timeZone"));
        assertTrue(data.has("conditionLanguage"));
    }

    /**
     * The declared message locales drive the per-locale-message law
     * ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}): a rule/computation needs one message per declared
     * locale. {@code conditionLanguage} alone doesn't reveal the set, so {@code config read} surfaces the
     * full {@code header.locales} — an agent provisions messages up front instead of failing first.
     */
    @Test
    void readSurfacesDeclaredLocales(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        java.nio.file.Files.writeString(model, CliTestSupport.subscriptionJson());
        var res = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode locales = M.readTree(res.out()).path("data").path("locales");
        assertTrue(locales.isArray(), () -> "config read declares `locales` as an array: " + res.out());
        assertEquals(2, locales.size(), "the subscription fixture declares two locales");
        assertEquals("en_US", locales.get(0).asText());
        assertEquals("de_DE", locales.get(1).asText());
    }

    @Test
    void modifySetsModelLabelsAndAnnotation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // en_US-only fixture
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify",
                "--label", "en_US=Customer Order", "--annotation", "owner=orders-team");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("Customer Order", j.path("changed").path("label.en_US").asText());
        String written = java.nio.file.Files.readString(model);
        assertTrue(written.contains("Customer Order"), "the model label is written to the header");
        assertTrue(written.contains("orders-team"), "the model annotation is written to the header");
    }

    // G26 — the model's access-control roles (a `roles` header annotation, comma-separated, the lint binds to
    // roles.yaml). `--role` MERGES (so adding one keeps the rest — the G17 rule); `config read` echoes them.

    @Test
    void modifyMergesRolesAndConfigReadEchoesThem(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify",
                "--role", "admin", "--role", "editor").code());
        var second = CliTestSupport.run("-m", model.toString(), "config", "modify", "--role", "viewer");
        assertEquals(0, second.code(), second.err());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals("admin,editor,viewer",
                M.readTree(read.out()).path("data").path("annotations").path("roles").asText(),
                () -> "roles merged, not clobbered: " + read.out());
    }

    @Test
    void clearRolesRemovesTheAnnotation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--role", "admin").code());
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--clear-roles");
        assertEquals(0, res.code(), res.err());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertTrue(M.readTree(read.out()).path("data").path("annotations").path("roles").isMissingNode(),
                () -> "the roles annotation is gone: " + read.out());
    }

    @Test
    void modifyChangesTheDecimalSeparator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--decimal-separator", ",");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals(",", j.path("changed").path("decimalSeparator").asText());
        // re-read confirms the write took
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(",", M.readTree(read.out()).path("data").path("decimalSeparator").asText());
    }

    @Test
    void modifySetsTheSupportedCharacterSet(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify",
                "--supported-character", "A-Z", "--supported-character", "0-9");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("A-Z 0-9", j.path("changed").path("supportedCharacters").asText());
        // config read surfaces the set back as an array, in order
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        JsonNode chars = M.readTree(read.out()).path("data").path("supportedCharacters");
        assertTrue(chars.isArray() && chars.size() == 2, () -> "read surfaces the set: " + read.out());
        assertEquals("A-Z", chars.get(0).asText());
        assertEquals("0-9", chars.get(1).asText());
    }

    @Test
    void modifySetsTheModelComment(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--comment", "an order model");
        assertEquals(0, res.code(), res.err());
        assertEquals("an order model", M.readTree(res.out()).path("changed").path("comment").asText());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals("an order model", M.readTree(read.out()).path("data").path("comment").asText());
    }

    @Test
    void modifyRequiresAtLeastOneSetting(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify");
        assertEquals(2, res.code());
    }

    // --- G16: register a locale on an existing model (config modify --add-locale) ---

    /** A label-light single-locale model (plain string field — no enum value labels to force per-locale coverage). */
    private static Path enOnlyModel(Path dir) throws Exception {
        Path m = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add",
                "--group", "/Doc", "--name", "Note", "--kind", "STRING").code());
        return m;
    }

    @Test
    void modifyRegistersANewLocale(@TempDir Path dir) throws Exception {
        Path model = enOnlyModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "de_DE");
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("changed").path("addedLocales").toString().contains("de_DE"),
                () -> "reports the added locale: " + res.out());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertTrue(M.readTree(read.out()).path("data").path("locales").toString().contains("de_DE"),
                () -> "the locale is now in the registry: " + read.out());
    }

    @Test
    void addLocaleIsIdempotent(@TempDir Path dir) throws Exception {
        Path model = enOnlyModel(dir);
        // en_US is already declared — re-adding must not duplicate it
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "en_US");
        assertEquals(0, res.code(), res.err());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        JsonNode locales = M.readTree(read.out()).path("data").path("locales");
        long n = 0;
        for (JsonNode l : locales) {
            if ("en_US".equals(l.asText())) {
                n++;
            }
        }
        assertEquals(1, n, () -> "en_US is not duplicated: " + read.out());
    }

    @Test
    void addingALocaleRulesDoNotCoverIsRejected(@TempDir Path dir) throws Exception {
        // orderRuled is en_US-only and has rules; adding de_DE leaves those rules without a de_DE message,
        // so the kernel gate rejects it (a structured envelope, not a silent half-edit)
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "de_DE");
        assertEquals(1, res.code(), () -> "the kernel rejects the uncovered locale: " + res.out());
        assertEquals("rejected", M.readTree(res.out()).path("outcome").asText());
    }
}
