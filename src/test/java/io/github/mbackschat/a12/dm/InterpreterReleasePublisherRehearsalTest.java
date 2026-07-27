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
            "PASS unqualified stage",
            "PASS changed qualified outputs",
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
        assertTrue(build.contains("gh release edit \"${{ inputs.release_tag }}\" --target \"$mirror_sha\" --verify-tag"));
        assertTrue(build.contains("--json targetCommitish -q .targetCommitish"));
        assertTrue(build.contains("test \"$(gh release view \"${{ inputs.release_tag }}\" --json isDraft -q .isDraft)\" = true"));
        assertTrue(
                build.indexOf("- name: Upload the immutable draft assets")
                        < build.indexOf("git push --atomic origin"),
                "draft asset upload must precede the atomic mirror/tag publication");

        var pages = Files.readString(repoFile(".github/interpreter-mirror-workflows/deploy-pages.yml"));
        assertTrue(pages.contains("run-name: Deploy Pages for ${{ inputs.release_tag }}"));
        assertTrue(pages.contains("workflow_dispatch:"));
        assertTrue(
                pages.lines().anyMatch("  contents: write"::equals),
                "the workflow token needs push-level contents access to inspect a private draft release");
        assertTrue(pages.contains("Verify the requested release is still a draft"));
        assertTrue(pages.contains("allow_published_refresh"));
        assertTrue(pages.contains("Verify the requested published release"));
        assertTrue(pages.contains("git diff --exit-code"));
        assertTrue(pages.contains("site/maven/io"));
        assertTrue(pages.contains("site/api/v*"));
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

    @Test
    void productionGoLiveBootstrapsFreshGitHubPagesBeforeDeployment() throws Exception {
        var backend = Files.readString(repoFile("scripts/interpreter-release-github.sh"));
        var bootstrapStart = backend.indexOf("release_host_ensure_pages()");
        assertTrue(bootstrapStart >= 0, "the production backend must own fresh-repository Pages setup");
        var pagesBootstrap = backend.substring(
                bootstrapStart,
                backend.indexOf("release_host_deploy_pages()"));
        var pagesDeployment = backend.substring(
                backend.indexOf("release_host_deploy_pages()"),
                backend.indexOf("release_host_publish_nonlatest()"));

        assertTrue(pagesBootstrap.contains("gh api"));
        assertTrue(pagesBootstrap.contains("--method POST"));
        assertTrue(pagesBootstrap.contains("--method PUT"));
        assertTrue(pagesBootstrap.contains("build_type=workflow"));
        var bootstrapCall = pagesDeployment.indexOf("release_host_ensure_pages");
        var workflowDispatch = pagesDeployment.indexOf("gh workflow run deploy-pages.yml");
        assertTrue(
                bootstrapCall >= 0 && bootstrapCall < workflowDispatch,
                "a new public repository must enable Pages in workflow mode before its first deployment");
    }

    @Test
    void isolatedConsumerHomesReuseTheAlreadyResolvedGradleDistribution() throws Exception {
        var interpreterBuild = Files.readString(repoFile("interpreter/build.gradle"));
        var consumerTasks = interpreterBuild.substring(
                interpreterBuild.indexOf("def verifyExternalInterpreterConsumer"),
                interpreterBuild.indexOf("def verifyInterpreterMavenPublication"));
        assertTrue(
                interpreterBuild.contains("gradle.gradleHomeDir"),
                "in-build standalone consumers must use the already-running Gradle distribution");
        assertFalse(
                consumerTasks.contains("rootProject.file('gradlew')"),
                "an isolated consumer home must not force the wrapper to download Gradle again");
        assertEquals(
                2,
                consumerTasks.lines()
                        .filter(line -> line.contains("currentGradleExecutable.get().absolutePath"))
                        .count(),
                "both JVM and JS standalone consumers must reuse the current distribution");

        var smoke = Files.readString(repoFile("scripts/smoke-interpreter-public-release.sh"));
        assertTrue(smoke.contains(". \"$ROOT/scripts/gradle-environment.sh\""));
        assertTrue(smoke.contains("a12_gradle_user_home"));
        assertTrue(smoke.contains("ln -s \"$SOURCE_WRAPPER_DISTS\" \"$GRADLE_HOME/wrapper/dists\""));
        assertTrue(
                smoke.contains("GRADLE_USER_HOME=\"$GRADLE_HOME\""),
                "only the distribution is reused; dependency caches and init scripts must remain isolated");
    }

    @Test
    void stageConsumesTheQualifiedMirrorWithoutASecondAssembly() throws Exception {
        var core = Files.readString(repoFile("scripts/interpreter-release-publisher-core.sh"));
        var stage = core.substring(
                core.indexOf("interpreter_release_stage()"),
                core.indexOf("interpreter_release_smoke_pages()"));
        var qualification = core.substring(
                core.indexOf("interpreter_release_qualify()"),
                core.indexOf("interpreter_release_stage()"));

        assertTrue(core.contains("preview=\"$source_root/build/interpreter-release/public-mirror\""));
        assertTrue(core.contains("assets=\"$source_root/build/interpreter-release/public-assets\""));
        assertTrue(core.contains("interpreter_release_qualify()"));
        assertTrue(core.contains("interpreter_release_assemble_public_mirror()"));
        assertFalse(qualification.contains("build.sh"), "the complete Gradle gate has its own bounded command");
        assertFalse(stage.contains("build.sh"), "stage must consume a verified qualification receipt");
        assertFalse(
                stage.contains("interpreter_release_assemble"),
                "the full interpreter gate already assembles and verifies these exact release outputs");
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
