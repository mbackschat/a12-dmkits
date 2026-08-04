package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelFormat;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;

/**
 * The release's version surface (CLI-SPEC §1b) — what this {@code dmtool} build <em>is</em> and what it was
 * built/verified against, for {@code --version} (human) and {@code manifest.version} (machine-readable, so a
 * cold agent can read it). Seven axes, each from its single source of truth:
 *
 * <ul>
 *   <li><b>rulekit</b> — this release, baked into {@code version.properties} by the build.</li>
 *   <li><b>source</b> — the full Git revision plus whether tracked or untracked working-tree content differed
 *       when the artifact was built; baked into the same resource so a developer {@code installDist} build is
 *       citable even between commits.</li>
 *   <li><b>kernel built-against</b> — the kernel pin this release compiled against ({@code build.gradle}, via
 *       the same properties resource).</li>
 *   <li><b>A12 distribution</b> — the human-facing A12 Tools release label the built-against kernel ships in
 *       ({@code build.gradle} {@code a12Distribution}); the kernel semver is the machine compatibility key,
 *       this is its "A12 Tools &lt;quarter&gt;" tag (RELEASE-SPEC).</li>
 *   <li><b>kernel runtime</b> — the kernel jar actually on the classpath ({@link KernelVersion#runtimeVersion()});
 *       may be <em>newer</em> than built-against — the F11 tolerance posture (a newer kernel is a superset).</li>
 *   <li><b>catalog verified-against</b> — the kernel the operator catalog was verified against
 *       ({@link OperatorCatalog#verifiedAgainst()}, the {@code operators.json} floor).</li>
 *   <li><b>model version</b> — the document-model schema version this release authors/expects
 *       ({@link ModelFormat#VERSION}); the reference a loaded model's {@code header.modelVersion}
 *       is compared against (the §8c load-time skew check).</li>
 * </ul>
 */
record VersionInfo(String rulekit, String sourceRevision, SourceState sourceState, String kernelBuiltAgainst,
        String a12Distribution, String kernelRuntime, String catalogVerifiedAgainst, String modelVersion) {

    enum SourceState {
        CLEAN,
        DIRTY,
        UNKNOWN;

        static SourceState from(String value) {
            return switch (value) {
                case "CLEAN" -> CLEAN;
                case "DIRTY" -> DIRTY;
                default -> UNKNOWN;
            };
        }

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final String UNKNOWN = "(unknown)";

    /** Compose the live version surface from the baked resource + the runtime kernel + the catalog floor. */
    static VersionInfo current() {
        Properties p = new Properties();
        try (InputStream in = VersionInfo.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // the build always generates it; if absent (e.g. an odd classpath) we degrade to "(dev)"
        }
        return from(p);
    }

    /** Build the version surface from generated properties, keeping source revision/state as one invariant. */
    static VersionInfo from(Properties p) {
        String revision = p.getProperty("sourceRevision", UNKNOWN);
        SourceState state = SourceState.from(p.getProperty("sourceState", "UNKNOWN"));
        if (!revision.matches("[0-9a-f]{40}") || state == SourceState.UNKNOWN) {
            revision = UNKNOWN;
            state = SourceState.UNKNOWN;
        }
        return new VersionInfo(
                p.getProperty("rulekit", "(dev)"),
                revision,
                state,
                p.getProperty("kernelBuiltAgainst", UNKNOWN),
                p.getProperty("a12Distribution", UNKNOWN),
                Optional.ofNullable(KernelVersion.runtimeVersion()).orElse(UNKNOWN),
                Optional.ofNullable(OperatorCatalog.load().verifiedAgainst()).orElse(UNKNOWN),
                ModelFormat.VERSION);
    }

    /** True when the kernel on the classpath is known and differs from what the catalog was verified against. */
    boolean kernelSkewed() {
        return !UNKNOWN.equals(kernelRuntime) && !kernelRuntime.equals(catalogVerifiedAgainst);
    }

    /** Human-readable {@code --version} block. */
    String[] lines() {
        return new String[] {
                "a12-dmkits " + rulekit,
                "  source: " + sourceRevision + " (" + sourceState.label() + ")",
                "  kernel: " + kernelBuiltAgainst + " (built) / " + kernelRuntime + " (runtime)"
                        + (kernelSkewed() ? "  [skew — see RK_KERNEL_VERSION_SKEW]" : ""),
                "  A12 Tools distribution: " + a12Distribution,
                "  catalog verified against: " + catalogVerifiedAgainst,
                "  model version (reference): " + modelVersion,
        };
    }

    /** Machine-readable object for {@code manifest.version}. */
    ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("rulekit", rulekit);
        ObjectNode source = n.putObject("source");
        source.put("revision", sourceRevision);
        source.put("state", sourceState.name());
        ObjectNode k = n.putObject("kernel");
        k.put("builtAgainst", kernelBuiltAgainst);
        k.put("a12Distribution", a12Distribution);
        k.put("runtime", kernelRuntime);
        k.put("skewed", kernelSkewed());
        n.put("catalogVerifiedAgainst", catalogVerifiedAgainst);
        n.put("modelVersion", modelVersion);
        return n;
    }
}
