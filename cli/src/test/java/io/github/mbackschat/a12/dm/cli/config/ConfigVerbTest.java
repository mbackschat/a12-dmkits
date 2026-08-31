package io.github.mbackschat.a12.dm.cli.config;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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

    // The model's base year (modelInfo.baseYear) — resolves year-less dates + drives the BaseYear constant.
    // Settable only via the modelkit library before; `config modify --base-year` closes that CLI gap, and
    // `config read` echoes it so an agent can discover the current value.
    @Test
    void modifySetsBaseYearAndConfigReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--base-year", "2024");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals(2024, j.path("changed").path("baseYear").asInt());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(2024, M.readTree(read.out()).path("data").path("baseYear").asInt(),
                () -> "config read echoes the base year: " + read.out());
    }

    // G32 — the fieldRefByShortNameAllowed config flag: settable in modelkit, read-only in the CLI before.
    @Test
    void modifySetsFieldRefByShortNameAndReadEchoesIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--field-ref-by-short-name", "false");
        assertEquals(0, res.code(), res.err());
        assertEquals(false, M.readTree(res.out()).path("changed").path("fieldRefByShortNameAllowed").asBoolean(true));
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(false, M.readTree(read.out()).path("data").path("fieldRefByShortNameAllowed").asBoolean(true),
                () -> "config read echoes the flag: " + read.out());
    }

    // G34 — --clear-comment (the inverse of --comment).
    @Test
    void clearCommentRemovesIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--comment", "note").code());
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--clear-comment");
        assertEquals(0, res.code(), res.err());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertTrue(M.readTree(read.out()).path("data").path("comment").isMissingNode(), () -> "comment gone: " + read.out());
    }

    /**
     * The inverse of {@code --base-year}, which had no inverse anywhere in the stack: {@code setBaseYear}
     * took a primitive {@code int} while {@code baseYear()} returned an {@code Optional}, so absence was
     * readable and unreachable. A consumer needing the no-base-year negative control on an already-set model
     * had to hand-edit the JSON. Raised by an a12-kernel-lean evidence route, 2026-08-31.
     */
    @Test
    void clearBaseYearRemovesIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--base-year", "2024").code());
        var set = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals(2024, M.readTree(set.out()).path("data").path("baseYear").asInt(-1),
                () -> "precondition — the base year is set: " + set.out());

        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--clear-base-year");
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("changed").path("baseYear").isNull(),
                () -> "the change record reports the removal: " + res.out());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertTrue(M.readTree(read.out()).path("data").path("baseYear").isMissingNode(),
                () -> "base year gone: " + read.out());
    }

    // G35 — --remove-annotation <name> (the general form of --clear-roles).
    @Test
    void removeAnnotationUnsetsIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--annotation", "owner=x").code());
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--remove-annotation", "owner");
        assertEquals(0, res.code(), res.err());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertTrue(M.readTree(read.out()).path("data").path("annotations").path("owner").isMissingNode(),
                () -> "annotation gone: " + read.out());
    }

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
    void addingALocaleRejectsAnEnumWithSingleLocaleLabelsAndNamesTheCause(@TempDir Path dir) throws Exception {
        // probe 02: registering a 2nd locale on a model whose enum value-labels only cover the 1st fails the
        // terminal kernel gate with the OPAQUE MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES ("the display value and
        // the XML value were not specified together"). Verify the real kernel emits it AND the enricher now
        // names the per-locale enum-label cause + the add-locale trigger (was fix-less — probe Finding 3b).
        Path model = dir.resolve("m.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "m", "--locale", "en_US",
                "--root", "M", "-o", model.toString()).code());
        Path spec = dir.resolve("tier.json");
        Files.writeString(spec, """
                { "group":"/M", "name":"Tier", "kind":"ENUM",
                  "enum": { "values": [ {"value":"GOLD","labels":{"en_US":"Gold"}},
                                        {"value":"SILVER","labels":{"en_US":"Silver"}} ] } }""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code(),
                "the enum is valid in the single-locale model");
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "de_DE");
        assertTrue(res.code() != 0, () -> "adding de_DE without de_DE enum labels is rejected: " + res.out());
        boolean found = false;
        for (JsonNode d : M.readTree(res.out()).path("diagnostics")) {
            if ("MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES".equals(d.path("code").asText())) {
                found = true;
                assertTrue(d.path("fix").asText().contains("labels") && d.path("fix").asText().contains("locale"),
                        () -> "the enriched fix names the per-locale enum-label gap: " + d);
            }
        }
        assertTrue(found, () -> "the rejection carries MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES: " + res.out());
    }

    @Test
    void removeLocaleIsTheInverseOfAddLocale(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", model.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "de_DE").code());
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--remove-locale", "de_DE");
        assertEquals(0, res.code(), res::err);
        JsonNode locales = M.readTree(CliTestSupport.run("-m", model.toString(), "config", "read").out())
                .path("data").path("locales");
        assertEquals(1, locales.size(), () -> "de_DE removed: " + locales);
        assertEquals("en_US", locales.path(0).asText());
    }

    @Test
    void removingTheLastLocaleIsRefused(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", model.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--remove-locale", "en_US");
        assertEquals(2, res.code(), () -> "removing the last locale is a spec error: " + res.out() + res.err());
        assertTrue(res.err().toLowerCase().contains("last locale"),
                () -> "names the last-locale rule: " + res.err());
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
    void configReadEchoesTheModelLabel(@TempDir Path dir) throws Exception {
        // the model-level label round-trips through config read (a per-locale label must cover every declared
        // model locale, a kernel constraint — the en-only fixture keeps it a single locale)
        Path model = enOnlyModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--label", "en_US=Orders");
        assertEquals(0, res.code(), res.err());
        assertEquals("Orders", M.readTree(res.out()).path("changed").path("label.en_US").asText());
        var read = CliTestSupport.run("-m", model.toString(), "config", "read");
        assertEquals("Orders", M.readTree(read.out()).path("data").path("label").path("en_US").asText(),
                () -> read.out());
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
