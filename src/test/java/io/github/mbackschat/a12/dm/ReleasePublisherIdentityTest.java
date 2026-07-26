package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the public-mirror commit identity at the publisher boundary. Release clones are intentionally fresh,
 * so inheriting a workstation's global Git configuration would make their public history machine-dependent.
 */
class ReleasePublisherIdentityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publisherConfiguresTheMaintainerIdentityInsideEveryFreshMirrorClone()
            throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only release publisher");
        Path identityScript = repoFile("scripts/release-git-identity.sh");
        Path publisher = repoFile("scripts/publish-release.sh");
        String publisherSource = Files.readString(publisher);
        assertTrue(publisherSource.contains(". \"$ROOT/scripts/release-git-identity.sh\""));
        assertTrue(publisherSource.contains("configure_release_git_identity \"$CLONE\""));

        run("git", "init", temporaryDirectory.toString());
        run("bash", "-c", ". \"$1\"; configure_release_git_identity \"$2\"",
                "release-identity-test", identityScript.toString(), temporaryDirectory.toString());
        Files.writeString(temporaryDirectory.resolve("release.txt"), "release\n");
        run("git", "-C", temporaryDirectory.toString(), "add", "release.txt");
        run("git", "-C", temporaryDirectory.toString(), "commit", "-m", "release test");

        String identity = run("git", "-C", temporaryDirectory.toString(), "log", "-1",
                "--format=%an <%ae>|%cn <%ce>");
        assertEquals(
                "Martin Backschat <mbackschat@mail.de>|Martin Backschat <mbackschat@mail.de>",
                identity.trim());
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> String.join(" ", command) + " failed:\n" + output);
        return output;
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            Path candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
