package io.github.mbackschat.a12.dm.adapter.laws;

import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The kernel's generated model code, kept across runs so the differential lane recompiles only what changed.
 *
 * <p><b>What this caches, and what it must never cache.</b> It caches the kernel's own {@link IModelCode} — the
 * code the kernel GENERATES from a model — and nothing else. The kernel still evaluates every document live on
 * every run, so the lane stays an oracle. Caching the kernel's ANSWERS would turn that oracle into a golden file:
 * a recorded message set can go stale for reasons no practical key captures (JDK, timezone database, locale data,
 * iteration order), and the test would then pass against something the kernel no longer says. A slow lane is
 * strictly better than a silent false green in the oracle.
 *
 * <p><b>Why it is worth having (measured 2026-09-22, 20-rule model, this machine).</b> Generating and compiling
 * a model's code costs <b>1036 ms</b>; reviving the same {@link IModelCode} from disk and letting Groovy compile
 * it again costs <b>240 ms</b>; holding the live object costs <b>6 ms</b>. So the disk round trip removes ~77 %,
 * and the reason is that {@code ModelGroovyCode} serializes the generated SOURCE while marking the compiled
 * {@code GroovyObject} {@code transient} — generation persists, compilation repeats. The kernel designed for
 * this: {@link IModelCode} {@code extends Serializable} and carries {@link IModelCode#getModelHash()} "to
 * determine if the model has changed". It matters because Gradle re-runs the whole task when any adapter test
 * changes, so today one edit pays the full per-model generation sweep. Detail: BUILD-ENVIRONMENT.md,
 * "Why {@code :adapter:test} takes minutes".
 *
 * <p><b>The identity a cached entry is filed under is the thing that makes it safe.</b> An entry is keyed by the
 * kernel runtime version, the JDK, and the model JSON — all three, because generated code is a function of all
 * three and of nothing else the cache can see. A kernel bump or a JDK change therefore cannot hit a stale entry;
 * it simply misses. The model's own text is in the key rather than the kernel's {@code modelCodeIdentifier},
 * which is the kernel's internal naming and not something this side may assume is content-derived.
 *
 * <p><b>It fails open, always.</b> Any read problem — a truncated file, a class that no longer deserializes after
 * a dependency bump — is treated as a miss and the entry is removed, never propagated. A cache that can fail a
 * test is worse than no cache, and this one is allowed to be wrong only in the direction of doing more work.
 */
final class KernelModelCodeCache implements IModelCodeCache {

    /**
     * Opt-IN, so a lane that measures codegen cannot inherit the cache by forgetting to opt out.
     *
     * <p>The default is the unsafe direction to get wrong: a timing bar that silently reads a generation off disk
     * reports an improvement and is really an instrument change, and nothing mechanical would catch it. A lane
     * that merely runs slower because it did not opt in costs wall time and says nothing false. So `adapter`'s
     * ordinary `test` lane sets this, and every `perf`/`measurement` lane simply never does.
     */
    private static final String ENABLED_PROPERTY = "rk.kernelCodeCache";

    private static final String DIRECTORY_PROPERTY = "rk.kernelCodeCacheDir";

    /**
     * The {@code .noindex} suffix is load-bearing on macOS, and it was measured rather than assumed.
     *
     * <p>A populated cache is ~1800 files (1805, measured 2026-09-22), and Spotlight indexed them: {@code mdfind -onlyin} returned 134 of
     * them, with {@code corespotlightd} and {@code spotlightknowledged.updater} at 85 % and 106 % CPU while the
     * lane ran. That is a problem here specifically — this machine also runs the timing lanes, and CPU spent
     * indexing a test cache is CPU a measurement is competing with. macOS excludes any directory whose name ends
     * in {@code .noindex}, which costs nothing on other platforms.
     */
    private static final Path DEFAULT_DIRECTORY = Path.of("build/kernel-model-code.noindex");

    /**
     * Reused across engines within a JVM so a fork pays the disk round trip once per model rather than per engine.
     * {@link RuntimeLaws} already caches engines by model text, but bounded — beyond that bound an evicted model
     * would otherwise re-read from disk.
     */
    private static final Map<String, IModelCode> RESIDENT = new ConcurrentHashMap<>();

    /**
     * Forget what this JVM is holding, so the next lookup goes to disk.
     *
     * <p>For tests that exercise the DISK path specifically. Without it a second lookup in the same fork is served
     * from memory and never reads the file, which would make a test about unreadable entries pass vacuously.
     */
    static void clearResident() {
        RESIDENT.clear();
    }

    private final Path directory;
    private final String fingerprint;

    private KernelModelCodeCache(Path directory, String fingerprint) {
        this.directory = directory;
        this.fingerprint = fingerprint;
    }

    /**
     * The cache for one model, or the kernel's plain in-memory one when this is disabled.
     *
     * <p>Returning {@link RuntimeLaws#newModelCodeCache()} rather than a no-op keeps the disabled path exactly
     * what every engine had before this existed, so a lane that opts out is not merely uncached but unchanged.
     */
    static IModelCodeCache forModel(String modelJson) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
            return RuntimeLaws.newModelCodeCache();
        }
        String override = System.getProperty(DIRECTORY_PROPERTY);
        Path directory = override == null ? DEFAULT_DIRECTORY : Path.of(override);
        return new KernelModelCodeCache(directory, fingerprint(modelJson));
    }

    @Override
    public IModelCode getModelCode(String modelCodeIdentifier) {
        String key = fingerprint + '/' + modelCodeIdentifier;
        IModelCode resident = RESIDENT.get(key);
        if (resident != null) {
            return resident;
        }
        Path file = entry(modelCodeIdentifier);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        // Gzipped because the payload is generated Groovy SOURCE, which compresses hard: uncompressed, one full
        // :adapter:test run of this estate wrote 94 MB across 1805 fingerprint directories (2026-09-22), and
        // those bytes are read back on every later run.
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            IModelCode revived = (IModelCode) in.readObject();
            RESIDENT.put(key, revived);
            return revived;
        } catch (IOException | ClassNotFoundException | RuntimeException unreadable) {
            // A miss, deliberately silent: the kernel regenerates, and a stale or truncated entry that cannot be
            // read is exactly the case this must not turn into a test failure.
            discard(file);
            return null;
        }
    }

    @Override
    public void addModelCode(String modelCodeIdentifier, IModelCode modelCode) {
        RESIDENT.put(fingerprint + '/' + modelCodeIdentifier, modelCode);
        Path file = entry(modelCodeIdentifier);
        try {
            Files.createDirectories(file.getParent());
            // Written beside the target and moved, so a fork killed mid-write cannot leave a half-entry that the
            // next run reads as a hit. Four forks share this directory, so the temp name carries the thread id.
            Path staging = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            try (ObjectOutputStream out =
                    new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(staging)))) {
                out.writeObject(modelCode);
            }
            Files.move(staging, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException unwritable) {
            // Failing to cache is not failing: the run continues with the code the kernel just generated.
            discard(file);
        }
    }

    private Path entry(String modelCodeIdentifier) {
        return directory.resolve(fingerprint).resolve(fingerprint(modelCodeIdentifier) + ".ser.gz");
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Nothing to do and nothing at stake: the entry is already treated as absent.
        }
    }

    /**
     * The identity generated code is a function of: the kernel that generates it, the JDK that runs it, the model.
     *
     * <p>{@code KernelVersion.runtimeVersion()} reads the kernel jar actually on the classpath rather than the
     * compile-time pin, which is the version that did the generating and therefore the one an entry belongs to.
     */
    private static String fingerprint(String modelJson) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(String.valueOf(KernelVersion.runtimeVersion()).getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(System.getProperty("java.version", "").getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(modelJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new UncheckedIOException(new IOException("SHA-256 is required by the platform", impossible));
        }
    }
}
