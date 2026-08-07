package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Locks the repository-level policies that make cold and clean-worktree Kotlin compilation reliable.
 */
class BuildPerformancePolicyTest {

    @Test
    void repositoryEnablesTheGradleBuildCache() throws IOException {
        var properties = buildProperties();

        assertEquals("true", properties.getProperty("org.gradle.caching"),
                "gradle.properties must enable org.gradle.caching so clean worktrees do not recompile identical outputs");
    }

    @Test
    void kotlinDaemonIsSizedForColdDualRuntimeAndAdapterCompilation() throws IOException {
        var properties = buildProperties();

        assertEquals("-Xmx1536m", properties.getProperty("kotlin.daemon.jvmargs"),
                "the default 512 MiB Kotlin daemon OOMs when cold JVM, JS, and adapter test compilation overlap");
    }

    @Test
    void kotlinPluginVersionIsLoadedOnceAtTheRoot() throws IOException {
        var rootBuild = Files.readString(locate("build.gradle"));
        var adapterBuild = Files.readString(locate("adapter/build.gradle"));
        var interpreterBuild = Files.readString(locate("interpreter/build.gradle"));

        var jvmVersion = rootPluginVersion(rootBuild, "org.jetbrains.kotlin.jvm");
        var multiplatformVersion = rootPluginVersion(rootBuild, "org.jetbrains.kotlin.multiplatform");
        assertEquals(jvmVersion, multiplatformVersion, "all Kotlin plugin ids must share one root-owned version");
        assertFalse(adapterBuild.contains("id 'org.jetbrains.kotlin.jvm' version"));
        assertFalse(interpreterBuild.contains("id 'org.jetbrains.kotlin.multiplatform' version"));
    }

    @Test
    void crossWorktreeCacheProofCannotUseTheNetwork() throws IOException {
        String script = Files.readString(locate("scripts/verify-build-cache.sh"));

        assertTrue(script.contains("./build.sh --offline --console=plain"),
                "the cache smoke must run Gradle offline so a remote cache or dependency download cannot make it pass");
    }

    @Test
    void buildWrapperDerivesTheSharedHomeFromTheCommonGitDirectory() throws IOException {
        String script = Files.readString(locate("build.sh"));
        String environment = Files.readString(locate("scripts/gradle-environment.sh"));

        assertTrue(script.contains("scripts/gradle-environment.sh"),
                "the build wrapper and release consumers must share one Gradle-home derivation");
        assertTrue(environment.contains("rev-parse --path-format=absolute --git-common-dir"),
                "a linked worktree must resolve the primary checkout instead of using its own filesystem parent");
        assertTrue(environment.contains("common_checkout"),
                "the isolated Gradle home must be anchored at the common checkout shared by every worktree");
    }

    @Test
    void demoVerifierDelegatesItsBuildToTheWorktreeSafeWrapper() throws IOException {
        String script = Files.readString(locate("scripts/verify-demos.sh"));

        assertTrue(script.contains("./build.sh --no-daemon :cli:installDist"),
                "build-driving scripts must reuse build.sh's common-directory-aware Gradle home");
        assertTrue(script.contains("export DMTOOL_CLI=\"$ROOT/cli/build/install/dmtool/bin/dmtool\""),
                "the packaged demo gate must pin the installDist launcher it just built; an inherited "
                        + "DMTOOL_CLI must not redirect the repo shim to a stale binary");
        assertFalse(script.contains("export GRADLE_USER_HOME"),
                "the demo verifier must not derive a second Gradle home from a linked worktree's parent");
    }

    @Test
    void nativeCaptureDelegatesItsBuildToTheWorktreeSafeWrapper() throws IOException {
        String script = Files.readString(locate("scripts/capture-native-config.sh"));

        assertTrue(script.contains("./build.sh -PrkNativeAgent"),
                "native capture must reuse build.sh's common-directory-aware Gradle home");
        assertFalse(script.contains("./build.sh --continue"),
                "the tracing test suite must fail the capture instead of continuing to copy partial metadata");
        assertTrue(script.contains("./build.sh :cli:test --tests 'io.github.mbackschat.a12.dm.cli.NativeImageMetadataHygieneTest'"),
                "the destination-dependent hygiene check must run after the captured metadata is copied");
        assertFalse(script.contains("export GRADLE_USER_HOME"),
                "native capture must not derive a second Gradle home from a linked worktree's parent");
        assertTrue(script.contains("./build.sh -PrkCaptureInProgress :cli:nativeCaptureStamp"),
                "only a completed capture may mint the freshness stamp, so this script must be what authorizes "
                        + "it; dropping the property makes the task refuse (cli/build.gradle nativeCaptureStamp)");
    }

