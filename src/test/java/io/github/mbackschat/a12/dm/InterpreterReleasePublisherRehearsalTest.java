package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Runs the complete interpreter release protocol against disposable local Git and release-host state.
 * No production repository, release, workflow, or Pages deployment is reachable from this rehearsal.
 */
class InterpreterReleasePublisherRehearsalTest {

    private static final List<String> REQUIRED_OUTCOMES = List.of(
            "PASS wrong author identity",
            "PASS wrong committer identity",
            "PASS dirty source",
            "PASS source tag/version mismatch",
            "PASS source tag/SHA mismatch",
            "PASS published release",
            "PASS existing immutable version",
            "PASS missing TypeScript declaration",
            "PASS empty JavaScript package",
            "PASS missing API documentation",
            "PASS failed production URL smoke",
            "PASS go-live source checkout must match tag",
            "PASS anonymous public consumer programs",
            "PASS published consumer smoke resumes",
            "PASS prerelease remains non-latest",
            "PASS default publication is stable root version",
            "PASS complete draft-to-production path",
            "interpreter release rehearsal: OK");

    @Test
    void disposableMirrorExercisesEveryRefusalAndTheCompletePath() throws Exception {
        var rehearsal = repoFile("scripts/rehearse-interpreter-release.sh");
        assertTrue(Files.isRegularFile(rehearsal), "missing release rehearsal: " + rehearsal);

        var process = new ProcessBuilder("bash", rehearsal.toString())
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.waitFor(), output);
        for (var outcome : REQUIRED_OUTCOMES) {
            assertTrue(output.contains(outcome), () -> "missing outcome '" + outcome + "':\n" + output);
        }
    }

    @Test
    void publicWorkflowsKeepPrivateSourceOutsideTheMirrorAndPreserveTheDraftBoundary() throws Exception {
        var build = Files.readString(repoFile(".github/interpreter-mirror-workflows/build-release.yml"));
        assertTrue(build.contains("run-name: Build release ${{ inputs.release_tag }}"));
        assertTrue(build.contains("path: public"));
        assertTrue(build.contains("path: source"));
        assertTrue(build.contains("--destination \"$GITHUB_WORKSPACE/public\""));
        assertTrue(build.contains("interpreter_release_verify_payload"));
        assertTrue(build.contains("interpreter_release_verify_commit_identities"));
        assertTrue(build.contains("git push --atomic origin HEAD:main \"+refs/tags/${{ inputs.release_tag }}\""));
        assertTrue(build.contains("test \"$(gh release view \"${{ inputs.release_tag }}\" --json isDraft -q .isDraft)\" = true"));
        assertTrue(
                build.indexOf("- name: Upload the immutable draft assets")
                        < build.indexOf("git push --atomic origin"),
                "draft asset upload must precede the atomic mirror/tag publication");

        var pages = Files.readString(repoFile(".github/interpreter-mirror-workflows/deploy-pages.yml"));
        assertTrue(pages.contains("run-name: Deploy Pages for ${{ inputs.release_tag }}"));
        assertTrue(pages.contains("workflow_dispatch:"));
        assertTrue(pages.contains("Verify the requested release is still a draft"));
        assertTrue(pages.contains("uses: actions/deploy-pages@"));
        assertFalse(pages.contains("push:"), "a mirror push must not deploy draft content");
    }

    @Test
    void productionGoLiveExecutesTheExistingConsumersAgainstAnonymousArtifacts() throws Exception {
        var smokePath = repoFile("scripts/smoke-interpreter-public-release.sh");
        assertTrue(Files.isRegularFile(smokePath), "missing anonymous public-consumer smoke: " + smokePath);
        var smoke = Files.readString(smokePath);
        assertTrue(smoke.contains(":interpreter:prepareExternalInterpreterConsumer"));
        assertTrue(smoke.contains(":interpreter:prepareExternalInterpreterTypeScriptConsumer"));
        assertTrue(smoke.contains("pnpm"));
        assertTrue(smoke.contains("gradlew"));

        var backend = Files.readString(repoFile("scripts/interpreter-release-github.sh"));
        assertTrue(backend.contains("smoke-interpreter-public-release.sh"));
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            var candidate = Path.of(base, relative);
            if (Files.exists(candidate.getParent())) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(relative + " not reachable from " + Path.of("").toAbsolutePath());
    }
}
