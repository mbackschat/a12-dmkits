package io.github.mbackschat.a12.dm.cli.model;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;

/**
 * Materializes a model file's content at a git {@code <ref>} — the baseline for {@code model diff --since}
 * (MODEL-REVIEW-SPEC §9c: the one deliberate git touch in an otherwise git-free tool). It shells to
 * {@code git} (so it needs git on PATH and the model inside a working tree) and reports a clear
 * {@link GitBaselineException} on every failure mode the caller turns into a corrective message.
 */
final class GitBaseline {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GitBaseline() {}

    /** A model plus a lazy id-indexed resolver over the same Git revision. */
    record Snapshot(String content, ModelResolver resolver) {}

    /** A git-side failure resolving the baseline (not a repo, bad ref, file absent at ref, git missing). */
    static final class GitBaselineException extends RuntimeException {
        GitBaselineException(String message) {
            super(message);
        }
    }

    /** The content of {@code modelFile} as committed at {@code ref} (e.g. {@code HEAD}). */
    static String contentAt(Path modelFile, String ref) {
        Repository repository = repository(modelFile);
        return contentAt(repository, ref);
    }

    /** The model and referenced document models as they existed at {@code ref}. Dependency indexing is lazy. */
    static Snapshot snapshotAt(Path modelFile, String ref, List<Path> workspaceRoots) {
        Repository repository = repository(modelFile);
        return new Snapshot(contentAt(repository, ref),
                new GitModelResolver(repository.root(), ref, List.copyOf(workspaceRoots)));
    }

    private static String contentAt(Repository repository, String ref) {
        return run(repository.root(),
                "cannot read " + repository.modelPath() + " at git ref '" + ref + "'",
                "show", ref + ":" + repository.modelPath());
    }

    private static Repository repository(Path modelFile) {
        Path absolute = modelFile.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        String root = run(directory, "the model is not in a git repository (cannot resolve --since)",
                "rev-parse", "--show-toplevel").trim();
        // git computes the prefix itself, avoiding macOS /tmp → /private path-alias mismatches.
        String prefix = run(directory, "the model is not in a git repository (cannot resolve --since)",
                "rev-parse", "--show-prefix").trim();
        return new Repository(Path.of(root), prefix + absolute.getFileName());
    }

