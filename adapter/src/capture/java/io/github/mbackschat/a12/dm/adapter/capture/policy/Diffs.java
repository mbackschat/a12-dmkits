package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.OutputDirs;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The deterministic STRUCTURAL diffs (proposal §7): {@code diff-packets} distinguishes input/provenance
 * drift from each runner's observation drift; {@code diff-reports} distinguishes policy/profile/verifier
 * drift from qualification-result drift. Both read receipt-consistent directories, write only into a new
 * report directory, and never touch their inputs.
 */
public final class Diffs {

    /** A written diff: its receipt digest and whether the two inputs were identical. */
    public record Written(String reportReceiptSha256, boolean identical) {
    }

    private Diffs() {
    }

    public static Written diffPackets(Path before, Path after, ProjectionId projection, Path outputDir,
            Path repoRoot) {
        Json.Obj diff = diffDirs(before, after, Diffs::packetDriftClass);
        diff.put("projection", projection.wire());
        return write(SchemaIds.PACKET_DIFF_REPORT, diff, outputDir, repoRoot);
    }

    public static Written diffReports(Path before, Path after, Path outputDir, Path repoRoot) {
        return write(SchemaIds.REPORT_DIFF_REPORT, diffDirs(before, after, Diffs::reportDriftClass),
                outputDir, repoRoot);
    }

    private static Json.Obj diffDirs(Path before, Path after,
            java.util.function.Function<String, String> classifier) {
        Map<String, Receipt.Artifact> beforeArtifacts = consistentInventory(before);
        Map<String, Receipt.Artifact> afterArtifacts = consistentInventory(after);

        Json.Arr drift = Json.Arr.of();
        boolean identical = true;
        TreeSet<String> paths = new TreeSet<>(beforeArtifacts.keySet());
        paths.addAll(afterArtifacts.keySet());
        for (String path : paths) {
            Receipt.Artifact a = beforeArtifacts.get(path);
            Receipt.Artifact b = afterArtifacts.get(path);
            String kind = a == null ? "added" : b == null ? "removed"
                    : a.sha256().equals(b.sha256()) ? null : "changed";
            if (kind == null) {
                continue;
            }
            identical = false;
            Json.Obj entry = Json.Obj.of()
                    .put("artifact", path)
                    .put("kind", kind)
                    .put("class", classifier.apply(path));
            if (path.startsWith("observations/")) {
                String[] segments = path.split("/");
                entry.put("caseId", segments[1]);
                entry.put("runner", segments[2].replace(".json", ""));
            }
            drift.add(entry);
        }
        Json.Obj diff = Json.Obj.of();   // schema member is prepended by write()
        diff.put("before", Json.Obj.of().put("receiptSha256", receiptSha(before)));
        diff.put("after", Json.Obj.of().put("receiptSha256", receiptSha(after)));
        diff.put("identical", identical);
        diff.put("drift", drift);
        return diff;
    }

    /** Verify the directory's stored receipt matches its live inventory, then index it by path. */
    private static Map<String, Receipt.Artifact> consistentInventory(Path dir) {
        Receipt stored = Receipt.fromJson(Json.parse(read(dir.resolve(Receipt.FILE_NAME))));
        if (!stored.equals(Receipt.over(dir))) {
            throw new CaptureRefusedException("diff input " + dir + " drifted from its own RECEIPT.json —"
                    + " refusing to diff an inconsistent directory");
        }
        Map<String, Receipt.Artifact> byPath = new LinkedHashMap<>();
        stored.artifacts().forEach(a -> byPath.put(a.path(), a));
        return byPath;
    }

    private static String packetDriftClass(String path) {
        if (path.startsWith("request/") || path.startsWith("inputs/")) {
            return "input";
        }
        if (path.startsWith("legality/")) {
            return "legality";
        }
        if (path.startsWith("observations/")) {
            return "observation";
        }
        return "provenance";
    }

    private static String reportDriftClass(String path) {
        return path.equals("PROFILE.json") ? "policy-profile" : "qualification-result";
    }

    private static Written write(String schema, Json.Obj diffBody, Path outputDir, Path repoRoot) {
        OutputDirs.requireNewOrEmptyOutsideCorpus(outputDir, repoRoot);
        Json.Obj root = Json.Obj.of().put("schema", schema);
        diffBody.members().forEach(root::put);
        byte[] bytes = Json.canonicalBytes(root);
        try {
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve("DIFF.json"), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the diff report", e);
        }
        String receiptSha = Receipt.over(outputDir).write(outputDir);
        boolean identical = ((Json.Bool) root.members().get("identical")).value();
        return new Written(receiptSha, identical);
    }

    private static String receiptSha(Path dir) {
        return Digests.sha256(read(dir.resolve(Receipt.FILE_NAME)));
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