    /**
     * A release binary must never name a commit it was not built from. The project-wide runtimeClasspath
     * normalization ignores {@code version.properties} (so a bare commit does not re-run the whole {@code :cli}
     * estate), which also hid it from {@code nativeCompile}'s up-to-date check: on 2026-08-07 one invocation
     * regenerated the resource at {@code aa6f1763} and still reported the image UP-TO-DATE, leaving
     * {@code 3b85a822} baked in. The explicit input is the whole fix, so this pins it.
     */
    @Test
    void theNativeImageRebuildsWhenOnlyItsProvenanceChanged() throws IOException {
        String build = Files.readString(locate("cli/build.gradle"));

        assertTrue(build.contains("ignore 'io/github/mbackschat/a12/dm/cli/version.properties'"),
                "this guard exists because that normalization hides the resource from classpath-based "
                        + "up-to-date checks; if it is gone, re-derive whether the explicit input is still needed");
        String nativeCompile = namedTaskBlock(build, "nativeCompile");
        assertFalse(nativeCompile.isEmpty(), "cli/build.gradle must configure nativeCompile");
        assertTrue(nativeCompile.contains("inputs.file(generateVersionProperties"),
                "nativeCompile must declare the generated version.properties as an input, or the native image "
                        + "goes UP-TO-DATE across a commit and ships provenance naming the previous one");
    }

    @Test
    void catalogProseReviewDeclaresEveryRuntimeScannedTestTree() throws IOException {
        String build = Files.readString(locate("build.gradle"));
        String task = gradleTask(build, "catalogProseReviewGuardTest");

        assertFalse(task.isEmpty(),
                "the catalog prose evidence resolver needs a dedicated input-declared task");
        assertTrue(build.contains("dependsOn 'catalogProseReviewGuardTest'"),
                "the broad root test must drive the catalog prose review task");
        // Asserted through the DERIVATION, not a literal exclude line: the broad task's excludes are computed
        // from the owning tasks' includes (`routeExclusivelyHostedTests`), so naming the owner here covers every
        // class that lane hosts. The literal-line form this replaced could only see the classes it spelled out,
        // which is how four cli classes and root's SourceStatisticsReleaseBoundaryTest drifted into both lanes.
        assertTrue(build.contains("routeExclusivelyHostedTests(it, ["),
                "the broad root test must DERIVE its excludes from the owning lanes, not mirror them by hand");
        assertTrue(ownersOfDerivedExcludes(build).contains("catalogProseReviewGuardTest"),
                "the catalog prose review must not also run in the input-blind broad root task");
        for (String tree : new String[] {
                "src/test",
                "adapter/src/test",
                "interpreter/src/commonTest",
                "interpreter/src/jvmTest",
                "interpreter/src/jsTest",
                "cli/src/test",
        }) {
            assertTrue(task.contains("fileTree('" + tree + "')"),
                    () -> "catalog prose review task does not declare scanned tree " + tree);
        }
        assertTrue(task.contains("guardInputs(it"),
                "the dedicated task must reject a silently empty scanned evidence tree");
    }

    @Test
    void docSnippetFreshnessDeclaresTheRawTaggedSourceDenominator() throws IOException {
        String task = gradleTask(Files.readString(locate("build.gradle")), "docGuardTest");

        assertTrue(task.contains("'canonical snippet test sources'"),
                "DocSnippetFreshnessTest reads tagged source text, so bytecode alone is not a sufficient task input");
        assertTrue(task.contains("fileTree('.')"),
                "the tagged-source denominator must be derived from the repository test-source layout");
        assertTrue(task.contains("include '**/src/test/**/*.java', '**/src/test/**/*.kt'"),
                "every Java/Kotlin test source must invalidate the doc guard when a tag-only edit leaves bytecode unchanged");
        assertTrue(task.contains("exclude '.claude/**', '**/build/**'"),
                "the derived tree must exclude linked worktrees and generated build outputs");
    }

    private static Properties buildProperties() throws IOException {
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(locate("gradle.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    private static String rootPluginVersion(String build, String pluginId) {
        var matcher = Pattern.compile(
                "id '" + Pattern.quote(pluginId) + "' version '([^']+)' apply false").matcher(build);
        assertTrue(matcher.find(), () -> "root build must own the version for " + pluginId);
        return matcher.group(1);
    }

    /** The task names the broad `test` derives its excludes from — the argument list of the routing call. */
    private static String ownersOfDerivedExcludes(String build) {
        int start = build.indexOf("routeExclusivelyHostedTests(it, [");
        if (start < 0) {
            return "";
        }
        return build.substring(start, build.indexOf(']', start));
    }

    /** Brace-balanced body of a {@code tasks.named('<name>')} block — {@link #gradleTask} only finds registrations. */
    private static String namedTaskBlock(String build, String name) {
        int start = build.indexOf("tasks.named('" + name + "')");
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = build.indexOf('{', start); i >= 0 && i < build.length(); i++) {
            char character = build.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return build.substring(start, i + 1);
            }
        }
        return "";
    }

    private static String gradleTask(String build, String name) {
        String marker = "tasks.register('" + name + "', Test)";
        int start = build.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int next = build.indexOf("\ntasks.register('", start + marker.length());
        return build.substring(start, next < 0 ? build.length() : next);
    }

    private static Path locate(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate " + relativePath + " from " + Path.of("").toAbsolutePath());
    }
}