    private static List<String> repositoryPaths(Path repositoryRoot, List<Path> workspaceRoots) {
        try {
            Path realRepository = repositoryRoot.toRealPath();
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (Path workspaceRoot : workspaceRoots) {
                Path realRoot = workspaceRoot.toRealPath();
                if (!realRoot.startsWith(realRepository)) {
                    throw new GitBaselineException("workspace root " + workspaceRoot
                            + " is outside the model's git repository; --since cannot resolve it at a git ref");
                }
                String relative = realRepository.relativize(realRoot).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    return List.of();
                }
                paths.add(relative);
            }
            return List.copyOf(paths);
        } catch (IOException exception) {
            throw new GitBaselineException("cannot resolve workspace roots for --since: " + exception.getMessage());
        }
    }

    private record Repository(Path root, String modelPath) {}

    /** Loads the revision index only if expansion actually asks for a referenced model. */
    private static final class GitModelResolver implements ModelResolver {
        private final Path repositoryRoot;
        private final String ref;
        private final List<Path> workspaceRoots;
        private Map<String, String> modelsById;

        private GitModelResolver(Path repositoryRoot, String ref, List<Path> workspaceRoots) {
            this.repositoryRoot = repositoryRoot;
            this.ref = ref;
            this.workspaceRoots = workspaceRoots;
        }

        @Override
        public String resolve(String modelId) {
            String model = models().get(modelId);
            if (model == null) {
                throw new UnresolvedReferenceException(modelId, "no document model with id \"" + modelId
                        + "\" found at git ref '" + ref + "' under " + workspaceRoots);
            }
            return model;
        }

        private synchronized Map<String, String> models() {
            if (modelsById == null) {
                modelsById = indexModelsAt(repositoryRoot, ref,
                        repositoryPaths(repositoryRoot, workspaceRoots));
            }
            return modelsById;
        }
    }

    /** One tree listing plus one persistent batch process: no per-model Git process explosion. */
    private static Map<String, String> indexModelsAt(Path repositoryRoot, String ref, List<String> roots) {
        List<String> paths = jsonPathsAt(repositoryRoot, ref, roots);
        List<String> command = List.of("git", "-C", repositoryRoot.toString(), "cat-file", "--batch");
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new GitBaselineException("git is not available on PATH (needed for --since)");
        }
        Map<String, String> byId = new LinkedHashMap<>();
        try (var input = new BufferedInputStream(process.getInputStream());
                var requests = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8))) {
            for (String path : paths) {
                if (path.indexOf('\n') >= 0) {
                    continue;
                }
                requests.write(ref + ":" + path);
                requests.newLine();
                requests.flush();
                String header = readLine(input);
                if (header == null) {
                    throw new GitBaselineException("git ended while reading " + path + " at ref '" + ref + "'");
                }
                if (header.endsWith(" missing")) {
                    continue;
                }
                int lastSpace = header.lastIndexOf(' ');
                int size = Math.toIntExact(Long.parseLong(header.substring(lastSpace + 1)));
                byte[] content = input.readNBytes(size);
                if (content.length != size || input.read() != '\n') {
                    throw new GitBaselineException("git returned a truncated model blob for " + path);
                }
                indexDocumentModel(byId, new String(content, StandardCharsets.UTF_8));
            }
            requests.close();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = process.waitFor();
            if (code != 0) {
                throw new GitBaselineException("cannot read workspace models at git ref '" + ref + "'"
                        + (error.isEmpty() ? "" : " — " + error));
            }
            return Map.copyOf(byId);
        } catch (IOException exception) {
            process.destroyForcibly();
            throw new GitBaselineException("git baseline workspace read failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitBaselineException("interrupted resolving the git baseline workspace");
        } catch (ArithmeticException | NumberFormatException exception) {
            process.destroyForcibly();
            throw new GitBaselineException("git returned an invalid model blob size at ref '" + ref + "'");
        } catch (GitBaselineException exception) {
            process.destroyForcibly();
            throw exception;
        }
    }

    private static List<String> jsonPathsAt(Path repositoryRoot, String ref, List<String> roots) {
        List<String> arguments = new ArrayList<>(List.of("ls-tree", "-r", "--name-only", "-z", ref));
        if (!roots.isEmpty()) {
            arguments.add("--");
            arguments.addAll(roots);
        }
        byte[] output = runBytes(repositoryRoot,
                "cannot list workspace models at git ref '" + ref + "'", arguments);
        List<String> paths = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < output.length; index++) {
            if (output[index] == 0) {
                String path = new String(output, start, index - start, StandardCharsets.UTF_8);
                if (path.endsWith(".json")) {
                    paths.add(path);
                }
                start = index + 1;
            }
        }
        if (roots.isEmpty()) {
            return List.copyOf(paths);
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String root : roots) {
            paths.stream().filter(path -> path.equals(root) || path.startsWith(root + "/"))
                    .forEach(ordered::add);
        }
        return List.copyOf(ordered);
    }

    private static void indexDocumentModel(Map<String, String> byId, String content) {
        try {
            JsonNode header = JSON.readTree(content).path("header");
            if ("document".equals(header.path("modelType").asText())) {
                String id = header.path("id").asText();
                if (!id.isEmpty()) {
                    byId.putIfAbsent(id, content);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A JSON file that is not a document model is not resolvable, matching the current-worktree index.
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = input.read(); next >= 0; next = input.read()) {
            if (next == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(next);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }

    private static String run(Path cwd, String failHint, String... gitArgs) {
        return new String(runBytes(cwd, failHint, List.of(gitArgs)), StandardCharsets.UTF_8);
    }

    private static byte[] runBytes(Path cwd, String failHint, List<String> gitArgs) {
        String[] cmd = new String[gitArgs.size() + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = cwd.toString();
        for (int index = 0; index < gitArgs.size(); index++) {
            cmd[index + 3] = gitArgs.get(index);
        }
        Process process;
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new GitBaselineException("git is not available on PATH (needed for --since)");
        }
        try {
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            int code = process.waitFor();
            if (code != 0) {
                String detail = new String(err, StandardCharsets.UTF_8).trim();
                throw new GitBaselineException(failHint + (detail.isEmpty() ? "" : " — " + detail));
            }
            return out;
        } catch (IOException e) {
            throw new GitBaselineException("git baseline read failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitBaselineException("interrupted resolving the git baseline");
        }
    }
}
