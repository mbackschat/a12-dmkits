package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles;

/**
 * The demonstration gate (CLI-CONFORMANCE-SPEC, the matrix "demonstrated" column) — every worked example in
 * {@code verb-examples.json} (the one the {@code manifest} surfaces to a cold agent) is <b>executed through
 * the tool</b> against a real fixture and must produce its documented outcome. A drifted example — a renamed
 * flag, a now-invalid path, a verb whose behavior changed — fails here, so the "in action" demonstration an
 * agent reads cannot rot into a lie. Every example is <b>read-only</b> by construction (a read, or a mutation
 * under {@code --dry-run}), so it never touches the committed fixtures.
 */
class VerbDemonstrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * <b>No agent-facing claim names an envelope value the verb never publishes.</b>
     *
     * <p>A verb's {@code --help}/{@code manifest} description and its example's {@code intent} are the two
     * sentences a cold agent reads <em>before</em> running anything, so a stale value claim there sends it
     * looking for a key that is not in the output. Nothing executed prose, so {@code profile compare} kept
     * advertising "a mismatch is a read result with {@code valid:false}" through a whole phase in which
     * {@code valid} had stopped being published at all — the taxonomy now reserves it for a verdict a
     * consistency gate arbitrated, and comparing two profile artifacts arbitrates nothing. The exit code was
     * still 1 and {@code data.matches} still said {@code false}, so every behavioural guard stayed green while
     * the sentence describing the behaviour was wrong.
     *
     * <p>The check is narrow on purpose: only a {@code key:literal} claim about a <em>published envelope
     * key</em>, verified against the verb's own demonstrated envelope. That form asserts something specific
     * enough to rot; looser prose ("rides {@code data}") is left alone because it cannot be mechanically
     * judged, and a guard that overreaches gets weakened rather than obeyed.
     */
    @Test
    void noAgentFacingClaimNamesAnEnvelopeValueTheVerbNeverPublishes(@TempDir Path dir) throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        JsonNode corpus = corpus();
        List<String> wrong = new ArrayList<>();
        for (JsonNode v : manifest.path("verbs")) {
            String verb = v.path("verb").asText();
            JsonNode ex = corpus.get(verb);
            if (ex == null) {
                continue; // no demonstration to check the claim against; the matrix's demonstrated column owns that
            }
            List<String> claimed = claimedEnvelopeKeys(
                    v.path("description").asText("") + " " + ex.path("intent").asText(""));
            if (claimed.isEmpty()) {
                continue;
            }
            JsonNode envelope = M.readTree(
                    CliTestSupport.run(materialize(verb, ex, dir).toArray(new String[0])).out());
            for (String key : claimed) {
                if (!envelope.has(key)) {
                    List<String> actual = new ArrayList<>();
                    envelope.fieldNames().forEachRemaining(actual::add);
                    wrong.add(verb + " promises '" + key + "' but its envelope publishes " + actual);
                }
            }
        }
        assertTrue(wrong.isEmpty(),
                () -> "these agent-facing descriptions name an envelope value the verb does not publish: " + wrong
                        + ". The sentence an agent reads before running the verb must match what the verb emits — "
                        + "fix the description (and the example's intent), not the envelope");
    }

    /**
     * The positive control for the column above, and the reason it is a separate test.
     *
     * <p>Fixing the last real violation leaves that column scanning a clean surface, so it would pass whether the
     * scanner works or matches nothing at all — the vacuous-green shape this whole effort kept producing. Asserting
     * "at least one claim was found" is the wrong cure: it turns a clean surface into a failure and pressures the
     * next author to delete the guard. What actually needs proving is that the <em>scanner</em> still recognises
     * the claim form, so it is exercised directly, on the sentence that was really shipped.
     */
    @Test
    void theClaimScannerRecognisesTheFormThatWentStale() throws Exception {
        assertEquals(List.of("valid"),
                claimedEnvelopeKeys("a mismatch is visible as valid:false and exit 1"),
                "the exact sentence profile compare shipped for a phase — this is what the column must catch");
        assertEquals(List.of(), claimedEnvelopeKeys("Every fidelity axis rides `data`; a mismatch exits 1."),
                "loose prose names no literal, so it is out of scope rather than a false positive");
        assertEquals(List.of(), claimedEnvelopeKeys("see https://example.invalid/x for the contract"),
                "a URL's colon must not read as a value claim");
        assertFalse(envelopeKeys().isEmpty(),
                "the envelope key set is read from result.schema.json; an empty one would silently drop every claim");
        assertTrue(envelopeKeys().contains("valid"), "sanity: the schema really does define the key under test");
    }

    /**
     * Runtime ownership is derived from {@link SchemaCommand#DOC_INPUT}, the independent directional-contract
     * denominator for every verb that evaluates a document instance. Every member must actually emit
     * {@code engine:DM_INTERPRETER}; deriving the denominator from the engine value itself would skip a newly
     * mislabelled verb while the old three kept a floor green. An interpreter-backed verb may describe A12 input
     * or kernel-matched semantics, but it must not tell an agent that the runtime output was produced by the
     * kernel. The texts inspected are the manifest description, example intent, and the output data-schema
     * description where one exists. The shared document-input schema is deliberately excluded; it correctly names
     * A12's kernel-owned serializer format rather than attributing evaluation to it.
     */
    @Test
    void interpreterBackedDemonstrationsNeverAttributeRuntimeOutputToTheKernel(@TempDir Path dir) throws Exception {
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        JsonNode corpus = corpus();
        List<String> violations = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();

        for (String name : SchemaCommand.DOC_INPUT.keySet()) {
            JsonNode verb = null;
            for (JsonNode candidate : manifest.path("verbs")) {
                if (name.equals(candidate.path("verb").asText())) {
                    verb = candidate;
                    break;
                }
            }
            assertTrue(verb != null, () -> name + " is in DOC_INPUT but absent from the live manifest");
            JsonNode example = corpus.get(name);
            assertTrue(example != null, () -> name + " is in DOC_INPUT but has no demonstration");
            JsonNode envelope = M.readTree(
                    CliTestSupport.run(materialize(name, example, dir).toArray(new String[0])).out());
            assertEquals(RuntimeEvidence.Engine.DM_INTERPRETER.name(), envelope.path("engine").asText(),
                    () -> name + " consumes a runtime document instance but does not identify the interpreter: "
                            + envelope);
            checked.add(name);
            List<String> command = List.of("schema", verb.path("target").asText(), verb.path("op").asText());
            JsonNode contract = M.readTree(CliTestSupport.run(command.toArray(String[]::new)).out());
            auditRuntimeAttribution(name, "manifest description", verb.path("description").asText(), violations);
            auditRuntimeAttribution(name, "example intent", example.path("intent").asText(), violations);
            if (contract.has("dataSchema")) {
                auditRuntimeAttribution(name, "data-schema description",
                        contract.path("dataSchema").path("description").asText(), violations);
            }
        }

        assertEquals(SchemaCommand.DOC_INPUT.keySet(), checked,
                "every document-instance runtime contract must be checked exactly once");
        assertTrue(violations.isEmpty(), () -> "interpreter-backed runtime claims attributed output to the kernel:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void theRuntimeAttributionGuardFiresOnTheFalseClaimThatShipped() {
        List<String> violations = new ArrayList<>();
        auditRuntimeAttribution("model compute", "data-schema description",
                "what the kernel made of it, in kernel order", violations);
        assertEquals(1, violations.size(), "the real pre-fix claim must be rejected by the same audit path");
    }

    private static void auditRuntimeAttribution(String verb, String surface, String prose, List<String> violations) {
        if (prose.toLowerCase(java.util.Locale.ROOT).contains("kernel")) {
            violations.add(verb + " " + surface + ": " + prose);
        }
    }

    /** Envelope keys a prose sentence claims a concrete value for — the form specific enough to go stale. */
    private static List<String> claimedEnvelopeKeys(String prose) throws Exception {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\b([a-z][a-zA-Z]*):(true|false|null|[0-9]+)\\b").matcher(prose);
        List<String> claimed = new ArrayList<>();
        while (m.find()) {
            if (envelopeKeys().contains(m.group(1)) && !claimed.contains(m.group(1))) {
                claimed.add(m.group(1));
            }
        }
        return claimed;
    }

    /** The published envelope frame, read from the schema that defines it rather than restated here. */
    private static Set<String> envelopeKeys() throws Exception {
        try (InputStream in = VerbDemonstrationTest.class.getResourceAsStream("/schema/result.schema.json")) {
            JsonNode props = M.readTree(in).path("properties");
            Set<String> keys = new LinkedHashSet<>();
            props.fieldNames().forEachRemaining(keys::add);
            return keys;
        }
    }

    @Test
    void everyVerbExampleRunsAndProducesItsDocumentedOutcome(@TempDir Path dir) throws Exception {
        JsonNode corpus = corpus();
        int run = 0;
        for (Iterator<String> it = corpus.fieldNames(); it.hasNext();) {
            String verb = it.next();
            if (verb.startsWith("_")) {
                continue; // metadata key (e.g. "_about")
            }
            JsonNode ex = corpus.get(verb);
            List<String> command = materialize(verb, ex, dir);
            CliTestSupport.Result res = CliTestSupport.run(command.toArray(new String[0]));
            String expect = ex.path("expect").asText();
            assertOutcome(verb, expect, res, command);
            run++;
        }
        int ran = run;
        assertTrue(ran >= 40, () -> "the demonstration corpus must exercise the surface; ran=" + ran);
    }

    /**
     * <b>{@code --text} totality</b> (CLI-SPEC §3b "The result envelope"): every envelope-producing verb renders human
     * text under {@code --text}, over the whole demonstrated surface rather than the three crafted envelopes
     * {@code TextRendererDispatchTest} pins at the renderer boundary.
     *
     * <p>The two tests answer different questions and neither substitutes for the other. That one asks "given an
     * envelope, does the renderer dispatch correctly" — a unit property, and it cannot see a verb that never
     * hands the renderer an envelope in the first place. That was exactly the defect: {@code apply} printed a
     * bespoke object and {@code batch} a bare array, so {@link TextRenderer#renderIfEnvelope} found no textual
     * {@code outcome}, passed the payload through unchanged, and {@code --text} silently emitted raw JSON for
     * both — in every flag placement, on success and on failure. A renderer-level test is structurally unable to
     * catch that, because the input it constructs is the very thing the verb was failing to produce.
     *
     * <p>So the assertion is deliberately shaped as "the output is <b>not</b> the JSON envelope": pass-through is
     * the failure mode, and it is invisible to any check that starts from a well-formed envelope.
     */
    @Test
    void everyEnvelopeVerbRendersTextUnderTheTextFlag(@TempDir Path dir) throws Exception {
        JsonNode corpus = corpus();
        int checked = 0;
        for (Iterator<String> it = corpus.fieldNames(); it.hasNext();) {
            String verb = it.next();
            if (verb.startsWith("_")) {
                continue;
            }
            JsonNode ex = corpus.get(verb);
            if (!List.of("read", "preview").contains(ex.path("expect").asText())) {
                continue;   // an artifact/raw verb prints its artifact; --text has no envelope to render
            }
            List<String> command = new ArrayList<>(materialize(verb, ex, dir));
            command.add("--text");
            CliTestSupport.Result res = CliTestSupport.run(command.toArray(new String[0]));
            assertEquals(0, res.code(), () -> verb + " --text failed (" + command + "): " + res.err() + res.out());
            assertFalse(looksLikeTheJsonEnvelope(res.out()),
                    () -> verb + " --text emitted the raw JSON envelope instead of text. The renderer passes a "
                            + "payload through unchanged when it cannot find a textual `outcome`, so this means "
                            + "the verb printed something that is not the standard envelope: " + res.out());
            assertFalse(res.out().isBlank(), () -> verb + " --text produced nothing: " + command);
            checked++;
        }
        int n = checked;
        assertTrue(n >= 55, () -> "the --text column must cover the envelope surface; checked=" + n);
    }

    /**
     * True when {@code out} is machine JSON rather than rendered text — <b>any</b> parsed container, not just the
     * standard envelope.
     *
     * <p>The distinction is the whole guard, and the first version got it backwards. It returned true only for an
     * object carrying a textual {@code outcome}, so a bespoke object <em>without</em> {@code outcome} and a bare
     * array both returned false and satisfied the assertion — and those are exactly the two shapes {@code apply}
     * and {@code batch} used to emit, which the guard's own doc comment cites as the regression it exists to
     * catch. It would have passed on the defect, for the same reason the renderer test could not see it: both
     * were phrased in terms of a well-formed envelope, which is precisely what the broken verbs were not
     * producing. Testing "is this JSON at all" is the only phrasing that covers the shapes nobody anticipated.
     */
    private static boolean looksLikeTheJsonEnvelope(String out) {
        try {
            JsonNode n = M.readTree(out);
            return n.isObject() || n.isArray();
        } catch (Exception notJson) {
            return false;
        }
    }

    /** Write the example's model (+ siblings, + inline doc) to {@code dir} and resolve the {@code <...>} placeholders. */
    static List<String> materialize(String verb, JsonNode ex, Path dir) throws Exception {
        // model-originating verbs (e.g. `model new`) declare no `model` — they make one
        Path model = ex.hasNonNull("model") ? writeResource("models/" + ex.get("model").asText(), dir) : null;
        for (JsonNode sib : ex.path("siblings")) {
            writeResource("models/" + sib.asText(), dir); // co-located so an include resolves the sibling by id
        }
        Path doc = null;
        if (ex.has("doc")) {
            doc = dir.resolve("doc.json");
            Files.writeString(doc, ex.get("doc").toString());
        }
        Path schemaFile = null;   // an external JSON Schema input (model import-jsonschema)
        if (ex.has("schema")) {
            schemaFile = dir.resolve("schema.json");
            Files.writeString(schemaFile, ex.get("schema").toString());
        }
        Path profile = null;
        for (JsonNode tok : ex.path("command")) {
            if ("<profile.json>".equals(tok.asText())) {
                assertTrue(model != null, () -> verb + " uses <profile.json> but declares no `model`");
                profile = dir.resolve("profile.json");
                Files.writeString(profile, ModelProfiles.extract(Files.readString(model)));
                break;
            }
        }
        // a verb that needs an ISOLATED workspace with its own roles.yaml (workspace roles): the shared `dir`
        // accumulates every example's models, so we stage this one's models + roles file in a private subtree.
        Path rolesWorkspace = ex.has("rolesWorkspace") ? materializeRolesWorkspace(ex.get("rolesWorkspace"), dir) : null;
        Path profileArtifact = profile;
        List<String> out = new ArrayList<>();
        for (JsonNode tok : ex.path("command")) {
            String t = tok.asText();
            out.add(switch (t) {
                case "<model.json>" -> {
                    assertTrue(model != null, () -> verb + " uses <model.json> but declares no `model`");
                    yield model.toString();
                }
                case "<doc.json>" -> {
                    assertTrue(doc != null, () -> verb + " uses <doc.json> but declares no `doc`");
                    yield doc.toString();
                }
                case "<schema.json>" -> {
                    assertTrue(schemaFile != null, () -> verb + " uses <schema.json> but declares no `schema`");
                    yield schemaFile.toString();
                }
                case "<profile.json>" -> {
                    assertTrue(profileArtifact != null,
                            () -> verb + " uses <profile.json> but no profile artifact was materialized");
                    yield profileArtifact.toString();
                }
                // a directory verb (workspace list): the temp dir, already populated with the example's siblings
                case "<workspace-dir>" -> dir.toString();
                case "<roles-workspace>" -> {
                    assertTrue(rolesWorkspace != null, () -> verb + " uses <roles-workspace> but declares no `rolesWorkspace`");
                    yield rolesWorkspace.toString();
                }
                default -> t;
            });
        }
        return out;
    }

    private static void assertOutcome(String verb, String expect, CliTestSupport.Result res, List<String> command) throws Exception {
        switch (expect) {
            case "read", "preview" -> {
                assertEquals(0, res.code(), () -> verb + " demo failed (" + command + "): " + res.err() + res.out());
                JsonNode env = M.readTree(res.out());
                assertEquals(expect, env.path("outcome").asText(),
                        () -> verb + " demo expected outcome=" + expect + ": " + res.out());
                assertEnvelopeIdentifiesItsOwnVerb(verb, env, res);
                assertOnlyAVerdictRouteClaimsValidity(verb, env, res);
                if ("preview".equals(expect)) {
                    assertPreviewStatesItsGate(verb, env, res);
                }
            }
            case "raw" -> {
                // a non-envelope verb (export renders an artifact; model new emits a brand-new model document)
                assertEquals(0, res.code(), () -> verb + " demo failed (" + command + "): " + res.err());
                assertFalse(res.out().isBlank(), () -> verb + " demo produced no output: " + command);
            }
            default -> throw new AssertionError(verb + " has an unknown expect '" + expect + "'");
        }
    }

    /**
     * The envelope must identify the verb it came from — {@code target}/{@code op} are the command path, not free
     * prose: a two-token verb {@code X Y} reports {@code target=X, op=Y}, and a top-level verb ({@code meta},
     * {@code where-used}) reports itself as the target with {@code op=read}. Verified across every demonstrated
     * verb rather than per verb. {@link ResultIdentity} now makes the former adjacent-string swap unrepresentable;
     * this wire-level assertion stays as an independent end-to-end backstop that the right identity source was
     * selected for each demonstrated route.
     */
    private static void assertEnvelopeIdentifiesItsOwnVerb(String verb, JsonNode env, CliTestSupport.Result res) {
        String[] path = verb.split(" ");
        String expectedTarget = path[0];
        String expectedOp = path.length > 1 ? path[1] : "read";
        assertEquals(expectedTarget, env.path("target").asText(),
                () -> verb + ": the envelope must echo its own target — a swapped target/op mis-attributes the "
                        + "whole result and compiles cleanly: " + res.out());
        assertEquals(expectedOp, env.path("op").asText(),
                () -> verb + ": the envelope must echo its own op: " + res.out());
    }

    /**
     * <b>Verdict provenance</b> (CLI-SPEC §3b "The result envelope"): {@code valid} appears only where a verdict route
     * produced it. This is the column for the defect the whole contract exists to remove — 35 call sites passed a
     * literal {@code boolean valid}, and seven verbs were measured reporting {@code valid:true} on a model the
     * kernel rejects.
     *
     * <p>The typed factories already make that unrepresentable: construction is private and only
     * {@code consistencyVerdict} and a kernel {@code rejected} set the member. So this is the behavioural backstop
     * for a structural property, and it earns its place by covering the axis the structure does <em>not</em>:
     * whether a route that legitimately holds a verdict is the one being run. {@link VERDICT_VERBS} is the whole
     * list, so a new verb that starts publishing {@code valid} fails here rather than quietly joining them.
     */
    private static void assertOnlyAVerdictRouteClaimsValidity(String verb, JsonNode env, CliTestSupport.Result res) {
        if (VERDICT_VERBS.contains(verb)) {
            assertTrue(env.has("valid"),
                    () -> verb + " is a verdict route but published no `valid` — either it stopped gating, or the "
                            + "VERDICT_VERBS list is stale: " + res.out());
            return;
        }
        assertFalse(env.has("valid"),
                () -> verb + " published `valid` without being a consistency verdict. A read, a runtime "
                        + "observation and a mutation all establish no verdict, so claiming one is the exact "
                        + "fabrication this contract removed: " + res.out());
    }

    /** The verbs whose product IS a consistency verdict — the only ones that may publish {@code valid}. */
    private static final Set<String> VERDICT_VERBS =
            Set.of("model check", "rule check", "workspace check", "patterns scaffold");

    /**
     * <b>Receipt totality</b> (CLI-SPEC §3b "The result envelope"): a mutation that ran the consistency oracle must say
     * so, over the 31 preview demos rather than the handful anyone thought to write a test for — the
     * {@code expect: "preview"} entries of {@code verb-examples.json}, of which there were 31 on 2026-08-16.
     *
     * <p>This column exists because the evidence type makes a <em>wrong</em> claim unrepresentable but leaves
     * <em>silence</em> cheap. {@code ConsistencyEvidence.of(x)} compiles for any witness, and one holding no
     * receipt yields {@code none()} — so a site that names the wrong object, or a route whose gate is later moved
     * or dropped, degrades quietly to publishing nothing. Nothing fails; the key just stops appearing. Asserting
     * presence per verb over an enumerated list is what makes that visible, and it is deliberately not an
     * {@code anyMatch} over whatever happened to run — a guard shaped that way is satisfied by one passing verb
     * and reads as coverage for all of them (the failure mode this effort hit twice; §1d items 10 and 18).
     *
     * <p>{@link #UNGATED_PREVIEW} is the honest exemption, and it must state a reason per verb.
     */
    private static void assertPreviewStatesItsGate(String verb, JsonNode env, CliTestSupport.Result res) {
        String reason = UNGATED_PREVIEW.get(verb);
        if (reason != null) {
            assertFalse(env.has("verification"),
                    () -> verb + " is classified as ungated (" + reason + ") yet published a gate receipt — "
                            + "either the classification is stale or the receipt is fabricated: " + res.out());
            return;
        }
        assertTrue(env.hasNonNull("verification"),
            () -> verb + ": a preview runs the same gate its write would, so it must name that gate. Absent means "
                    + "the site's ConsistencyEvidence came from a witness that never gated (a pre-mutation "
                    + "ModelDocument, a lenient bind) — or the route stopped gating. Fix the witness, or classify "
                    + "the verb in UNGATED_PREVIEW with a reason: " + res.out());
    }

    /**
     * Preview demos that legitimately publish no {@code verification}, each with the reason it never consults the
     * oracle. A row here is a claim about the verb's behaviour, so it is checked in both directions above.
     */
    private static final Map<String, String> UNGATED_PREVIEW = Map.of(
            "model normalize", "a pure re-serialization of the parsed tree — no consistency gate is run");

    private static Path writeResource(String classpath, Path dir) throws Exception {
        return writeResourceTo(classpath, dir.resolve(classpath.substring(classpath.lastIndexOf('/') + 1)));
    }

    private static Path writeResourceTo(String classpath, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (InputStream in = VerbDemonstrationTest.class.getClassLoader().getResourceAsStream(classpath)) {
            assertTrue(in != null, () -> "fixture resource present: " + classpath);
            Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return target;
    }

    /** Stage an isolated workspace: the spec's models under {@code models/}, its roles file at {@code auth/roles.yaml}. */
    private static Path materializeRolesWorkspace(JsonNode spec, Path dir) throws Exception {
        Path ws = dir.resolve("roles-workspace");
        for (JsonNode m : spec.path("models")) {
            writeResourceTo("models/" + m.asText(), ws.resolve("models").resolve(basename(m.asText())));
        }
        writeResourceTo("models/" + spec.get("roles").asText(), ws.resolve("auth").resolve("roles.yaml"));
        return ws;
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static JsonNode corpus() throws Exception {
        try (InputStream in = VerbDemonstrationTest.class.getResourceAsStream("/verb-examples.json")) {
            assertTrue(in != null, "verb-examples.json present on the classpath");
            return M.readTree(in);
        }
    }
}
