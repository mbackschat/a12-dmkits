package io.github.mbackschat.a12.dm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.rulekit.validate.KernelVersion;

/**
 * <b>The consult-only A12 kernel source checkout, accepted only when it is the release this build runs.</b> The
 * source-reading guardians (the grammar ledger, the MVK Java-raiser sweep, the codegen-tier denominator) are
 * oracles for the kernel on the classpath, so a checkout of another release makes them agree with the wrong
 * kernel and stay green.
 *
 * <p>That is not hypothetical. On the 31.1.1 → 31.3.1 upgrade (2026-11-06) the sibling {@code ../a12-kernel}
 * still had 31.1.1 checked out while {@code mavenLocal} already held 31.3.1, and nothing compared the two: every
 * one of these guardians would have read the old source and passed. The kernel tree states its release in its
 * root {@code package.json} ({@code "version": "31.3.1"} at the bootstrap-pinned commit), which is the marker
 * compared here against the running kernel's {@code Implementation-Version}.
 *
 * <p>An absent checkout still skips, as before: CI has none. A present one of the wrong release FAILS, because
 * skipping would hide exactly the drift this class exists to catch. Point {@code -Da12.kernel.dir} at a matching
 * tree, for instance a {@code git archive} of the release commit, rather than switching a sibling's branch.
 */
public final class KernelSourceCheckout {

    /** The system property that overrides the conventional sibling locations. */
    public static final String PROPERTY = "a12.kernel.dir";

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private KernelSourceCheckout() {
    }

    /** The checkout matching the running kernel, or empty when none is on this machine. */
    public static Optional<Path> locate() {
        Optional<Path> root = Stream.of(System.getProperty(PROPERTY), "../a12-kernel", "../../a12-kernel")
                .filter(candidate -> candidate != null)
                .map(Path::of)
                .filter(Files::isDirectory)
                .findFirst();
        root.ifPresent(dir -> requireRelease(dir, KernelVersion.runtimeVersion()));
        return root;
    }

    /** Refuses a checkout whose stated release is not {@code expected}, or that states none. */
    static void requireRelease(Path root, String expected) {
        String stated = statedRelease(root);
        if (expected == null || !expected.equals(stated)) {
            throw new AssertionError("the kernel source checkout at " + root.toAbsolutePath() + " is release "
                    + stated + " but this build runs kernel " + expected + "; a source oracle of another release "
                    + "agrees with the wrong kernel. Check out the matching release there, or pass -D" + PROPERTY
                    + "=<a tree of " + expected + ">");
        }
    }

    private static String statedRelease(Path root) {
        Path manifest = root.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return "(unknown: no package.json)";
        }
        try {
            Matcher matcher = VERSION.matcher(Files.readString(manifest));
            return matcher.find() ? matcher.group(1) : "(unknown: package.json names no version)";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
