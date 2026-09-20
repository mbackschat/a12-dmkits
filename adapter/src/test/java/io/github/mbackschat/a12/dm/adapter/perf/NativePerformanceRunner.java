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
 *
 * <p><b>Each sample carries how busy the machine was, and the quantity is NOT the sibling lanes' foreign demand
 * (IF330, measured 2026-09-20).</b> The child's own CPU time is unobtainable here — {@code
 * ProcessHandle.Info.totalCpuDuration()} is empty on darwin while the child runs, immediately before it exits,
 * and after — so the only figure the parent can form is system utilisation minus the <em>parent's</em> own share,
 * which is demand attributable to neither and therefore <em>includes the child's work</em>. Hence its own name,
 * its own floor, and its own measured basis; borrowing the sibling constant would be a claim about a different
 * quantity. See {@link #MAX_UNATTRIBUTED_CPU_UTILISATION}.
 *
 * <p><b>The same arithmetic, a different meaning — which is why the names differ.</b> This lane reads
 * {@code PerfMachineDemand.Reading.getForeign()}, so the formula is identical to the sibling lanes'; what changes
 * is who "this process" is. In {@code perfGateTest} the measuring process is the one doing the work, so the
 * remainder really is foreign. Here the measuring process is a parent waiting on a child, so the remainder
 * contains the treatment. Reusing the reading is correct; reusing its NAME would have been the scope-transfer
 * error of assuming a proved quantity keeps its meaning on a new carrier.
 *
 * <p><b>This lane reports rather than gates, deliberately, because it has no bar to protect.</b> The report is
 * {@code informativeOnly}, so there is no verdict a contended sample could corrupt — what a contended sample
 * corrupts is the report's value as evidence. So the reading rides every sample, the summary says how many
 * samples produced one and how many were contended, and a contended run prints a loud line. Nothing is dropped:
 * a contended record is evidence of the condition that produced it, the same reason the interpreter protocol
 * refuses at aggregation instead of at the write.
 */
public final class NativePerformanceRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The unattributed-utilisation level above which a native sample is called contended.
     *
     * <p><b>Measured on the real binary, 2026-09-20</b> (darwin-aarch64, 8 cores, 8 samples per arm, applied load
     * the only variable, with a return control): unloaded arms read 0.257–0.327 and 0.184–0.412, four busy
     * spinners read 0.883–0.928, sixteen read 0.993–0.998. The floor sits inside that gap — 70 % above the worst
     * quiet reading and 21 % under the best contaminated one. Wall time moved 77.9 ms → 101.5 ms across the same
     * arms, which is the contamination this is meant to make visible.
     *
     * <p>It equals {@link PerfMachineDemand#MAX_FOREIGN_CPU_UTILISATION} today and is deliberately a separate
     * constant: the two judge different quantities on different lanes and were derived from different arms, so
     * re-deriving one must not silently move the other.
     */
    static final double MAX_UNATTRIBUTED_CPU_UTILISATION = 0.70;

    private NativePerformanceRunner() {
    }

    /**
     * One timed sample: its parent-clock wall time, and the machine's unattributed load over that window.
     *
     * <p>{@code unattributed} is {@code null} where the sampler produced nothing usable, which on this lane is
     * the COMMON case rather than an edge one — measured at 3 to 6 of every 8 samples, because the parent JVM
     * spends the window blocked on the child and the accessor's own window is correspondingly short. A verdict
     * computed from two readings is weak evidence, so the report states the count instead of hiding it.
     */
    record Sample(double elapsedMillis, Double unattributed) {
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
        // Primes both load deltas so the first sample's window starts here rather than at JVM start, which would
        // otherwise average the whole warm-up into it.
        PerfMachineDemand.INSTANCE.reading();
        var timings = new ArrayList<Double>(samples);
        var unattributed = new ArrayList<Double>(samples);
        for (var index = 0; index < samples; index++) {
            var sample = runOnce(command, true);
            timings.add(sample.elapsedMillis());
            unattributed.add(sample.unattributed());
        }
        var sorted = new ArrayList<>(timings);
        Collections.sort(sorted);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("medianMillis", sorted.get(sorted.size() / 2));
        summary.put("nearestRankP95Millis", sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1));
        summary.putAll(contentionSummary(unattributed));

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
        report.put("unattributedCpuUtilisation", unattributed);
        return report;
    }

    /**
     * The contention verdict over the samples that actually reported, plus how many that was.
     *
     * <p><b>The count is part of the verdict, not metadata beside it.</b> Most samples on this lane produce no
     * reading, so {@code contendedSamples=1} can mean one of one or one of eight, and those are different claims.
     * A reader — and this is a report a human reads, the lane being informative — has to be able to tell them
     * apart, so {@code demandReadings} travels with it and {@code contended} is stated only on a MAJORITY of the
     * readings rather than on any single one, which a burst of foreign load could trip on its own.
     *
     * <p>With no readings at all, {@code contended} is {@code false} and {@code demandReadings} is {@code 0}: an
     * absent reading is not evidence of contention, and the pair says plainly that nothing was observed rather
     * than asserting a quiet machine.
     */
    static Map<String, Object> contentionSummary(List<Double> unattributed) {
        var readings = unattributed.stream().filter(java.util.Objects::nonNull).toList();
        var contended = readings.stream().filter(value -> value > MAX_UNATTRIBUTED_CPU_UTILISATION).count();
        var verdict = new LinkedHashMap<String, Object>();
        verdict.put("demandReadings", readings.size());
        verdict.put("contendedSamples", contended);
        verdict.put("contended", !readings.isEmpty() && contended * 2 > readings.size());
        if (Boolean.TRUE.equals(verdict.get("contended"))) {
            System.out.printf(
                    "[performance][FLAG] native samples were taken while this machine was busy: %d of %d readings "
                    + "above %.0f%% unattributed CPU. These timings are the machine's, not the binary's — "
                    + "re-measure on an idle machine before comparing them.%n",
                    contended, readings.size(), MAX_UNATTRIBUTED_CPU_UTILISATION * 100);
        }
        return verdict;
    }

    private static Sample runOnce(List<String> command, boolean timed) throws Exception {
        var started = System.nanoTime();
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exit = process.waitFor();
        var ended = System.nanoTime();
        // Read after waitFor, so the window closed here is the one that contained the child's whole run. Routed
        // through PerfMachineDemand because both accessors delta against their own previous call, and a second
        // call site would shorten this window invisibly.
        var demand = PerfMachineDemand.INSTANCE.reading().getForeign();
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
            return new Sample(0.0, null);
        }
        var elapsed = (ended - started) / 1_000_000.0;
        if (!Double.isFinite(elapsed) || elapsed <= 0.0) {
            throw new IllegalStateException("invalid native timing " + elapsed);
        }
        return new Sample(elapsed, demand);
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
