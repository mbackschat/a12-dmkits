package io.github.mbackschat.a12.dm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>This git checkout, and the only ways to enumerate it.</b> A guard that scans the repository asks here rather
 * than walking the working directory, and takes its denominator from what <b>git tracks</b>.
 *
 * <p><b>Why git decides.</b> A walk of the working directory takes every tool's output as its input, and the set
 * of things that write into a checkout is open-ended: an IDE's compiled output and its copies of non-Java
 * sources, a coverage run, an editor's swap file, this project's own review worktrees. An exclusion list can only
 * grow, and each entry arrives as a guard failing on a file nobody wrote. The tracked set is exact, closed, and
 * is what CI asserts over, so a local run and a CI run scan the same thing.
 *
 * <p><b>Why the root never escapes.</b> There is deliberately no accessor returning the checkout's own
 * directory, so {@code Files.walk} of the repository root cannot be written against this type at all. A source
 * census that looked for that call instead was measured to miss three ordinary spellings of it — a root reached
 * through a method parameter, a renamed locator, a one-hop alias — which is why the boundary is expressed in the
 * type rather than detected in text. {@link #resolve} therefore rejects a relative path that would name the root
 * or climb out of it.
 *
 * <p><b>Why the denominators are named here and not per guard.</b> Each was previously spelled per class, and the
 * spellings disagreed: three guards meaning "the test sources" scanned 1571, 1295 and 1170 files, and the
 * narrowest matched none of the 273 tracked sources in the interpreter's Kotlin-Multiplatform test source sets.
 * A guard that wants something narrower than a named set filters it visibly at its own call site.
 */
public final class Checkout {

    /** Any test source set: {@code src/test}, and the KMP and dedicated-lane trees beside it. */
    private static final Pattern TEST_SOURCE_SET = Pattern.compile("(^|/)src/[A-Za-z]*[Tt]est[A-Za-z]*/");

    private static volatile List<Path> tracked;

    private final Path root;

    private Checkout(Path root) {
        this.root = root;
    }

    /**
     * The checkout containing the working directory. TWO markers decide it, not one: `settings.gradle` alone also
     * names a nested or sibling Gradle build, and a walk that stopped at the first one would hand a guard a
     * different tree than its neighbour got. `build.sh` is this repository's own entry point and is not something
     * a nested build carries.
     */
    public static Checkout locate() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("build.sh"))) {
                return new Checkout(directory);
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no directory with both settings.gradle and build.sh above " + Path.of("").toAbsolutePath());
    }

    /** Every tracked Java and Kotlin source, in every module and source set. */
    public List<Path> sources() {
        return named("sources", trackedFiles().stream().filter(Checkout::isJvmSource).toList());
    }

    /** Every tracked Java and Kotlin source in a test source set — the one definition of that set. */
    public List<Path> testSources() {
        return named("test sources", trackedFiles().stream()
                .filter(Checkout::isJvmSource)
                .filter(path -> TEST_SOURCE_SET.matcher(relative(path)).find())
                .toList());
    }

    /** Every tracked Markdown file. */
    public List<Path> markdown() {
        return named("markdown", trackedFiles().stream()
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .toList());
    }

    /**
     * Every tracked file, whatever its type. For a guard whose subject is the published set itself — scripts,
     * workflows, resources — rather than the JVM sources {@link #sources()} narrows to.
     */
    public List<Path> tracked() {
        return named("files", trackedFiles());
    }

    /**
     * Whether git tracks {@code path}. Answered from the same set every enumeration uses, read once per JVM, so a
     * file created after the first enumeration reads as untracked — which it is, until it is added.
     */
    public boolean isTracked(Path path) {
        return trackedFiles().contains(path.toAbsolutePath().normalize());
    }

    /** Every tracked file under {@code subdirectory} — the scoped shape, for a guard that means one subtree. */
    public List<Path> tracked(String subdirectory) {
        Path directory = resolve(subdirectory);
        return named("files under " + subdirectory,
                trackedFiles().stream().filter(path -> path.startsWith(directory)).toList());
    }

    /**
     * A path inside the checkout.
     *
     * @throws IllegalArgumentException if {@code relative} names the checkout root or climbs out of it, which
     *     would hand back the very path this type exists to withhold.
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank() || relative.equals(".")) {
            throw new IllegalArgumentException(
                    "resolve needs a path inside the checkout; the root itself is deliberately not reachable");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException(relative + " does not name a path inside the checkout");
        }
        ReadLog.record(relative(resolved));
        return resolved;
    }

    /**
     * Records which repo files a guard actually reads, so a task's DECLARED inputs can be checked against them
     * instead of kept in sync by hand.
     *
     * <p>The failure it catches is silent: a file a guard reads but nothing declares leaves its lane
     * UP-TO-DATE on an edit to that file, so the guard is green while checking nothing.
     *
     * <p>Writes one file per JVM (tests fork, and several forks may run in parallel), flushed by a shutdown
     * hook because the forked JVM exits before Gradle's {@code doLast} reads them.
     */
    /**
     * The system property the guard's Gradle task sets to say "record what you read here", and this JVM's
     * half of a two-build contract: the producer is `a12Guards.guardInputs` in the `build-logic` included
     * build, which cannot share a compilation unit with this source set. If the two names diverge, nothing
     * throws — {@link ReadLog#record} simply returns, every observation set is empty, and the undeclared-read
     * check passes because it saw nothing, which is indistinguishable from a real pass.
     * {@code BuildPerformancePolicyTest} holds the two names equal.
     */
    static final String OBSERVED_READS_PROPERTY = "a12.guard.observedReads";

    private static final class ReadLog {

        private static final String OUTPUT_PROPERTY = OBSERVED_READS_PROPERTY;
        private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
        private static final AtomicBoolean HOOKED = new AtomicBoolean();

        private ReadLog() {
        }

        static void record(String relative) {
            String dir = System.getProperty(OUTPUT_PROPERTY);
            if (dir == null || !SEEN.add(relative)) {
                return;                                   // not under observation, or already recorded
            }
            if (HOOKED.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> flush(dir)));
            }
        }

        private static void flush(String dir) {
            try {
                Path out = Path.of(dir).resolve("reads-" + ProcessHandle.current().pid() + ".txt");
                Files.createDirectories(out.getParent());
                Files.write(out, new java.util.TreeSet<>(SEEN));
            } catch (IOException writingIsBestEffort) {
                // A report that cannot be written must never fail the guard it is observing.
            }
        }
    }

    /**
     * A process to run with the checkout as its working directory — the reason the root itself need not be handed
     * out. Callers configure redirection and start it; the directory is already set.
     */
    public ProcessBuilder command(List<String> command) {
        return new ProcessBuilder(command).directory(root.toFile());
    }

    /**
     * A file this checkout must contain, resolved and asserted in one step.
     *
     * <p>The idiom this replaces probed {@code .}, {@code ..} and {@code ../..} for the first existing match, so a
     * file MISSING from the checkout resolved to a same-named file outside it instead of failing — silently, and
     * differently on every machine, depending on what sits beside the clone. Resolution here is anchored to the
     * checkout, so absence is absence.
     *
     * @throws IllegalArgumentException if {@code relative} would leave the checkout
     * @throws IllegalStateException if it names no regular file
     */
    Path requireFile(String relative) {
        Path resolved = resolve(relative);
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException(
                    relative + " is not a file in this checkout — looked at " + resolved);
        }
        return resolved;
    }

    /** {@code absolute} as a checkout-relative path, with forward slashes on every platform. */
    public String relative(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    /**
     * Refuses an empty answer. A denominator of nothing is how a broken predicate, a wrong working directory or
     * an unavailable git turns every scan green: "found no offender" and "read no file" are indistinguishable in
     * the assertion that follows.
     */
    private static List<Path> named(String what, List<Path> files) {
        if (files.isEmpty()) {
            throw new IllegalStateException("the checkout tracks no " + what + " — every scan over it is vacuous");
        }
        return files;
    }

    private static boolean isJvmSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private List<Path> trackedFiles() {
        List<Path> cached = tracked;
        if (cached == null) {
            cached = List.copyOf(readTrackedFiles(root));
            tracked = cached;
        }
        return cached;
    }

    private static List<Path> readTrackedFiles(Path root) {
        try {
            Process git = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = git.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git ls-files failed (" + exit + ") in " + root + ": " + out);
            }
            List<Path> files = new ArrayList<>();
            for (String name : out.split("\0")) {
                if (!name.isEmpty()) {
                    Path candidate = root.resolve(name);
                    // A tracked path can be absent from the worktree (a sparse checkout, a pending delete).
                    if (Files.isRegularFile(candidate)) {
                        files.add(candidate);
                    }
                }
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("git tracks no files under " + root + " — every scan is vacuous");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("could not ask git which files " + root + " tracks", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted asking git which files " + root + " tracks", e);
        }
    }
}
