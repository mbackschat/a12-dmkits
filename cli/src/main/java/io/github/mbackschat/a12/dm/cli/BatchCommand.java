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
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code batch} verb (CLI-SPEC §4) — run a sequence of verb invocations in ONE process, amortizing
 * JVM startup + the kernel's one-time StringTemplate warm-up across all ops (the N6 runner enabler).
 * Reads a JSON array of ops {@code {"verb","args":[…],"id"?}}, dispatches each through {@link Cli#run}
 * (the same seam as a standalone call), and emits self-describing per-op results in an aggregate envelope.
 * A batch-level error (missing/malformed file) exits 2; a fully dispatched batch exits 0 iff every child did,
 * while retaining every child's exact exit code. Dispatch is non-transactional and never rolls writes back.
 * A batch-level {@code -m} is the <b>default model</b> for ops that omit their own (G18 — the universal
 * {@code -m} convention); an op carrying its own {@code -m} keeps it.
 *
 * <p><b>A nonzero child exit is not necessarily a failed child</b>, and the aggregate says which it was rather
 * than guessing: a verdict verb ({@code rule check}, {@code model check}, …) exits 1 when its verdict is
 * negative while reporting {@code ok:true}, as does a comparison that differs. Both RAN. The summary's
 * breakdown is derived from each child's own envelope — see {@code ResultEnvelope}'s nonzero-breakdown note for
 * why the exit code alone cannot classify them.
 */
@Command(name = "batch", mixinStandardHelpOptions = true,
        description = "Dispatch independent verb invocations in one process (non-transactional; never rolls back). "
                + "Exits 0 iff every child did. A nonzero child is NOT always a failed child: a verdict verb "
                + "(rule/model/workspace check) exits 1 on a negative verdict, and a comparison exits 1 when it "
                + "differs, both with ok:true — so read the child's own `ok` (did it run) beside `valid`/`matches` "
                + "(what it found). The aggregate summary reports that split.")
final class BatchCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of ops [{\"verb\",\"args\":[…argv…],\"id\"?}, …] — each op's args are that "
                    + "verb's CLI argv. See `dmtool schema batch` for the frame + worked examples. (A batch-level "
                    + "-m is the default model for ops that omit their own.)")
    String opsFile;

    @Option(names = "--observations", paramLabel = "<file>",
            description = "overwrite a deterministic static-kernel verdict artifact for caller-tagged rule checks "
                    + "and computation-spec dry-runs (see `dmtool schema batch`). EXIT: the aggregate rule above "
                    + "applies unchanged, and it bites here routinely — a candidate the kernel REJECTED is a "
                    + "nonzero child, so a capture containing an intentional negative control exits 1 by design, "
                    + "not because the capture failed. The artifact is still complete and authoritative: the "
                    + "capture succeeded exactly when the envelope reports `outcome:completed` with "
                    + "`written:true` (a preflight refusal is `outcome:rejected` and writes nothing; an artifact "
                    + "write failure is `outcome:error`, exit 70). Per child, the artifact keeps the two axes "
                    + "apart: `ok:true` with `valid:false` is a correctly rejected candidate, while `ok:false` is "
                    + "a child that failed to run")
    String observationsFile;

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
        for (int index = 0; index < ops.size(); index++) {
            JsonNode op = ops.get(index);
            if (!op.has("verb") && (op.has("target") || op.has("op"))) {
                return refusePrecheck(ResultIdentity.BATCH_RUN, RkCode.UNKNOWN_ARG,
                        "batch item " + index + " is an apply op-record, not a batch invocation",
                        "/" + index,
                        "use `dmtool apply` for records shaped as {target,op,…}; batch items are {verb,args,id?}");
            }
        }
        // Enforce the exact frame served by `schema batch` before invoking the first child. The former
        // path coerced wrong-typed id/verb values, silently treated a non-array args value as absent, and
        // ignored extra keys despite additionalProperties:false — schema and runtime contradicted each other.
        var frameRejection = rejectInvalidSpec(ResultIdentity.BATCH_RUN, "batch", ops);
        if (frameRejection.isPresent()) {
            return frameRejection.get();
        }
        String batchModel = model();   // G18: the batch-level -m is the default for ops that omit their own
        BatchObservationCapture.Plan observationPlan = null;
        if (observationsFile != null) {
            try {
                observationPlan = BatchObservationCapture.prepare(
                        ops, Path.of(opsFile), Path.of(observationsFile), batchModel);
            } catch (BatchObservationCapture.InvalidInputException invalid) {
                BatchObservationCapture.Rejection rejection = invalid.rejection();
                return rejectObservationInput(rejection);
            } catch (IOException e) {
                return rejectObservationInput(new BatchObservationCapture.Rejection(-1,
                        "cannot establish safe observation paths", e.getMessage()));
            }
        }
        ArrayNode results = MAPPER.createArrayNode();
        int index = 0;
        for (JsonNode op : ops) {
            results.add(runOp(op, index++, batchModel));
        }
        if (observationPlan != null) {
            try {
                observationPlan.write(results);
            } catch (IOException e) {
                Diagnostic diagnostic = Diagnostic.internal(java.util.Optional.of(RkCode.INTERNAL_ERROR.code()),
                        "cannot write the observations artifact: " + e.getMessage(),
                        new Diagnostic.Where((String) null, java.util.Optional.empty()),
                        java.util.Optional.of("choose a writable regular-file destination and rerun the batch"));
                ResultEnvelope envelope = ResultEnvelope.dispatchArtifactWriteFailed(
                        ResultIdentity.BATCH_RUN, "dispatched " + results.size() + " op"
                                + (results.size() == 1 ? "" : "s")
                                + ", but could not write the observations artifact",
                        results, List.of(diagnostic));
                out().print(envelope.toJsonString());
                return envelope.exitCode();
            }
        }
        // The standard envelope, with the items under `data.results`. It used to print a bare JSON ARRAY, which is
        // why `batch --text` emitted raw JSON: the renderer needs an object carrying a textual `outcome`, and an
        // array has neither. The items themselves are unchanged.
        String summary = "dispatched " + results.size() + " op" + (results.size() == 1 ? "" : "s");
        ResultEnvelope env = observationPlan == null
                ? ResultEnvelope.dispatchCompleted(ResultIdentity.BATCH_RUN, summary, results)
                : ResultEnvelope.dispatchCompletedWithArtifact(
                        ResultIdentity.BATCH_RUN, summary, results, observationPlan.output());
        out().print(env.toJsonString());
        return env.exitCode();
    }

    private int rejectObservationInput(BatchObservationCapture.Rejection rejection) {
        String path = rejection.index() < 0 ? "/" : "/" + rejection.index();
        Diagnostic diagnostic = Diagnostic.precheck(java.util.Optional.of(RkCode.INVALID_VALUE.code()),
                rejection.message(), Diagnostic.Where.path(path), java.util.Optional.of(rejection.fix()),
                java.util.Optional.empty());
        ResultEnvelope envelope = ResultEnvelope.rejected(ResultIdentity.BATCH_RUN,
                "the observation request is invalid", List.of(diagnostic), RejectionBasis.preflight());
        out().print(envelope.toJsonString());
        return envelope.exitCode();
    }

    private static ObjectNode runOp(JsonNode op, int index, String batchModel) {
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
                String arg = a.asText();
                argv.add(arg);
            }
        }
        boolean opHasModel = hasOwnModel(argv);
        // G18: an op that omits its own -m inherits the batch-level one (the universal -m default); an op
        // carrying its own -m keeps it. Put the inherited global option before the child command's arguments:
        // appending it would place it after a child's `--`, where picocli correctly treats it as a positional.
        if (!opHasModel && batchModel != null) {
            argv.add(1, batchModel);
            argv.add(1, "-m");
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

    private static boolean hasOwnModel(List<String> argv) {
        try {
            return CommandContracts.parsedParameters(argv).value("--model") != null;
        } catch (RuntimeException malformedChild) {
            return argv.stream().anyMatch(arg -> "-m".equals(arg) || "--model".equals(arg)
                    || arg.startsWith("-m=") || arg.startsWith("--model="));
        }
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
