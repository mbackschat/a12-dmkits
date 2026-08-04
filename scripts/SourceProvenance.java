import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports the Git revision and clean/dirty state baked into dmtool's generated version resource.
 *
 * <p>This is a Java source-file program so the Gradle build and its black-box tests execute one portable
 * implementation without adding a compiled build-time artifact.</p>
 */
public final class SourceProvenance {

    private static final Provenance UNKNOWN = new Provenance("(unknown)", "UNKNOWN");

    private SourceProvenance() {
    }

    public static void main(String[] args) {
        Provenance provenance = args.length == 1 ? read(Path.of(args[0])) : UNKNOWN;
        System.out.println(provenance.revision());
        System.out.println(provenance.state());
    }

    private static Provenance read(Path sourceRoot) {
        try {
            GitResult prefix = git(sourceRoot, "rev-parse", "--show-prefix");
            // At the actual repository/worktree root this is empty. A nested archive discovered through an
            // enclosing repository has a non-empty prefix and must not inherit that repository's identity.
            if (prefix.code() != 0 || !prefix.output().strip().isEmpty()) {
                return UNKNOWN;
            }
            GitResult revision = git(sourceRoot, "rev-parse", "HEAD");
            String commit = revision.output().strip();
            if (revision.code() != 0 || !commit.matches("[0-9a-f]{40}")) {
                return UNKNOWN;
            }
            // Explicit options override personal Git configuration: untracked source and dirty submodules
            // always make a developer artifact DIRTY.
            GitResult status = git(sourceRoot, "status", "--porcelain=v1", "--untracked-files=normal",
                    "--ignore-submodules=none");
            if (status.code() != 0) {
                return UNKNOWN;
            }
            return new Provenance(commit, status.output().strip().isEmpty() ? "CLEAN" : "DIRTY");
        } catch (IOException e) {
            return UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        }
    }

    private static GitResult git(Path sourceRoot, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(sourceRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitResult(process.waitFor(), output);
    }

    private record GitResult(int code, String output) { }

    private record Provenance(String revision, String state) { }
}
