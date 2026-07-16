package io.github.mbackschat.a12.dm.adapter.capture.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureOptions;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.ClosureIdentity;
import io.github.mbackschat.a12.dm.adapter.capture.packet.PacketWriter;
import io.github.mbackschat.a12.dm.adapter.capture.policy.Diffs;
import io.github.mbackschat.a12.dm.adapter.capture.policy.Verifier;
import io.github.mbackschat.a12.dm.adapter.capture.schema.Capabilities;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioRejectedException;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * The maintained capture COMMAND surface (proposal §7) — adapter-internal developer tooling, launched by
 * the {@code :adapter:capture*} Gradle tasks (never a dmtool verb). One entry ({@link #run}) serves the
 * Gradle launcher and the self-tests/mutation suite alike, so the tests drive the REAL command. Both
 * modes leave source bytes and visible status exactly as found; every refusal happens before anything is
 * written.
 */
public final class CaptureTool {

    /** The command environment — injectable so tests cover the source-state and closure-identity refusal
     *  branches deterministically; {@link #realEnv} is what the Gradle tasks run. */
    public record Env(PrintStream out, PrintStream err, ClosureIdentity.Probe probe, Path repoRoot) {
    }

    /** The closed command set (grows per shipped slice; unknown commands name the available set). */
    private enum Command {
        CAPABILITIES("capabilities"),
        VALIDATE_SCENARIOS("validate-scenarios"),
        CAPTURE("capture"),
        VERIFY("verify"),
        DIFF_PACKETS("diff-packets"),
        DIFF_REPORTS("diff-reports");

        private final String wire;

        Command(String wire) {
            this.wire = wire;
        }

        static Optional<Command> ofWire(String wire) {
            for (Command c : values()) {
                if (c.wire.equals(wire)) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }
    }

    private CaptureTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, realEnv()));
    }

    static Env realEnv() {
        Path repoRoot = repoRoot();
        return new Env(System.out, System.err, ClosureIdentity.probe(repoRoot), repoRoot);
    }

    private static Path repoRoot() {
        String property = System.getProperty("capture.repoRoot");
        if (property != null) {
            return Path.of(property);
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("no repository root found — set -Dcapture.repoRoot");
        }
        return dir;
    }

    /** The one real entry: exit 0 = done, 1 = rejected/refused (nothing mutated), 2 = usage. */
    public static int run(String[] args, Env env) {
        if (args.length == 0) {
            return usage(env, "no command given");
        }
        Optional<Command> command = Command.ofWire(args[0]);
        if (command.isEmpty()) {
            return usage(env, "unknown command \"" + args[0] + "\"");
        }
        Map<String, String> options;
        boolean development;
        try {
            List<String> flags = new ArrayList<>(List.of(args).subList(1, args.length));
            development = flags.remove("--development");
            options = parseOptions(flags);
        } catch (IllegalArgumentException e) {
            return usage(env, e.getMessage());
        }
        try {
            return switch (command.get()) {
                case CAPABILITIES -> capabilities(env);
                case VALIDATE_SCENARIOS -> validateScenarios(env, required(options, "--scenarios"));
                case CAPTURE -> capture(env, required(options, "--scenarios"), required(options, "--output"),
                        development);
                case VERIFY -> verify(env, options);
                case DIFF_PACKETS -> diffPackets(env, options);
                case DIFF_REPORTS -> diffReports(env, options);
            };
        } catch (IllegalArgumentException usage) {
            return usage(env, usage.getMessage());
        } catch (ScenarioRejectedException rejected) {
            env.err().println(rejected.getMessage());
            return 1;
        } catch (CaptureRefusedException refused) {
            env.err().println("capture refused: " + refused.getMessage());
            return 1;
        } catch (IllegalStateException guard) {
            // A write-time guard (metadata leak, closed receipt, kernel artifact resolution) — a refusal
            // with its corrective diagnostic, never a stack trace.
            env.err().println("capture refused: " + guard.getMessage());
            return 1;
        }
    }

    private static int capabilities(Env env) {
        env.out().print(new String(Json.canonicalBytes(Capabilities.emit()), StandardCharsets.UTF_8));
        return 0;
    }

    private static int validateScenarios(Env env, String scenarios) {
        ScenarioSets.Loaded loaded = ScenarioSets.load(Path.of(scenarios));
        env.out().println("scenario set ACCEPTED: " + loaded.set().scenarioSetId()
                + " v" + loaded.set().scenarioSetVersion()
                + " (" + loaded.set().cases().size() + " case(s), " + loaded.models().size() + " model(s))"
                + " — structural acceptance only; no engine ran, nothing was written");
        return 0;
    }

    private static int capture(Env env, String scenarios, String output, boolean development) {
        Path scenarioFile = Path.of(scenarios);
        ScenarioSets.Loaded loaded = ScenarioSets.load(scenarioFile);   // the SAME decoder validate-scenarios runs
        PacketWriter.Written written = PacketWriter.write(loaded, CaptureOptions.builder()
                .scenarioFile(scenarioFile)
                .outputDir(Path.of(output))
                .development(development)
                .identityProbe(env.probe())
                .repoRoot(env.repoRoot())
                .build());
        if (development) {
            env.out().println("DEVELOPMENT packet (dirty-source-eligible engineering output — permanently"
                    + " ineligible for every portable qualification policy)");
        }
        env.out().println("packet written: " + written.artifacts() + " artifact(s)");
        env.out().println("RECEIPT.json sha256: " + written.receiptSha256());
        return 0;
    }

    private static int verify(Env env, Map<String, String> options) {
        PolicyId policy = PolicyId.ofWire(required(options, "--policy"))
                .orElseThrow(() -> new IllegalArgumentException("unknown policy \"" + options.get("--policy")
                        + "\" — published: "
                        + java.util.Arrays.stream(PolicyId.values()).map(PolicyId::wire).toList()));
        Verifier.Verified verified = Verifier.verify(
                Path.of(required(options, "--packet")),
                required(options, "--packet-receipt-sha256"),
                policy,
                Path.of(required(options, "--output")),
                env.probe().resolve().source(),
                env.repoRoot());
        env.out().println("policy " + policy.wire() + ": " + verified.verdict().toUpperCase(java.util.Locale.ROOT));
        env.out().println("report RECEIPT.json sha256: " + verified.reportReceiptSha256());
        return 0;
    }

    private static int diffPackets(Env env, Map<String, String> options) {
        ProjectionId projection = ProjectionId.ofWire(required(options, "--projection"))
                .orElseThrow(() -> new IllegalArgumentException("unknown projection \""
                        + options.get("--projection") + "\" — published: "
                        + java.util.Arrays.stream(ProjectionId.values()).map(ProjectionId::wire).toList()));
        Diffs.Written written = Diffs.diffPackets(
                Path.of(required(options, "--before")),
                Path.of(required(options, "--after")),
                projection,
                Path.of(required(options, "--output")),
                env.repoRoot());
        env.out().println(written.identical() ? "packets are IDENTICAL" : "packets DIFFER — see DIFF.json");
        env.out().println("diff RECEIPT.json sha256: " + written.reportReceiptSha256());
        return 0;
    }

    private static int diffReports(Env env, Map<String, String> options) {
        Diffs.Written written = Diffs.diffReports(
                Path.of(required(options, "--before")),
                Path.of(required(options, "--after")),
                Path.of(required(options, "--output")),
                env.repoRoot());
        env.out().println(written.identical() ? "reports are IDENTICAL" : "reports DIFFER — see DIFF.json");
        env.out().println("diff RECEIPT.json sha256: " + written.reportReceiptSha256());
        return 0;
    }

    private static Map<String, String> parseOptions(List<String> flags) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < flags.size(); i += 2) {
            String key = flags.get(i);
            if (!key.startsWith("--") || i + 1 >= flags.size()) {
                throw new IllegalArgumentException("malformed option \"" + key + "\" — options are"
                        + " --name value pairs");
            }
            options.put(key, flags.get(i + 1));
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required option " + key);
        }
        return value;
    }

    private static int usage(Env env, String problem) {
        env.err().println("capture tool: " + problem);
        env.err().println("""
                usage:
                  capabilities
                  validate-scenarios --scenarios <file>
                  capture [--development] --scenarios <file> --output <new-or-empty-directory>
                  verify --packet <dir> --packet-receipt-sha256 <hex> --policy <policy-id> --output <new-dir>
                  diff-packets --before <packet> --after <packet> --projection <projection-id> --output <new-dir>
                  diff-reports --before <report> --after <report> --output <new-dir>
                (Gradle: ./build.sh :adapter:captureCapabilities | :adapter:captureValidateScenarios | \
                :adapter:capture | :adapter:captureVerify | :adapter:captureDiffPackets | \
                :adapter:captureDiffReports — options via -Pcapture.*; see corpus/README.md)""");
        return 2;
    }
}
