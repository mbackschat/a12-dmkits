package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * The {@code batch} verb (CLI-SPEC §4) — run a sequence of verb invocations in ONE process, amortizing
 * JVM startup + the kernel's one-time StringTemplate warm-up across all ops (the N6 runner enabler).
 * Reads a JSON array of ops {@code {"verb","args":[…],"id"?}}, dispatches each through {@link Cli#run}
 * (the same seam as a standalone call), and emits a JSON array of self-describing per-op results.
 * A batch-level error (missing/malformed file) exits 2; otherwise exit 0 with each op's own exit code.
 */
@Command(name = "batch", mixinStandardHelpOptions = true,
        description = "Run a JSON array of verb invocations in one process (amortizes JVM + kernel warm-up).")
final class BatchCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of ops: [{\"verb\":\"rule\",\"args\":[\"check\",\"-m\",\"model.json\", …],\"id\"?}, …]")
    String opsFile;

    @Override
    public Integer call() {
        JsonNode ops;
        try {
            ops = MAPPER.readTree(Files.readString(Path.of(opsFile)));
        } catch (IOException e) {
            return fail("cannot read ops file: " + e.getMessage());
        }
        if (!ops.isArray()) {
            return fail("ops file must be a JSON array");
        }
        ArrayNode results = MAPPER.createArrayNode();
        int index = 0;
        for (JsonNode op : ops) {
            results.add(runOp(op, index++));
        }
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results) + "\n");
            return 0;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return fail("cannot write batch results: " + e.getMessage());
        }
    }

    private static ObjectNode runOp(JsonNode op, int index) {
        // Every result is SELF-DESCRIBING so a diagnostic can never be mis-attributed: the explicit
        // `op` index, the caller's optional `id`, the `verb`, and the echoed `args`.
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
            // cross-type corrective: an `apply` op-record ({target,op,…}) was fed to `batch`
            if (op.has("target") || op.has("op")) {
                return opError(result, "op has target/op — it is an `apply` op-record, not a `batch` op; "
                        + "use the `apply` verb (batch ops are {verb,args})");
            }
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
}
