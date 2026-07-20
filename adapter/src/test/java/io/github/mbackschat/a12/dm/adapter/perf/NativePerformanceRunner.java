package io.github.mbackschat.a12.dm.adapter.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parent-clock native first-answer runner: process launch through complete JSON envelope and exit. It warms
 * only the OS file cache with one discarded invocation; every recorded sample is a separate native process.
 */
public final class NativePerformanceRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NativePerformanceRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "usage: <binary> <model> <instance> <samples> <commit> <runner> <output>");
        }
        var binary = Path.of(args[0]).toAbsolutePath();
        var model = Path.of(args[1]).toAbsolutePath();
        var instance = Path.of(args[2]).toAbsolutePath();
        var samples = Integer.parseInt(args[3]);
        var commit = args[4];
        var runner = args[5];
        var output = Path.of(args[6]).toAbsolutePath();
        var report = measure(binary, model, instance, samples, commit, runner);
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    static Map<String, Object> measure(
            Path binary,
            Path model,
            Path instance,
            int samples,
            String commit,
            String runner) throws Exception {
        requireFile(binary, "native binary");
        requireFile(model, "model");
        requireFile(instance, "instance");
        if (!Files.isExecutable(binary)) {
            throw new IllegalArgumentException("native binary is not executable: " + binary);
        }
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be positive");
        }
        if (!commit.matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException("commit must be an 8–64 character lowercase hexadecimal identity");
        }
        if (runner.isBlank()) {
            throw new IllegalArgumentException("runner must not be blank");
        }

        var command = List.of(
                binary.toString(),
                "-m", model.toString(),
                "model", "eval",
                "--instance", instance.toString(),
                "--no-computations");
        runOnce(command, false);
        var timings = new ArrayList<Double>(samples);
        for (var index = 0; index < samples; index++) {
            timings.add(runOnce(command, true));
        }
        var sorted = new ArrayList<>(timings);
        Collections.sort(sorted);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("medianMillis", sorted.get(sorted.size() / 2));
        summary.put("nearestRankP95Millis", sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1));

        var definition = Files.readString(model) + "\n" + Files.readString(instance)
                + "\ncommand=model-eval-no-computations";
        var runtime = new LinkedHashMap<String, Object>();
        runtime.put("binary", binary.toString());
        runtime.put("binarySha256", sha256(Files.readAllBytes(binary)));
        runtime.put("os", System.getProperty("os.name"));
        runtime.put("arch", System.getProperty("os.arch"));

        var report = new LinkedHashMap<String, Object>();
        report.put("protocolVersion", 1);
        report.put("informativeOnly", true);
        report.put("commit", commit);
        report.put("runner", runner);
        report.put("scenario", "native-48-rules-500-rows-first-answer");
        report.put("scenarioVersion", 1);
        report.put("identityLabel", "native-48-rules-500-rows-first-answer@1");
        report.put("scenarioIdentity", sha256(definition.getBytes(StandardCharsets.UTF_8)));
        report.put("sampleCount", samples);
        report.put("runtime", runtime);
        report.put("summary", summary);
        report.put("samplesMillis", timings);
        return report;
    }

    private static double runOnce(List<String> command, boolean timed) throws Exception {
        var started = System.nanoTime();
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exit = process.waitFor();
        var ended = System.nanoTime();
        if (exit != 0) {
            throw new IllegalStateException("native performance command failed (" + exit + "): " + output);
        }
        JsonNode envelope;
        try {
            envelope = JSON.readTree(output);
        } catch (IOException failure) {
            throw new IllegalStateException("native performance command did not emit one JSON envelope: " + output, failure);
        }
        if (!envelope.path("ok").asBoolean(false)) {
            throw new IllegalStateException("native performance command returned a non-ok envelope: " + output);
        }
        var unsupported = envelope.path("data").path("unsupported");
        if (unsupported.isArray() && !unsupported.isEmpty()) {
            throw new IllegalStateException("native performance command reported unsupported semantics: " + unsupported);
        }
        var fired = envelope.path("data").path("fired");
        if (!fired.isArray() || !fired.isEmpty()) {
            throw new IllegalStateException("native low-firing fixture must return an empty fired array: " + fired);
        }
        if (!timed) {
            return 0.0;
        }
        var elapsed = (ended - started) / 1_000_000.0;
        if (!Double.isFinite(elapsed) || elapsed <= 0.0) {
            throw new IllegalStateException("invalid native timing " + elapsed);
        }
        return elapsed;
    }

    private static void requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " is missing: " + path);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        var hex = new StringBuilder(digest.length * 2);
        for (var value : digest) {
            hex.append("%02x".formatted(value & 0xff));
        }
        return hex.toString();
    }
}
