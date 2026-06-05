package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.util.Arrays;

/**
 * The standalone CLI (proposal N10 / docs/CLI-SPEC.md) — the modeller/BA front-end over the engine.
 *
 * <p>{@link #run} is deliberately <b>pure and testable</b>: it returns an exit code and writes to the
 * passed sinks (no {@code System.exit}, no static streams), so every verb is a sub-second JUnit test
 * with no packaging and no spawned process (CLI-SPEC §3 — the fast inner loop). {@link CliMain} is the
 * thin shell that wires it to the process.
 */
public final class Cli {

    private Cli() {
    }

    /** Dispatch a verb. Returns a process exit code (0 = success). */
    public static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: rulekit <verb> [args]"
                    + "   (verbs: dump, describe, validate, check, add, ingest, explain, diff, batch, schema)");
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "dump" -> DumpCommand.run(rest, out, err);
            case "describe" -> DescribeCommand.run(rest, out, err);
            case "validate" -> ValidateCommand.run(rest, out, err);
            case "check" -> CheckCommand.run(rest, out, err);
            case "add" -> AddCommand.run(rest, out, err);
            case "ingest" -> IngestCommand.run(rest, out, err);
            case "explain" -> ExplainCommand.run(rest, out, err);
            case "diff" -> DiffCommand.run(rest, out, err);
            case "batch" -> BatchCommand.run(rest, out, err);
            case "schema" -> SchemaCommand.run(rest, out, err);
            default -> fail(err, "unknown verb: " + args[0]
                    + "   (verbs: dump, describe, validate, check, add, ingest, explain, diff, batch, schema)");
        };
    }

    private static int fail(Appendable err, String message) {
        try {
            err.append(message).append('\n');
        } catch (IOException ignored) {
            // best-effort diagnostic
        }
        return 2;
    }
}
