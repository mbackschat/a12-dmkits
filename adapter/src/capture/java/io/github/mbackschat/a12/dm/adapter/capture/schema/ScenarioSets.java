package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.core.ComputationInfo;
import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * Load + structurally validate a scenario-set file — the ONE pre-execution acceptance pipeline shared by
 * {@code validate-scenarios} and {@code capture} (both call exactly this; {@code DecoderParityTest}
 * guards the parity). Runs NO semantic engine and writes NO evidence: parse (strict) → the closed
 * {@link ScenarioDecoder} → resolve every declared model ref inside the scenario root (traversal/symlink-safe)
 * → verify the declared SHA-256 against the exact bytes → the kernel-free model-structure gates
 * ({@link ModelIndex}): placement/probe paths resolve, and the v1 input-support gate — a computation whose
 * target or scope lies under a repeatable group is REJECTED ({@code non-repeatable-compute-targets-v1}).
 */
public final class ScenarioSets {

    private ScenarioSets() {
    }

    /** A loaded, structurally accepted scenario set: the decoded request, its exact bytes, and each model's
     *  exact bytes + verified digest (keyed by ref, request order). */
    public record Loaded(ScenarioSet set, byte[] scenarioBytes, Map<String, ModelBytes> models) {
    }

    /** A model input's exact bytes and their (verified) SHA-256. */
    public record ModelBytes(byte[] bytes, String sha256) {
    }

    /** Load {@code scenarioFile}, resolving refs against its parent directory (the scenario root). */
    public static Loaded load(Path scenarioFile) {
        List<String> problems = new ArrayList<>();
        byte[] scenarioBytes = readFile(scenarioFile, problems);
        if (!problems.isEmpty()) {
            throw new ScenarioRejectedException(problems);
        }
        ScenarioSet set;
        try {
            set = ScenarioDecoder.decode(Json.parse(scenarioBytes));
        } catch (IllegalArgumentException parseFailure) {
            throw new ScenarioRejectedException(List.of("$: " + parseFailure.getMessage()));
        }

        Path root = scenarioFile.toAbsolutePath().getParent();
        Map<String, ModelBytes> models = new LinkedHashMap<>();
        Map<String, ModelIndex> indexes = new LinkedHashMap<>();
        for (ScenarioSet.ModelRef ref : set.models()) {
            ModelBytes bytes = resolveModel(root, ref, problems);
            if (bytes == null) {
                continue;
            }
            models.put(ref.ref(), bytes);
            ModelIndex index = indexModel(ref.ref(), bytes, problems);
            if (index != null) {
                indexes.put(ref.ref(), index);
                checkV1InputSupport(ref.ref(), index, problems);
            }
        }
        for (ScenarioSet.ScenarioCase c : set.cases()) {
            ModelIndex index = indexes.get(c.modelRef());
            if (index != null) {
                checkCaseAgainstModel(c, index, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new ScenarioRejectedException(problems);
        }
        return new Loaded(set, scenarioBytes, models);
    }

    private static byte[] readFile(Path file, List<String> problems) {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            problems.add("scenario file " + file + " is not a regular file");
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            problems.add("scenario file " + file + " is unreadable: " + e.getMessage());
            return null;
        }
    }

    /** Resolve a declared model ref inside the scenario root — every component symlink-free, the resolved
     *  file inside the root — and verify the declared digest against the exact bytes. */
    private static ModelBytes resolveModel(Path root, ScenarioSet.ModelRef ref, List<String> problems) {
        Path candidate = root;
        for (String segment : ref.ref().split("/")) {
            candidate = candidate.resolve(segment);
            if (Files.isSymbolicLink(candidate)) {
                problems.add("model ref \"" + ref.ref() + "\": \"" + root.relativize(candidate)
                        + "\" is a symlink — references must resolve to regular files inside the scenario root");
                return null;
            }
        }
        if (!candidate.normalize().startsWith(root.normalize())) {
            problems.add("model ref \"" + ref.ref() + "\" escapes the scenario root — traversal is rejected");
            return null;
        }
        if (!Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            problems.add("model ref \"" + ref.ref() + "\" does not resolve to a file inside the scenario root");
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(candidate);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read model " + ref.ref(), e);
        }
        String actual = Digests.sha256(bytes);
        if (!actual.equals(ref.sha256())) {
            problems.add("model ref \"" + ref.ref() + "\": declared sha256 " + ref.sha256()
                    + " does not match the file's actual " + actual + " — the request binds exact bytes");
            return null;
        }
        return new ModelBytes(bytes, actual);
    }

    private static ModelIndex indexModel(String ref, ModelBytes bytes, List<String> problems) {
        try {
            return ModelIndex.ofSelfContained(new String(bytes.bytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            problems.add("model \"" + ref + "\" is not structurally readable DM-JSON: " + e.getMessage());
            return null;
        }
    }

    /** The v1 input-support gate ({@code non-repeatable-compute-targets-v1}): reject a computation whose
     *  TARGET or SCOPE lies under a repeatable group — repeat support needs a new declared input-support
     *  version and separating evidence (proposal §4). */
    private static void checkV1InputSupport(String ref, ModelIndex index, List<String> problems) {
        for (ComputationInfo computation : index.computations()) {
            if (underRepeatableGroup(index, computation.targetFieldPath())) {
                problems.add("model \"" + ref + "\": computation " + computation.path() + " targets "
                        + computation.targetFieldPath() + " under a repeatable group — "
                        + SchemaIds.INPUT_SUPPORT_COMPUTE + " rejects repeatable computation targets");
            } else if (underRepeatableGroup(index, computation.groupPath() + "/")) {
                problems.add("model \"" + ref + "\": computation " + computation.path() + " is scoped under a"
                        + " repeatable group — " + SchemaIds.INPUT_SUPPORT_COMPUTE
                        + " rejects repeatable computation scopes");
            }
        }
    }

    /** Whether any PROPER ancestor group of {@code path} (or, for a path ending in {@code /}, the group
     *  itself) is repeatable. */
    private static boolean underRepeatableGroup(ModelIndex index, String path) {
        String[] segments = path.substring(1).split("/");
        StringBuilder ancestor = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            ancestor.append('/').append(segments[i]);
            if (index.isGroup(ancestor.toString()) && index.isRepeating(ancestor.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void checkCaseAgainstModel(ScenarioSet.ScenarioCase c, ModelIndex index, List<String> problems) {
        String at = "case \"" + c.caseId() + "\"";
        for (ScenarioSet.Placement p : c.placements()) {
            switch (p.kind()) {
                case GROUP -> {
                    if (!index.isGroup(p.path())) {
                        problems.add(at + ": GROUP placement path " + p.path() + " is not a group of the model");
                    }
                }
                case FIELD, EMPTY -> {
                    if (!index.isField(p.path())) {
                        problems.add(at + ": " + p.kind() + " placement path " + p.path()
                                + " is not a field of the model");
                    }
                }
            }
        }
        for (String probe : c.probes()) {
            String namePath = probe.replaceAll("\\[\\d+\\]", "");
            if (!index.isField(namePath)) {
                problems.add(at + ": probe pointer " + probe + " does not name a field of the model");
            }
        }
    }
}
