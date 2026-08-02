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
        assertFalse(script.contains("export GRADLE_USER_HOME"),
                "the demo verifier must not derive a second Gradle home from a linked worktree's parent");
    }

    @Test
    void nativeCaptureDelegatesItsBuildToTheWorktreeSafeWrapper() throws IOException {
        String script = Files.readString(locate("scripts/capture-native-config.sh"));

        assertTrue(script.contains("./build.sh --continue -PrkNativeAgent"),
                "native capture must reuse build.sh's common-directory-aware Gradle home");
        assertFalse(script.contains("export GRADLE_USER_HOME"),
                "native capture must not derive a second Gradle home from a linked worktree's parent");
    }

    @Test
    void catalogProseReviewDeclaresEveryRuntimeScannedTestTree() throws IOException {
        String build = Files.readString(locate("build.gradle"));
        String task = gradleTask(build, "catalogProseReviewGuardTest");

        assertFalse(task.isEmpty(),
                "the catalog prose evidence resolver needs a dedicated input-declared task");
        assertTrue(build.contains("dependsOn 'catalogProseReviewGuardTest'"),
                "the broad root test must drive the catalog prose review task");
        assertTrue(build.contains("excludeTestsMatching 'io.github.mbackschat.a12.dm.rulekit.catalog.CatalogProseReviewGuardTest'"),
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
