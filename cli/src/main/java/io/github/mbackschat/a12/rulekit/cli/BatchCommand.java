package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code batch} verb (CLI-SPEC §4) — run a sequence of verb invocations in ONE process. The kernel
 * pays a one-time cost on the first {@code checkConsistency} of a process (~497 StringTemplate classes
 * load, then cache for the JVM's life); spawning the CLI once per rule pays it every time. {@code batch}
 * runs the whole list in a single process so that warm-up — and JVM startup — is amortized across all
 * ops. This is the enabler for the N6 benchmark runner (many `check`s per task/tier).
 *
 * <p>Reads a JSON array of ops, each {@code {"verb": "...", "args": [...]}}, dispatches every op through
 * {@link Cli#run} (the same seam as a standalone call), and emits a JSON array of per-op results
 * {@code {verb, exit, result|stdout, stderr?}} — JSON stdout is embedded as {@code result}, other text
 * as {@code stdout}. Usage: {@code batch <ops.json>}. A batch-level error (missing/malformed file) exits
 * 2; otherwise exit 0 with each op's own exit code in the array, so one failing op never aborts the rest.
 */
final class BatchCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USAGE =
            "usage: batch <ops.json>   (ops: [{\"verb\":\"check\",\"args\":[\"model.json\", …]}, …])";

    private BatchCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length != 1) {
            return fail(err, USAGE);
        }
        JsonNode ops;
        try {
            ops = MAPPER.readTree(Files.readString(Path.of(args[0])));
        } catch (IOException e) {
            return fail(err, "cannot read ops file: " + e.getMessage());
        }
        if (!ops.isArray()) {
            return fail(err, "ops file must be a JSON array\n" + USAGE);
        }
        ArrayNode results = MAPPER.createArrayNode();
        int index = 0;
        for (JsonNode op : ops) {
            results.add(runOp(op, index++));
        }
        try {
            out.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results)).append('\n');
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot write batch results: " + e.getMessage());
        }
    }

    private static ObjectNode runOp(JsonNode op, int index) {
        // Every result is SELF-DESCRIBING so a diagnostic can never be mis-attributed: the explicit
        // `op` index, the caller's optional `id`, the `verb`, and the echoed `args` together say which
        // invocation produced the result — even if results are reordered or diagnostics are flattened
        // out of their wrapper. (Within a verb's own diagnostics the rule path may still be a synthetic
        // default like "CheckRule"; pass that verb's `--name` if you need it distinct there too.)
        ObjectNode result = MAPPER.createObjectNode();
        result.put("op", index);
        if (op.hasNonNull("id")) {
            result.put("id", op.get("id").asText());
        }
        String verb = op.path("verb").asText(null);
        result.put("verb", verb);
        JsonNode argsNode = op.path("args");
        if (argsNode.isArray()) {
            result.set("args", argsNode);
        }
        if (verb == null || verb.isBlank()) {
            return opError(result, "op is missing \"verb\"");
        }
        if ("batch".equals(verb)) {
            return opError(result, "batch cannot nest");
        }

        List<String> argv = new ArrayList<>();
        argv.add(verb);
        if (argsNode.isArray()) {
            for (JsonNode a : argsNode) {
                argv.add(a.asText());
            }
        }

        StringBuilder opOut = new StringBuilder();
        StringBuilder opErr = new StringBuilder();
        int exit;
        try {
            exit = Cli.run(argv.toArray(String[]::new), opOut, opErr);
        } catch (RuntimeException e) {
            // a verb that throws is still just a failed op — keep the batch going
            result.put("exit", 1);
            result.put("stderr", "op threw: " + e.getMessage());
            return result;
        }
        result.put("exit", exit);
        attachStdout(result, opOut.toString());
        if (!opErr.isEmpty()) {
            result.put("stderr", opErr.toString());
        }
        return result;
    }

    /** Embed stdout as parsed JSON when it is JSON (verbs emit JSON), else verbatim as a string. */
    private static void attachStdout(ObjectNode result, String stdout) {
        String trimmed = stdout.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            result.set("result", MAPPER.readTree(trimmed));
        } catch (IOException notJson) {
            result.put("stdout", stdout);
        }
    }

    private static ObjectNode opError(ObjectNode result, String message) {
        result.put("exit", 2);
        result.put("stderr", message);
        return result;
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
