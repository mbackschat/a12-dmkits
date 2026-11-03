package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The diagnostic-conformance gate (CLI-CONFORMANCE-SPEC) — the keystone that makes "must not happen again"
 * real. It is DERIVED from the live tool: it reads the {@code manifest}, and for every verb with an
 * entity-reference param (a {@code @ParamKind} that must exist) — AND, regardless of {@code @ParamKind}, every
 * READ-projection verb's locator positional (F5) — it synthesizes a <b>domain error</b>: feed that param a
 * bogus value, fill the rest from their kinds, and assert the result is a conformant <b>structured envelope</b>
 * ({@code refused}/{@code rejected}, exit 2/1, {@code written:false}, a coded diagnostic), never a bare stderr
 * string (AP1). A new verb escapes only by BOTH leaving its locator untagged AND minting a new op name
 * outside {@link #READ_PROJECTION_OPS} — tag the {@code @ParamKind} and the net is automatic.
 *
 * <p>It must never <em>false-fail</em>: when the synthesized call is itself structurally invalid (an
 * {@code @ArgGroup} the manifest can't express, a param it can't fill), picocli prints a usage message and
 * the case is <b>skipped + logged</b> (no silent truncation). Only a non-usage, non-envelope exit — a true
 * bare domain error — fails the gate.
 */
class DiagnosticConformanceFuzzTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> ENTITY_REF = Set.of(
            "FIELD_PATH", "GROUP_PATH", "RULE_PATH", "COMPUTATION_PATH", "TYPEDEF_ID", "INCLUDE_REFERENCE",
            "ELEMENT_REF");
    /** Read-PROJECTION ops whose sole positional is always a model-entity locator — probed regardless of
     *  {@code @ParamKind}, so a future read verb that forgets to tag its locator still can't bare-fail (F5). */
    private static final Set<String> READ_PROJECTION_OPS = Set.of("read", "explain", "format", "deps");

    @TempDir
    Path dir;
    private String template;

    @BeforeEach
    void buildFixture() {
        template = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", s -> s.field("Zip", FieldKind.STRING)))
                .build()
                .toJson();
    }

    @Test
    void everyMutatingVerbStructuresADomainError() throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Path work = dir.resolve("work.dm.json");
        int tested = 0;
        List<String> skipped = new ArrayList<>();

        for (JsonNode v : manifest.path("verbs")) {
            String verb = v.path("verb").asText();
            List<JsonNode> params = new ArrayList<>();
            v.path("params").forEach(params::add);
            // Probe every entity-reference param, AND — regardless of @ParamKind — a READ-projection verb's
            // locator positional, so a future read verb that forgets to tag its locator still can't regress to
            // a bare error (F5). A tagged read locator appears via both paths; dedupe by key.
            List<JsonNode> probeParams = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode p : params) {
                if (ENTITY_REF.contains(p.path("kind").asText()) && seen.add(p.path("key").asText())) {
                    probeParams.add(p);
                }
            }
            if (READ_PROJECTION_OPS.contains(v.path("op").asText())) {
                params.stream().filter(p -> p.path("positional").asBoolean()).findFirst()
                        .filter(p -> seen.add(p.path("key").asText()))
                        .ifPresent(probeParams::add);
            }
            if (probeParams.isEmpty()) {
                continue;   // no entity reference / read locator — nothing to probe
            }
            for (JsonNode testedParam : probeParams) {
                String testedKey = testedParam.path("key").asText();
                Optional<List<String>> args = synthesize(verb, params, testedKey, work);
                if (args.isEmpty()) {
                    skipped.add(verb + " [" + testedKey + "]: unfillable (FILE / no fixture value)");
                    continue;
                }
                Files.writeString(work, template);   // fresh per case — a stray apply can't contaminate
                CliTestSupport.Result res = CliTestSupport.run(args.get().toArray(new String[0]));
                JsonNode env = parse(res.out());
                if (env != null && env.has("outcome")) {
                    assertStructured(verb, testedKey, env, res.out());
                    tested++;
                } else if (res.err().contains("Usage:")) {
                    skipped.add(verb + " [" + testedKey + "]: picocli usage (ArgGroup/parse) — not synthesizable");
                } else {
                    fail(verb + " [" + testedKey + "] a bogus entity-ref gave a BARE error (AP1), not a structured "
                            + "envelope. exit=" + res.code() + " stderr=" + res.err() + " stdout=" + res.out());
                }
            }
        }
        int probed = tested;
        assertTrue(probed >= 10,
                () -> "the conformance fuzzer must exercise the mutating + read surface; tested=" + probed + " skipped=" + skipped);
        if (!skipped.isEmpty()) {
            System.out.println("[conformance fuzzer] " + probed + " verbs probed; skipped (logged, not failed): " + skipped);
        }
    }

    /** Build the CLI args for one (verb, tested entity-ref); empty if a required param can't be filled. */
    private Optional<List<String>> synthesize(String verb, List<JsonNode> params, String testedKey, Path model) {
        List<String> options = new ArrayList<>();
        List<String> positionals = new ArrayList<>();
        for (JsonNode p : params) {
            String key = p.path("key").asText();
            boolean tested = key.equals(testedKey);
            if (!tested && !p.path("required").asBoolean()) {
                continue;   // omit optional, non-tested params
            }
            String kind = p.path("kind").asText("");
            String value = tested ? bogus(kind) : ENTITY_REF.contains(kind) ? validFromFixture(kind) : benign(kind);
            if (value == null) {
                return Optional.empty();   // FILE, or an entity-ref kind the fixture has no value for
            }
            if (p.path("positional").asBoolean()) {
                positionals.add(value);
            } else {
                options.add(p.path("flag").asText());
                options.add(value);
            }
        }
        List<String> args = new ArrayList<>(List.of(verb.split(" ")));
        args.add("-m");
        args.add(model.toString());
        args.addAll(options);
        args.addAll(positionals);
        return Optional.of(args);
    }

    private void assertStructured(String verb, String key, JsonNode env, String out) {
        String outcome = env.path("outcome").asText();
        assertTrue(outcome.equals("refused") || outcome.equals("rejected"),
                () -> verb + " [" + key + "] a bogus entity-ref must refuse/reject, got outcome=" + outcome + ": " + out);
        assertTrue(!env.path("written").asBoolean(true),
                () -> verb + " [" + key + "] a refused/rejected op must write nothing: " + out);
        JsonNode d = env.path("diagnostics").path(0);
        assertTrue(d.has("where"), () -> verb + " [" + key + "] diagnostic must carry a `where`: " + out);
        if (outcome.equals("refused")) {
            // a precheck refusal is rulekit's own — it must be coded + fixable (the diagnostic contract)
            assertTrue(!d.path("code").asText().isBlank() && d.has("fix"),
                    () -> verb + " [" + key + "] a refusal must carry a code + fix: " + out);
        }
    }

    private static JsonNode parse(String out) {
        if (out == null || out.isBlank()) {
            return null;
        }
        try {
            return M.readTree(out);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bogus(String kind) {
        return switch (kind) {
            case "GROUP_PATH" -> "/__rk_absent_group__";
            case "TYPEDEF_ID" -> "__rk_absent_typedef__";
            case "INCLUDE_REFERENCE" -> "__rk_absent_include__";
            default -> "/__rk_absent__/__x__";   // FIELD_PATH / RULE_PATH / COMPUTATION_PATH
        };
    }

    /** A value that exists in the fixture, for filling a non-tested entity-ref; null if the fixture has none. */
    private static String validFromFixture(String kind) {
        return switch (kind) {
            case "FIELD_PATH", "ELEMENT_REF" -> "/Order/Note";
            case "GROUP_PATH" -> "/Order";
            default -> null;   // no rule/computation/typedef/include in the fixture — those verbs are single-ref
        };
    }

    private static String benign(String kind) {
        return switch (kind) {
            case "NEW_NAME" -> "RkProbe";
            case "FIELD_KIND" -> "NUMBER";
            case "SCALAR" -> "2";
            case "FREEFORM" -> "x";
            default -> null;   // FILE etc. — not fillable
        };
    }
}
