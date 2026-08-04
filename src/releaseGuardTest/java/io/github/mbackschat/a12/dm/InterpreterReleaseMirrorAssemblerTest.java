package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the cumulative public interpreter mirror at its release-script boundary. The test uses tiny
 * synthetic payloads so every refusal remains fast and independent of Kotlin/JS compilation.
 */
class InterpreterReleaseMirrorAssemblerTest {

    private static final String VERSION = "0.10.1";
    private static final String SOURCE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshAssembliesAreByteIdenticalAndExposeThePublicContract() throws Exception {
        var inputs = releaseInputs(VERSION);
        var firstMirror = temporaryDirectory.resolve("first-mirror");
        var secondMirror = temporaryDirectory.resolve("second-mirror");
        var firstAssets = temporaryDirectory.resolve("first-assets");
        var secondAssets = temporaryDirectory.resolve("second-assets");

        assemble(inputs, firstMirror, firstAssets);
        assemble(inputs, secondMirror, secondAssets);

        assertEquals(treeDigests(firstMirror), treeDigests(secondMirror));
        assertEquals(treeDigests(firstAssets), treeDigests(secondAssets));
        assertTrue(Files.isRegularFile(firstMirror.resolve("site/showcase/index.html")));
        assertTrue(Files.isRegularFile(firstMirror.resolve("site/api/v" + VERSION + "/index.html")));
        assertTrue(Files.isRegularFile(firstMirror.resolve("site/api/latest/index.html")));
        assertTrue(Files.isRegularFile(firstMirror.resolve(
                "site/maven/io/github/mbackschat/a12/dm/dm-interpreter/" + VERSION
                        + "/dm-interpreter-" + VERSION + ".pom")));
        var mavenLandingPage = Files.readString(firstMirror.resolve("site/maven/index.html"));
        assertTrue(mavenLandingPage.contains(
                "io.github.mbackschat.a12.dm:dm-interpreter:" + VERSION));
        assertTrue(mavenLandingPage.contains(
                "io/github/mbackschat/a12/dm/dm-interpreter/" + VERSION
                        + "/dm-interpreter-" + VERSION + ".pom"));
        assertTrue(Files.readString(firstMirror.resolve("README.md")).contains(VERSION));
        assertTrue(Files.readString(firstMirror.resolve("site/privacy.html"))
                .contains("Martin Backschat"));
        assertTrue(Files.readString(firstMirror.resolve("site/privacy.html"))
                .contains("GitHub Pages"));
        assertFalse(Files.readString(firstMirror.resolve("site/privacy.html"))
                .contains("mgm-tp.com"));
        assertTrue(Files.readString(firstAssets.resolve("SHA256SUMS"))
                .contains("release-manifest.json"));
    }

    @Test
    void cumulativeAssemblyPreservesOldVersionsAndRefusesRepublishingOne() throws Exception {
        var oldInputs = releaseInputs("0.9.0");
        var currentInputs = releaseInputs(VERSION);
        var mirror = temporaryDirectory.resolve("cumulative-mirror");
        var assets = temporaryDirectory.resolve("cumulative-assets");

        assemble(oldInputs, mirror, assets);
        var oldApi = mirror.resolve("site/api/v0.9.0/index.html");
        var oldMaven = mirror.resolve(
                "site/maven/io/github/mbackschat/a12/dm/dm-interpreter/0.9.0/dm-interpreter-0.9.0.pom");
        var oldApiDigest = sha256(oldApi);
        var oldMavenDigest = sha256(oldMaven);

        assemble(currentInputs, mirror, assets);

        assertEquals(oldApiDigest, sha256(oldApi));
        assertEquals(oldMavenDigest, sha256(oldMaven));
        assertTrue(Files.readString(mirror.resolve("site/api/latest/index.html"))
                .contains(VERSION));
        var duplicate = runAssembler(currentInputs, mirror, assets);
        assertEquals(1, duplicate.exitCode());
        assertTrue(duplicate.output().contains("already exists"), duplicate::output);
    }

    @Test
    void exactInventoryAndSecretScanRejectTheWholeInjectionClass() throws Exception {
        var inputs = releaseInputs(VERSION);
        var mirror = temporaryDirectory.resolve("guarded-mirror");
        var assets = temporaryDirectory.resolve("guarded-assets");
        assemble(inputs, mirror, assets);

        Files.writeString(mirror.resolve("unexpected.txt"), "injected\n");
        var unexpected = runAssembler(releaseInputs("0.10.2"), mirror, assets);
        assertEquals(1, unexpected.exitCode());
        assertTrue(unexpected.output().contains("public file inventory"), unexpected::output);

        var secretInputs = releaseInputs("0.10.2");
        Files.writeString(secretInputs.showcase().resolve("secret.txt"),
                "private checkout: /Users/example/a12\n");
        var secretMirror = temporaryDirectory.resolve("secret-mirror");
        var secret = runAssembler(secretInputs, secretMirror,
                temporaryDirectory.resolve("secret-assets"));
        assertEquals(1, secret.exitCode());
        assertTrue(secret.output().contains("machine-local path"), secret::output);
        assertFalse(Files.exists(secretMirror.resolve("PUBLIC-FILES.sha256")));
    }

    @Test
    void refusedCumulativeAssemblyLeavesTheVerifiedMirrorUnchanged() throws Exception {
        var mirror = temporaryDirectory.resolve("transactional-mirror");
        var assets = temporaryDirectory.resolve("transactional-assets");
        assemble(releaseInputs("0.9.0"), mirror, assets);
        var before = treeDigests(mirror);
        var rejectedInputs = releaseInputs(VERSION);
        Files.writeString(rejectedInputs.showcase().resolve("secret.txt"),
                "private checkout: /Users/example/a12\n");

        var rejected = runAssembler(rejectedInputs, mirror, assets);

        assertEquals(1, rejected.exitCode());
        assertTrue(rejected.output().contains("machine-local path"), rejected::output);
        assertEquals(before, treeDigests(mirror));
    }

