package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    void crossWorktreeCacheProofCannotUseTheNetwork() throws IOException {
        String script = Files.readString(locate("scripts/verify-build-cache.sh"));

        assertTrue(script.contains("./build.sh --offline --console=plain"),
                "the cache smoke must run Gradle offline so a remote cache or dependency download cannot make it pass");
    }

    private static Properties buildProperties() throws IOException {
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(locate("gradle.properties"))) {
            properties.load(reader);
        }
        return properties;
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
