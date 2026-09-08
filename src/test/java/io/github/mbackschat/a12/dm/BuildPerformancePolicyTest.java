package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** An unescaped {@code VAR=$(…java_home…)} capture on a non-comment line — a second place deciding the pin. */
    private static final java.util.regex.Pattern RE_ENCODED_PIN = java.util.regex.Pattern.compile(
            "(?m)^[^#\n]*\\b(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*=\"?\\$\\(/usr/libexec/java_home");

    /**
     * The POSITIVE half of the single-sourcing claim: each named consumer resolves the pin through the shared
     * helper. The NEGATIVE half — that nobody ELSE re-encodes it — deliberately does not live here, because
     * this list is authored and an authored list cannot see the consumer nobody remembered. It is
     * {@code JdkPinSingleSourceGuardTest}, which sweeps every git-tracked non-prose file and therefore runs in
     * the always-run {@code leakGuardTest}. That split was made after this list read as a complete guard while
     * the committed {@code .envrc} re-encoded the pin unnoticed.
     *
     * <p>The pin is advertised as single-sourced by three surfaces ({@code scripts/README.md}, the
     * preparation script's own comment, and the helper's javadoc), and the defect that created the helper was
     * exactly two sites deciding it differently — the build pinned, the launcher check did not. A re-encoded
     * {@code java_home -v 21} is therefore the drift to catch, not a style preference.
     */
    @Test
    void everyJdkPinGoesThroughTheOneSharedDerivation() throws IOException {
        assertTrue(Files.readString(locate("scripts/gradle-environment.sh")).contains("a12_pinned_jdk21_home()"),
                "the shared helper must own the pin");
        // The denominator is every committed file that decides which JDK to run something under — including
        // `benchmark.workflow.js`, which is not a shell script but emits one, and `perf-keys.sh`, whose own
        // "only when JAVA_HOME is unset" policy is separate from the pin it then applies. Both re-encoded
        // `java_home -v 21`, which was also macOS-only in the JS case.
        for (String consumer : List.of("build.sh", "scripts/prepare-dmtool-source.sh",
                "scripts/verify-demos.sh", "scripts/doctor.sh", "scripts/perf-keys.sh",
                "eval/benchmark.workflow.js")) {
            String script = Files.readString(locate(consumer));
            assertTrue(script.contains("a12_pinned_jdk21_home"),
                    consumer + " must resolve the JDK 21 pin through the shared helper");
            assertFalse(RE_ENCODED_PIN.matcher(script).find(),
                    consumer + " assigns from `java_home` directly; two sites deciding one policy is the defect "
                            + "the helper exists to remove. (Naming the command in a comment or in a corrective "
                            + "message is fine — capturing its output into a variable is not.)");
        }
    }

    /**
     * An included build shares no plugin management with the build that includes it, so each declares its
     * own — and two hand-kept copies is the drift this single source exists to remove. The test therefore
     * checks both halves: every build applies the shared file, and none of them declares a repository of
     * its own beside it.
     */
    @Test
    void publicRepositoriesAreSingleSourcedAndEveryBuildRootCarriesTheOptOutMarker() throws IOException {
        Path repositoryRoot = locate("settings.gradle").getParent();
        String shared = SourceText.uncommented(repositoryRoot.resolve("gradle/public-repositories.gradle"));
        assertTrue(shared.contains("gradlePluginPortal()") && shared.contains("mavenCentral()"),
                "the shared declaration must name the public repositories this project actually resolves from");
        assertFalse(shared.contains("mavenLocal()"),
                "no plugin here comes from mavenLocal, and putting a mutable unchecksummed local repository "
                        + "in the plugin path would change resolution for every contributor and both CI jobs");

        for (String build : new String[] {"", "build-logic/"}) {
            Path root = repositoryRoot.resolve(build.isEmpty() ? "." : build).normalize();
            String settings = SourceText.uncommented(root.resolve("settings.gradle"));

            int declaration = settings.indexOf("pluginManagement {");
            assertTrue(declaration >= 0, build + "settings.gradle must declare its own plugin repositories; "
                    + "with none declared, Gradle resolves plugins from whatever a machine-global init "
                    + "script injects, and plugin management is NOT inherited from an including build");
            assertTrue(declaration < settings.indexOf("rootProject.name"), build
                    + "settings.gradle's declaration has to come first: an init script appends its "
                    + "repositories in settingsEvaluated, so ours are tried first only by being declared here");
            assertTrue(settings.contains("public-repositories.gradle"), build
                    + "settings.gradle must apply the single-sourced repository file rather than restate it");
            assertFalse(settings.contains("gradlePluginPortal()") || settings.contains("mavenCentral()"),
                    build + "settings.gradle names a repository beside the shared file it applies. A second "
                            + "copy is a second thing to keep right, which is what single-sourcing removes");
            assertTrue(Files.isRegularFile(root.resolve(".no-global-gradle-repos")), build
                    + ".no-global-gradle-repos is missing. The marker is read from a BUILD's root directory, "
                    + "so an included build has its own root and is not covered by the repository's copy; "
                    + "without it, an init-script-injecting machine still reaches that build");
        }
    }

    /**
     * The observed-reads wire crosses a build boundary, so no compiler holds its two ends together. The
     * producer is the `build-logic` included build; the consumer is {@link Checkout}. Divergence is SILENT —
     * the recorder returns early, every observation set is empty, and the undeclared-read check passes
     * because it saw nothing, which is exactly the false green the check exists to prevent.
     *
     * <p>A text match is the only instrument available across two Gradle builds, and the subject genuinely
     * is a file. What it cannot see is the property being read from somewhere other than this constant.
     */
    @Test
    void theObservedReadsPropertyNameIsTheSameOnBothSidesOfTheBuildBoundary() throws IOException {
        String source = Files.readString(locate(
                "build-logic/src/main/kotlin/io/github/mbackschat/a12/dm/buildlogic/BuildGuardsExtension.kt"));
        assertTrue(source.contains('"' + Checkout.OBSERVED_READS_PROPERTY + '"'),
                "the guard task must set the same system property Checkout reads ("
                        + Checkout.OBSERVED_READS_PROPERTY + "). These are two string literals in two "
                        + "different Gradle builds; if they diverge nothing throws, the observation set is "
                        + "empty, and the undeclared-read check passes because it saw nothing");
    }

    @Test
    void theLeakScanAlwaysRunsAndIsNeverRestoredFromCache() throws IOException {
        String task = gradleTask(Files.readString(locate("build.gradle")), "leakGuardTest");

        assertTrue(task.contains("outputs.upToDateWhen { false }"),
                "a leak scan that can go UP-TO-DATE is one that can be stale, which is worse than none: "
                        + "\"the git-tracked set\" is not expressible as a Gradle input");
        assertTrue(task.contains("outputs.cacheIf { false }"),
                "without this the task stores a cache entry on every build and its always-runs guarantee "
                        + "rests on undocumented restore behaviour rather than on the declaration");
    }

    @Test
    void demoVerifierDelegatesItsBuildToTheWorktreeSafeWrapper() throws IOException {
        String launcher = Files.readString(locate("scripts/verify-demos.sh"));
        String implementation = Files.readString(locate("scripts/typescript/verify-demos.ts"));

        assertTrue(implementation.contains("spawnSync(\"./build.sh\", [\"--no-daemon\", \":cli:installDist\"]"),
                "build-driving scripts must reuse build.sh's common-directory-aware Gradle home");
        assertTrue(implementation.contains("DMTOOL_CLI: path.join(root, \"cli/build/install/dmtool/bin/dmtool\")"),
                "the packaged demo gate must pin the installDist launcher it just built; an inherited "
                        + "DMTOOL_CLI must not redirect the repo shim to a stale binary");
        assertTrue(launcher.contains("a12_pinned_jdk21_home"),
                "the stable launcher must apply the same shared JDK pin to the post-build CLI process");
        assertFalse((launcher + implementation).contains("export GRADLE_USER_HOME"),
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
        assertTrue(script.contains("build/native-metadata-regression/${CAPTURE_HEAD:0:12}"),
                "the previous committed capture must survive the run as an inspectable regression baseline");
        assertTrue(script.contains("baseline-reachability-metadata.json"),
                "the preserved regression directory must identify the committed baseline explicitly");
        assertTrue(script.contains("raw-recapture-reachability-metadata.json"),
                "the unsanitized agent result must remain available for member-level loss analysis");
        assertTrue(script.contains("shipped-recapture-reachability-metadata.json"),
                "the sanitized shipping candidate must remain beside the baseline and raw capture");
        assertTrue(script.contains("\"$REGRESSION_DIR/member-diff\""),
                "the baseline-versus-recapture comparison must retain its canonical member inventories and "
                        + "added/removed reports instead of deleting the regression evidence on exit");
        String removalCheck = Files.readString(locate("scripts/check-native-metadata-removals.sh"));
        assertTrue(removalCheck.contains("org.codehaus.groovy.runtime.callsite.CallSite"),
                "capture-only kernel-generated Groovy classes must be classified by their actual constructor "
                        + "dependency, not a brittle generated class-name pattern");
        assertTrue(removalCheck.contains("raw-removed.entries")
                        && removalCheck.contains("capture-only-removed.entries")
                        && removalCheck.contains("removed.entries"),
                "the report must retain every raw loss while separating native-inapplicable capture noise from "
                        + "the actionable removals that fail the capture");
        assertFalse(script.contains("rk-native-metadata-baseline.XXXXXX"),
                "a temporary baseline deleted on exit cannot support post-capture regression review");
    }

    /**
     * The capture stamp must mean the same thing on every machine. It hashed {@code GRAALVM_HOME} — an absolute
     * path, and a different patch release on a runner — so it could only ever verify on the machine that minted
     * it. The gate fails closed, so every CI native build broke the moment the stamp was first committed and the
     * check stopped being vacuous (2026-08-07). Any environment read puts that back.
     */
    @Test
    void theCaptureStampDigestReadsNoMachineSpecificEnvironment() throws IOException {
        String digest = closureBody(Files.readString(locate("cli/build.gradle")), "nativeCaptureStampDigest");
        assertTrue(digest.contains("MessageDigest"), "the capture stamp digest closure must be found intact");

        String code = SourceText.uncommentedFragment(digest, false);
        for (String read : AMBIENT_READS) {
            assertFalse(code.contains(read), "the capture stamp digest must hash only repository inputs; '"
                    + read + "' reads ambient state, which makes the stamp unverifiable on any other machine, "
                    + "and the gate that consumes it fails closed");
        }

        // A direct read is the obvious form, not the only one: binding the environment to a name OUTSIDE this
        // closure and hashing the name inside it is the same defect and passed a check for the call alone. So
        // collect every file-scope name bound to an ambient read — `def x =`, `ext.x =`, a bare `x =`, or a
        // method whose body performs one — and require the digest to mention none of them.
        String build = SourceText.uncommented(locate("cli/build.gradle"));
        for (String name : namesBoundToAmbientState(build)) {
            assertFalse(Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find(),
                    "the capture stamp digest reads '" + name + "', which is bound from ambient state earlier "
                            + "in cli/build.gradle. Hoisting the read out of the closure leaves the stamp "
                            + "exactly as machine-specific as reading it inline");
        }
    }

    @Test
    void nativeCaptureCannotHideReachabilityBehindAmbientAnsiSettingsOrUnhashedRouting() throws IOException {
        String cliBuild = Files.readString(locate("cli/build.gradle"));
        assertTrue(cliBuild.contains("environment.remove(it)")
                        && cliBuild.contains("['NO_COLOR', 'CLICOLOR', 'CLICOLOR_FORCE', 'ANSICON', 'ConEmuANSI']"),
                "NO_COLOR and related ambient hints can short-circuit picocli's reflective System.console "
                        + "lookup, so the traced Test process itself must normalize them; changing only the "
                        + "calling shell does not override an already-running Gradle daemon's environment");

        String digest = closureBody(cliBuild, "nativeCaptureStampDigest");
        for (String input : List.of("build.gradle", "*/build.gradle", "scripts/capture-native-config.sh",
                "scripts/check-native-metadata-removals.sh")) {
            assertTrue(digest.contains(input),
                    "the capture stamp must hash " + input + " because it can change test routing, agent "
                            + "inputs, or whether a narrower capture is accepted");
        }
    }

    /**
     * Every ambient-state read a Gradle build script can reach. Blacklisting spellings is bounded only because
     * this vocabulary is: {@code System.getenv} was the whole list once, and it accepted
     * {@code providers.environmentVariable} — a Gradle-idiomatic form of exactly the defect — untouched.
     *
     * <p><b>The bound of this guard:</b> it reads source text, so a dynamically composed call
     * ({@code System."${'get' + 'env'}"}) evades it. That is accepted rather than papered over: the check
     * catches every form anyone would write on purpose, and the stamp gate itself fails closed on CI if one
     * slips through. It is a tripwire for an accident, not a barrier against an author who means it.
     */
    private static final List<String> AMBIENT_READS = List.of(
            "System.getenv", "System.env", "System.getProperty", "System.properties",
            "providers.environmentVariable", "providers.systemProperty");

    /**
     * <b>The hoist detector must actually fire.</b> Each form below is a way to move an ambient read out of the
     * digest closure and reference it from inside; the detector previously recognized only {@code def x =} with
     * {@code System.getenv}, so a method wrapper, an {@code ext} property, and Gradle's own
     * {@code providers.environmentVariable} all passed while doing exactly what the invariant forbids. Asserting
     * on a synthetic build script keeps this a positive control without mutating the repository.
     */
    @Test
    void theHoistDetectorRecognizesEveryBindingFormOfAnAmbientRead() {
        String script = """
                def graal = System.getenv('GRAALVM_HOME')
                ext.home = System.env['JAVA_HOME']
                runner = providers.environmentVariable('CI').get()
                def toolchain() { System.getProperty('java.home') }
                def innocent = rootProject.file('scripts/x.json')
                """;

        assertEquals(Set.of("graal", "home", "runner", "toolchain"), namesBoundToAmbientState(script),
                "every way of binding ambient state to a name must be detected; a name the digest can "
                        + "reference is a machine-specific stamp however the value reached it");
    }

    /** File-scope names whose value comes from {@link #AMBIENT_READS}, in any binding form Groovy offers. */
    private static Set<String> namesBoundToAmbientState(String build) {
        String reads = AMBIENT_READS.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        var names = new java.util.LinkedHashSet<String>();
        // `def x = …`, `ext.x = …`, `x = …` — the assignment forms, read on one line.
        Matcher assigned = Pattern.compile("(?:\\bdef\\s+|\\bext\\.)?(\\w+)\\s*=[^\\n]*(?:" + reads + ")")
                .matcher(build);
        while (assigned.find()) {
            names.add(assigned.group(1));
        }
        // `def name(…) { … System.getenv … }` — a method wrapping the read is the same hoist one indirection on.
        Matcher method = Pattern.compile("\\bdef\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{([^}]*)}", Pattern.DOTALL)
                .matcher(build);
        while (method.find()) {
            if (Pattern.compile(reads).matcher(method.group(2)).find()) {
                names.add(method.group(1));
            }
        }
        return names;
    }


    /**
     * A release binary must never name a commit it was not built from. The project-wide runtimeClasspath
     * normalization ignores {@code version.properties} (so a bare commit does not re-run the whole {@code :cli}
     * estate), which also hid it from {@code nativeCompile}'s up-to-date check: on 2026-08-07 one invocation
     * regenerated the resource at {@code aa6f1763} and still reported the image UP-TO-DATE, leaving
     * {@code 3b85a822} baked in. The explicit input is the whole fix, so this pins that it is DECLARED.
     *
     * <p><b>Named for what it checks.</b> It read {@code theNativeImageRebuildsWhenOnlyItsProvenanceChanged},
     * which claims a rebuild this test never observes — the declaration could sit in a dead branch and this
     * would still pass. Observing the rebuild needs a Gradle TestKit build plus a native compile, which is not
     * affordable in this lane. The behavioural claim rests on the 2026-08-07 measurement; what runs on every
     * build is this declaration check, and the name now says so.
     */
    @Test
    void nativeCompileDeclaresTheGeneratedProvenanceAsAnInput() throws IOException {
        String build = Files.readString(locate("cli/build.gradle"));

        assertTrue(build.contains("ignore 'io/github/mbackschat/a12/dm/cli/version.properties'"),
                "this guard exists because that normalization hides the resource from classpath-based "
                        + "up-to-date checks; if it is gone, re-derive whether the explicit input is still needed");
        // Comment-stripped: this asserts the declaration EXISTS, and a commented-out line carrying the same
        // text would otherwise satisfy it while the input is gone — the failure mode the guard is for.
        String nativeCompile = SourceText.uncommentedFragment(namedTaskBlock(build, "nativeCompile"), false);
        assertFalse(nativeCompile.isBlank(), "cli/build.gradle must configure nativeCompile");
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

    /** Brace-balanced body of a {@code def <name> = { … }} closure, so a check cannot run past it into the next one. */
    private static String closureBody(String build, String name) {
        int start = build.indexOf("def " + name);
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