    @Test
    void publicMirrorSyncsCompareContentInsteadOfTrustingSizeAndTimestamp() throws Exception {
        for (var script : List.of(
                "scripts/assemble-interpreter-release-repo.sh",
                "scripts/publish-release.sh")) {
            var source = Files.readString(repoFile(script));
            assertTrue(
                    source.contains("rsync -a --checksum --delete"),
                    () -> script + " can retain stale same-size, same-timestamp release content");
        }
    }

    private ReleaseInputs releaseInputs(String version) throws IOException {
        var root = temporaryDirectory.resolve("inputs-" + version);
        Files.createDirectories(root);
        write(root.resolve("a12-interpreter-" + version + ".tgz"), "npm-" + version);
        write(root.resolve("maven").resolve(
                "io/github/mbackschat/a12/dm/dm-interpreter/" + version
                        + "/dm-interpreter-" + version + ".pom"),
                "<project><version>" + version + "</version></project>\n");
        write(root.resolve("api/v" + version + "/index.html"), "<title>API " + version + "</title>\n");
        write(root.resolve("api/v" + version + "/kotlin/index.html"), "Kotlin " + version + "\n");
        write(root.resolve("dm-interpreter-" + version + "-api-docs.zip"),
                "api-archive-" + version);
        write(root.resolve("showcase/index.html"), "<title>Showcase " + version + "</title>\n");
        write(root.resolve("showcase/assets/app.js"), "console.log('showcase');\n");
        write(root.resolve("public-sources/README.md"),
                "# A12 Interpreter releases\n\nVersion @VERSION@ for developers.\n");
        write(root.resolve("public-sources/CHANGELOG.md"),
                "# Changelog\n\n## [@VERSION@]\n\nInitial release.\n");
        write(root.resolve("public-sources/DISCLAIMER.md"),
                "# Disclaimer\n\nIndependent, not an official A12 or mgm artifact.\n");
        write(root.resolve("public-sources/privacy.html"),
                "<h1>Privacy</h1><p>Martin Backschat operates this GitHub Pages site.</p>\n");
        write(root.resolve("public-sources/index.html"),
                "<title>A12 Interpreter @VERSION@</title>\n");
        write(root.resolve("public-sources/maven.html"),
                """
                <title>A12 Interpreter Maven repository</title>
                <code>io.github.mbackschat.a12.dm:dm-interpreter:@VERSION@</code>
                <a href="io/github/mbackschat/a12/dm/dm-interpreter/@VERSION@/dm-interpreter-@VERSION@.pom">POM</a>
                """);
        write(root.resolve("public-sources/workflows/build-release.yml"), "name: build-release\n");
        write(root.resolve("public-sources/workflows/deploy-pages.yml"), "name: deploy-pages\n");
        return new ReleaseInputs(version, root);
    }

    private void assemble(ReleaseInputs inputs, Path mirror, Path assets)
            throws IOException, InterruptedException {
        var result = runAssembler(inputs, mirror, assets);
        assertEquals(0, result.exitCode(), result::output);
    }

    private ProcessResult runAssembler(ReleaseInputs inputs, Path mirror, Path assets)
            throws IOException, InterruptedException {
        var command = List.of(
                "bash",
                repoFile("scripts/assemble-interpreter-release-repo.sh").toString(),
                "--destination", mirror.toString(),
                "--assets-directory", assets.toString(),
                "--version", inputs.version(),
                "--source-tag", "interpreter-v" + inputs.version(),
                "--source-sha", SOURCE_SHA,
                "--npm-tarball", inputs.npm().toString(),
                "--maven-repository", inputs.maven().toString(),
                "--api-documentation", inputs.api().toString(),
                "--api-docs-archive", inputs.apiArchive().toString(),
                "--showcase", inputs.showcase().toString(),
                "--readme", inputs.readme().toString(),
                "--changelog", inputs.changelog().toString(),
                "--license", repoFile("LICENSE").toString(),
                "--disclaimer", inputs.disclaimer().toString(),
                "--privacy", inputs.privacy().toString(),
                "--landing-page", inputs.landingPage().toString(),
                "--maven-landing-page", inputs.mavenLandingPage().toString(),
                "--workflow-directory", inputs.workflows().toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(process.waitFor(), output);
    }

    private static List<String> treeDigests(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/')
                            + "=" + uncheckedSha256(path))
                    .sorted()
                    .toList();
        }
    }

    private static String uncheckedSha256(Path path) {
        try {
            return sha256(path);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            var candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record ReleaseInputs(String version, Path root) {
        Path npm() {
            return root.resolve("a12-interpreter-" + version + ".tgz");
        }

        Path maven() {
            return root.resolve("maven");
        }

        Path api() {
            return root.resolve("api/v" + version);
        }

        Path apiArchive() {
            return root.resolve("dm-interpreter-" + version + "-api-docs.zip");
        }

        Path showcase() {
            return root.resolve("showcase");
        }

        Path readme() {
            return publicSource("README.md");
        }

        Path changelog() {
            return publicSource("CHANGELOG.md");
        }

        Path disclaimer() {
            return publicSource("DISCLAIMER.md");
        }

        Path privacy() {
            return publicSource("privacy.html");
        }

        Path landingPage() {
            return publicSource("index.html");
        }

        Path mavenLandingPage() {
            return publicSource("maven.html");
        }

        Path workflows() {
            return publicSource("workflows");
        }

        private Path publicSource(String relative) {
            return root.resolve("public-sources").resolve(relative);
        }
    }
}
