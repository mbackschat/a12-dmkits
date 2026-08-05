package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>CG8 — every parameter that names something on the filesystem publishes which way content flows.</b>
 *
 * <p>The gap this closes: the manifest tagged {@code kind:"FILE"} on 12 of 320 parameters (317
 * verb params + 3 globals), inconsistently — {@code --output} alone occurs 45 times and carried it on 4 — and without direction (the same kind covered {@code --schema}
 * which is read and {@code --output} which is written). A consumer could not tell content from destination.
 *
 * <p>The anti-drift lever is here rather than in an annotation: {@link ContentRole#fileShaped} reads the
 * {@code paramLabel}, which is <b>independent</b> of the classification rules, so this test can catch a
 * file-shaped parameter the rules fail to place. That is what makes "a new file parameter cannot ship
 * unpublished" a checked claim instead of a promise.
 */
class ParamContentRoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode manifest() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res::err);
        return MAPPER.readTree(res.out());
    }

    /** Every param the manifest publishes, keyed as "<verb> <flag>" → the param node. */
    private static Map<String, JsonNode> publishedParams(JsonNode manifest) {
        Map<String, JsonNode> params = new TreeMap<>();
        for (JsonNode verb : manifest.path("verbs")) {
            for (JsonNode param : verb.path("params")) {
                params.put(verb.path("verb").asText() + " " + param.path("flag").asText(), param);
            }
        }
        for (JsonNode global : manifest.path("globalOptions")) {
            params.put("<global> " + global.path("flag").asText(), global);
        }
        return params;
    }

    /**
     * The biconditional, in the direction that matters for a consumer: a parameter taking a path says so. The
     * denominator is the live picocli tree via the derived manifest, so it cannot go stale.
     */
    @Test
    void everyFileShapedParamPublishesItsContentRole() throws Exception {
        Map<String, JsonNode> params = publishedParams(manifest());
        assertFalse(params.isEmpty(), "no params read from the manifest — the walk is broken");

        List<String> unpublished = params.entrySet().stream()
                .filter(e -> ContentRole.fileShaped(e.getValue().path("paramLabel").asText(null)))
                .filter(e -> !e.getValue().hasNonNull("contentRole"))
                .map(e -> e.getKey() + " (label " + e.getValue().path("paramLabel").asText() + ")")
                .sorted().toList();
        assertTrue(unpublished.isEmpty(),
                () -> "these params name a file or directory but publish no `contentRole`, so a consumer cannot "
                        + "tell whether they carry content in or out — classify them in ContentRole: " + unpublished);

        // Anti-vacuity: the assertion above is satisfied by publishing NOTHING if fileShaped never fires.
        long published = params.values().stream().filter(p -> p.hasNonNull("contentRole")).count();
        assertTrue(published >= 60,
                () -> "only " + published + " params carry a contentRole — the label detector has stopped "
                        + "matching, so the guard above passes vacuously");
    }

    /**
     * The other direction — a role is never published on a parameter that takes no path. Without this the gap
     * could be "closed" by tagging everything, which would mislead exactly as badly as tagging nothing.
     */
    @Test
    void noRoleIsPublishedOnAParamThatNamesNoPath() throws Exception {
        List<String> spurious = publishedParams(manifest()).entrySet().stream()
                .filter(e -> e.getValue().hasNonNull("contentRole"))
                .filter(e -> !ContentRole.fileShaped(e.getValue().path("paramLabel").asText(null)))
                .map(e -> e.getKey() + " (label " + e.getValue().path("paramLabel").asText() + ")")
                .sorted().toList();
        assertTrue(spurious.isEmpty(),
                () -> "these params publish a `contentRole` but their label names no file or directory: " + spurious);
    }

    /**
     * The inconsistency that <em>was</em> CG8: structurally identical parameters must now agree. {@code --output}
     * is declared 45 separate times (it is not inherited), which is precisely how 41 of them ended up untagged;
     * with {@code --out-dir} the destination family is 47.
     */
    @Test
    void everyOutputParamAgreesOnBeingADestination() throws Exception {
        Map<String, JsonNode> params = publishedParams(manifest());
        List<String> outputs = params.entrySet().stream()
                .filter(e -> e.getKey().endsWith(" --output") || e.getKey().endsWith(" --out-dir"))
                .map(Map.Entry::getKey).sorted().toList();
        assertTrue(outputs.size() >= 40, () -> "expected the ~45 --output declarations, found " + outputs.size());
        for (String key : outputs) {
            assertEquals("OUTBOUND_DESTINATION", params.get(key).path("contentRole").asText(),
                    () -> key + " must publish OUTBOUND_DESTINATION");
        }
    }

    /**
     * A value-less flag publishes no {@code paramLabel}. picocli synthesizes one from the field name for an
     * arity-0 boolean ({@code --recursive} → {@code <recursive>}) and never renders it in {@code --help}; when
     * the manifest began publishing labels it published those too, telling an agent that {@code --recursive}
     * takes an argument. Caught on the live tree, so it stays caught.
     */
    @Test
    void noValuelessFlagPublishesAValueShape() throws Exception {
        // Derived from the live tree, not a flag list: every arity-0 option, wherever it is declared.
        Set<String> valueless = new java.util.HashSet<>();
        collectValuelessFlags(new CommandLine(new RootCommand()).getCommandSpec(), valueless);
        assertFalse(valueless.isEmpty(), "no arity-0 option found in the tree — the walk is broken");

        List<String> misleading = publishedParams(manifest()).entrySet().stream()
                .filter(e -> e.getValue().hasNonNull("paramLabel"))
                .filter(e -> valueless.contains(e.getValue().path("flag").asText()))
                .map(e -> e.getKey() + " → " + e.getValue().path("paramLabel").asText())
                .sorted().toList();
        assertTrue(misleading.isEmpty(),
                () -> "these flags take no value but publish a paramLabel, which reads as though they do: "
                        + misleading);
    }

    private static void collectValuelessFlags(CommandLine.Model.CommandSpec spec, Set<String> into) {
        spec.options().stream()
                .filter(o -> o.arity().max() == 0)
                .forEach(o -> into.add(o.longestName()));
        spec.subcommands().values().forEach(sub -> collectValuelessFlags(sub.getCommandSpec(), into));
    }

    /**
     * <b>Misclassification, not just non-classification.</b> The classifier decides direction from the flag
     * name, so a destination that is not <em>named</em> like one falls through to the inbound default — which is
     * exactly how {@code batch --observations} was published as {@code INBOUND_CONTENT} while
     * {@code BatchObservationCapture} does {@code Files.writeString} to it. The parameter's own description is an
     * <b>independent</b> signal (authored for humans, not derived from the flag), so cross-checking against it
     * catches that class. Limitation, stated rather than implied: prose is a heuristic, so this catches a
     * write-described parameter classified as anything else, and cannot prove the converse.
     */
    @Test
    void noParamDescribedAsWrittenIsClassifiedAsInboundContent() throws Exception {
        java.util.regex.Pattern writes = java.util.regex.Pattern.compile(
                "\\b(overwrite|written as|written to|write (?:the|all|it|them)|writes)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        List<String> wrong = publishedParams(manifest()).entrySet().stream()
                .filter(e -> e.getValue().hasNonNull("contentRole"))
                .filter(e -> !"OUTBOUND_DESTINATION".equals(e.getValue().path("contentRole").asText()))
                .filter(e -> writes.matcher(e.getValue().path("description").asText()).find())
                .map(e -> e.getKey() + " is " + e.getValue().path("contentRole").asText()
                        + " but its description says it is written")
                .sorted().toList();
        assertTrue(wrong.isEmpty(),
                () -> "these params are described as being written but are not classified as a destination — add "
                        + "the flag to ContentRole.DESTINATION_FLAGS: " + wrong);
    }

    /** The four roles are each actually reachable — a value no live param carries is a fiction in the vocabulary. */
    @Test
    void everyRoleIsCarriedByAtLeastOneLiveParam() throws Exception {
        Map<String, JsonNode> params = publishedParams(manifest());
        for (ContentRole role : ContentRole.values()) {
            assertTrue(params.values().stream().anyMatch(p -> role.name().equals(p.path("contentRole").asText())),
                    () -> "no live param carries " + role + " — remove the value or classify the param it is for");
        }
    }

    /** The vocabulary rides the manifest, so a consumer learns what each role means without reading our source. */
    @Test
    void theManifestPublishesWhatEachRoleMeans() throws Exception {
        JsonNode vocabulary = manifest().path("contentRoles");
        assertTrue(vocabulary.isObject() && vocabulary.size() == ContentRole.values().length,
                () -> "the manifest publishes all " + ContentRole.values().length + " role meanings: " + vocabulary);
        for (ContentRole role : ContentRole.values()) {
            assertEquals(role.meaning(), vocabulary.path(role.name()).asText(),
                    () -> role + " carries its meaning in the manifest");
        }
    }
}
