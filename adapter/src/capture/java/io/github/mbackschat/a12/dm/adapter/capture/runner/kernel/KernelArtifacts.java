package io.github.mbackschat.a12.dm.adapter.capture.runner.kernel;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * The resolved kernel-closure artifacts bound into every packet (proposal §3; audit Finding 5).
 * {@link #resolve} names the BEHAVIOR anchor — the {@code kernel-md-runtime-service} jar both registered
 * routes execute through; {@link #resolveAll} enumerates EVERY semantics-affecting artifact on this JVM's
 * classpath (the kernel groups and the Groovy runtime the dynamic route executes through), each with its
 * exact bytes hashed — one coordinate is never assumed to cover the whole execution. Coordinates are
 * derived from the resolved jar file names (the executing classpath is the ground truth); no classpath
 * location (an absolute path) ever enters the packet.
 */
public final class KernelArtifacts {

    private static final String RUNTIME_SERVICE = "kernel-md-runtime-service";
    private static final Pattern JAR_VERSION = Pattern.compile(RUNTIME_SERVICE + "-([0-9][^/]*?)\\.jar$");

    /** {@code <artifactId>-<version>.jar} of the closure prefixes, filename-parsed. */
    private static final Pattern CLOSURE_JAR = Pattern.compile(
            "((?:kernel-|base-model-|mmtypings-|mmvalidators-)[a-zA-Z0-9_.-]*?|groovy(?:-[a-z]+)?)"
                    + "-([0-9][^/]*?)\\.jar$");

    /** A resolved artifact identity: Maven coordinate, version, and the artifact bytes' SHA-256. */
    public record Identity(String coordinate, String version, String sha256) {
    }

    private KernelArtifacts() {
    }

    /** The behavior anchor: the one runtime-service artifact both kernel routes execute through. */
    public static Identity resolve() {
        for (String entry : classpath()) {
            Matcher matcher = JAR_VERSION.matcher(entry.replace('\\', '/'));
            if (matcher.find()) {
                return identity(RUNTIME_SERVICE, matcher.group(1), Path.of(entry));
            }
        }
        throw new IllegalStateException("the " + RUNTIME_SERVICE + " artifact was not found on the classpath —"
                + " capture requires the kernel runtime it binds into the packet");
    }

    /** Every semantics-affecting closure artifact on the executing classpath, canonically sorted. */
    public static List<Identity> resolveAll() {
        List<Identity> artifacts = new ArrayList<>();
        for (String entry : classpath()) {
            String normalized = entry.replace('\\', '/');
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            Matcher matcher = CLOSURE_JAR.matcher(fileName);
            if (matcher.matches()) {
                artifacts.add(identity(matcher.group(1), matcher.group(2), Path.of(entry)));
            }
        }
        if (artifacts.stream().noneMatch(a -> a.coordinate().endsWith(":" + RUNTIME_SERVICE))) {
            throw new IllegalStateException("the kernel closure scan found no " + RUNTIME_SERVICE
                    + " artifact — capture requires the kernel runtime it binds into the packet");
        }
        artifacts.sort(Comparator.comparing(Identity::coordinate).thenComparing(Identity::version));
        return artifacts;
    }

    private static Identity identity(String artifactId, String version, Path jar) {
        try {
            byte[] bytes = Files.readAllBytes(jar);
            return new Identity(groupOf(artifactId) + ":" + artifactId, version, Digests.sha256(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to hash closure artifact " + artifactId, e);
        }
    }

    /** The published group of a closure artifact id (the filename carries only the artifact id). */
    private static String groupOf(String artifactId) {
        if (artifactId.startsWith("base-model-")) {
            return "com.mgmtp.a12.base";
        }
        if (artifactId.startsWith("groovy")) {
            return "org.codehaus.groovy";
        }
        return "com.mgmtp.a12.kernel";
    }

    private static String[] classpath() {
        return System.getProperty("java.class.path").split(File.pathSeparator);
    }
}
