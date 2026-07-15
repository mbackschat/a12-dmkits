package io.github.mbackschat.a12.dm.adapter.capture.runner.kernel;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * The resolved kernel BEHAVIOR artifact bound into every packet (proposal §3): the
 * {@code kernel-md-runtime-service} jar — the one implementation both registered routes execute through —
 * located on this JVM's classpath, with its exact bytes hashed. The coordinate + version + SHA-256 are
 * recorded; no classpath location (an absolute path) ever enters the packet.
 */
public final class KernelArtifacts {

    private static final String RUNTIME_SERVICE = "kernel-md-runtime-service";
    private static final Pattern JAR_VERSION = Pattern.compile(RUNTIME_SERVICE + "-([0-9][^/]*?)\\.jar$");

    /** The resolved kernel identity: Maven coordinate, version, and the artifact bytes' SHA-256. */
    public record Identity(String coordinate, String version, String sha256) {
    }

    private KernelArtifacts() {
    }

    public static Identity resolve() {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Matcher matcher = JAR_VERSION.matcher(entry.replace('\\', '/'));
            if (matcher.find()) {
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(entry));
                    return new Identity("com.mgmtp.a12.kernel:" + RUNTIME_SERVICE, matcher.group(1),
                            Digests.sha256(bytes));
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to hash the kernel runtime artifact", e);
                }
            }
        }
        throw new IllegalStateException("the " + RUNTIME_SERVICE + " artifact was not found on the classpath —"
                + " capture requires the kernel runtime it binds into the packet");
    }
}
