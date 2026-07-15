package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata LEAK guard (proposal §2): GENERATED artifacts — envelope, legality, observations, receipts,
 * reports — must not contain an absolute local path, username, home directory, hostname, kernel class
 * name, or stack-trace frame. It runs at WRITE time on each generated artifact's bytes and refuses the
 * whole capture on a hit. Consumer-authored input bytes (the copied request + models) are NEVER scanned or
 * rewritten — their String values may legitimately resemble paths or hostnames; scenario review governs
 * those.
 */
public final class LeakGuard {

    private final Map<String, String> forbidden = new LinkedHashMap<>();

    /** The guard for a capture rooted at {@code repoRoot} writing to {@code outputDir}. */
    public LeakGuard(String repoRoot, String outputDir, String scenarioPath) {
        forbid(repoRoot, "the repository's absolute path");
        forbid(outputDir, "the output directory's absolute path");
        forbid(scenarioPath, "the scenario file's absolute path");
        forbid(System.getProperty("user.home"), "the user home directory");
        String userName = System.getProperty("user.name");
        if (userName != null && userName.length() > 2) {   // 1-2 char names would false-positive constantly
            forbid(userName, "the local username");
        }
        forbid(hostName(), "the local hostname");
        forbid("com.mgmtp.", "a kernel class/package name");
        forbid("\tat ", "a stack-trace frame");
    }

    private void forbid(String needle, String meaning) {
        if (needle != null && !needle.isBlank()) {
            forbidden.put(needle, meaning);
        }
    }

    private static String hostName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** The one sanctioned {@code com.mgmtp.} occurrence: the kernel's MAVEN COORDINATE (group:artifact),
     *  which the envelope must bind (proposal §3) — a coordinate is identity, not implementation detail;
     *  the class-name ban still catches any dotted {@code com.mgmtp.…} type reference. */
    private static final String ALLOWED_KERNEL_COORDINATE = "com.mgmtp.a12.kernel:";

    /** Check one GENERATED artifact's bytes; refuse loudly on any machine-specific or kernel leak. */
    public void checkGenerated(String artifactPath, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace(ALLOWED_KERNEL_COORDINATE, "");
        List<String> hits = new ArrayList<>();
        forbidden.forEach((needle, meaning) -> {
            if (text.contains(needle)) {
                hits.add(meaning);
            }
        });
        if (!hits.isEmpty()) {
            throw new IllegalStateException("metadata leak in generated artifact \"" + artifactPath + "\": "
                    + String.join(", ", hits) + " — generated provenance/diagnostic metadata must stay"
                    + " machine-neutral (copied consumer inputs are exempt and never scanned)");
        }
    }
}
