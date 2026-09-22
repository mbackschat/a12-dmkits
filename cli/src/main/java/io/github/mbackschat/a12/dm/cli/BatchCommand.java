package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
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
 * The {@code batch} verb (CLI-SPEC §7) — run a sequence of verb invocations in ONE process, amortizing
 * JVM startup + the kernel's one-time StringTemplate warm-up across all ops (the N6 runner enabler).
 * Reads a JSON array of ops {@code {"verb","args":[…],"id"?}}, dispatches each through {@link Cli#run}
 * (the same seam as a standalone call), and emits self-describing per-op results in an aggregate envelope.
 * A batch-level error (missing/malformed file) exits 2. An ordinary fully dispatched batch exits 0 iff every child
 * did. Observation mode exits 0 iff every child was dispatched and its artifact was written, while retaining every
 * child's exact exit code and the aggregate under {@code data.allExitedZero}. Dispatch is non-transactional and
 * never rolls writes back.
 * A batch-level {@code -m} is the <b>default model</b> for ops that omit their own (G18 — the universal
 * {@code -m} convention); an op carrying its own {@code -m} keeps it.
 *
 * <p><b>A nonzero child exit is not necessarily a failed child</b>, and the aggregate says which it was rather
 * than guessing: a verdict verb ({@code rule check}, {@code model check}, …) exits 1 when its verdict is
 * negative while reporting {@code ok:true}, as does a comparison that differs; a kernel-gated candidate the
 * kernel refuses ({@code computation add --dry-run}) exits 1 with {@code ok:false} but publishes the verdict it
 * was arbitrated by ({@code valid:false} plus {@code verification}). All of them RAN. The summary's breakdown is
 * derived from each child's own envelope — see {@code ResultEnvelope.ChildDisposition} for why neither the exit
 * code nor {@code ok} alone can classify them.
 */
@Command(name = "batch", mixinStandardHelpOptions = true,
        description = "Dispatch independent verb invocations in one process (non-transactional; never rolls back). "
                + "Ordinary mode exits 0 iff every child did; observation mode exits 0 after full dispatch and "
                + "artifact write. A nonzero child is NOT always a failed child: a verdict verb "
                + "(rule/model/workspace check) exits 1 on a negative verdict and a comparison exits 1 when it "
                + "differs, both with ok:true, while a kernel-gated candidate the kernel refuses exits 1 with "
                + "ok:false AND valid:false — so classify a child by what its envelope established (`valid`, "
                + "`data.matches`), and read `ok` as whether the operation itself succeeded. A child that failed "
                + "to run carries no `valid` at all. The aggregate summary reports that split.")
final class BatchCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<ops.json>",
            description = "a JSON array of ops [{\"verb\",\"args\":[…argv…],\"id\"?}, …] — each op's args are that "
                    + "verb's CLI argv. See `dmtool schema batch` for the frame + worked examples. (A batch-level "
                    + "-m is the default model for ops that omit their own.)"
                    + JsonInput.HELP)
    String opsFile;

    @Option(names = "--observations", paramLabel = "<file>",
            description = "overwrite a deterministic static-kernel verdict artifact for caller-tagged rule checks "
                    + "and computation-spec dry-runs (see `dmtool schema batch`). Observation mode exits 0 after "
                    + "full dispatch and artifact write, even when a captured child exits nonzero; "
                    + "`data.allExitedZero` retains that independent aggregate. A preflight refusal is "
                    + "`outcome:rejected` and writes nothing; an artifact write failure is `outcome:error`, exit "
                    + "70. Per child, `valid:false` is a correctly rejected candidate whichever way `ok` reads "
                    + "(a rule-check verdict rejects with ok:true, a kernel-gated computation dry-run with "
                    + "ok:false); a child that failed to run has `ok:false` and NO `valid`")
    String observationsFile;

    @Override
    public Integer call() {
        JsonNode ops;
        try {
            ops = MAPPER.readTree(JsonInput.read(opsFile, "<ops.json>"));
        } catch (IOException e) {
            return refuseUnreadableInput(ResultIdentity.BATCH_RUN, "<ops.json>", opsFile, e);
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
        // A verb that names no command is wrong for EVERY child, so it is answered once, before child zero.
        // Reporting it per child hid it: a batch written with a wrong verb fails uniformly, controls
        // included, which reads as a route problem rather than a spelling one — and dispatch never rolls
        // back, so the preflight is also the last point at which nothing has been written.
        for (int index = 0; index < ops.size(); index++) {
            String verb = ops.get(index).path("verb").asText("");
            if (!verb.isBlank() && !CommandContracts.namesACommand(verbTokens(verb))) {
                return refusePrecheck(ResultIdentity.BATCH_RUN, RkCode.UNKNOWN_ARG,
                        "batch item " + index + "'s verb '" + verb + "' names no command",
                        "/" + index,
                        "pass a verb the manifest lists — either spelling works, `\"model check\"` or "
                                + "`\"model\"` with `check` as the first args entry (`dmtool manifest`)");
            }
        }
        String batchModel = model();   // G18: the batch-level -m is the default for ops that omit their own
        BatchObservationCapture.Plan observationPlan = null;
        if (observationsFile != null && JsonInput.isStdin(opsFile)) {
            // The artifact commits to the EXACT authored bytes of every candidate, which capture re-reads and
            // hashes. A stream cannot serve that: it is consumed, unnamed and unre-readable. Refused here rather
            // than downstream because coercing "-" into a Path fabricates <cwd>/- and puts it in the
            // overwrite-protection set — falsely protecting a real file of that name while leaving the actual
            // ops source unprotected (G60).
            return rejectObservationInput(new BatchObservationCapture.Rejection(-1,
                    "observation capture needs the ops as a file, and these arrived on stdin",
                    "write the ops to a file and pass its path; the observation artifact records the exact "
                            + "authored bytes, which a consumed stream cannot provide"));
        }
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
                // ENVIRONMENT, not INTERNAL: this used to carry RK_INTERNAL_ERROR — "a dmtool defect, report
                // it" — beside a fix telling the caller to pick a better path. Both cannot be true, and the
                // path is the caller's. The family and exit code are deliberately unchanged: every child ran
                // and wrote, so this is a partial success, not the "nothing happened" shape a refusal carries.
                Diagnostic diagnostic = Diagnostic.environmentFailure(
                        java.util.Optional.of(RkCode.OUTPUT_UNWRITABLE.code()),
                        "cannot write the observations artifact: " + e.getMessage(),
                        new Diagnostic.Where((String) null, java.util.Optional.empty()),
                        java.util.Optional.of(RkCode.OUTPUT_UNWRITABLE.fix()));
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
        result.put("verb", verb);   // the caller's own spelling, echoed verbatim so a correlation still matches
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
        List<String> verbTokens = verbTokens(verb);
        if ("batch".equals(verbTokens.get(0))) {
            return opError(result, "batch cannot nest");
        }

        List<String> argv = new ArrayList<>(verbTokens);
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
        // With a multi-token verb that means after the whole verb. Position is not load-bearing here — `-m`
        // is an INHERIT-scope root option, so `model -m X check` parses too (measured) — but keeping the
        // inherited option out of the middle of the verb preserves this line's original intent exactly.
        if (!opHasModel && batchModel != null) {
            argv.add(verbTokens.size(), batchModel);
            argv.add(verbTokens.size(), "-m");
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

    /**
     * The argv tokens a {@code verb} names. The manifest spells a two-word verb as ONE string
     * ({@code "model check"}) and this frame's {@code verb} is that name, so it is split on whitespace
     * rather than passed through as a single token — which is what used to fail every child of a batch
     * written from the manifest with {@code Unmatched argument at index 0}. The two spellings cannot
     * collide, since no single-token verb contains a space.
     */
    private static List<String> verbTokens(String verb) {
        return List.of(verb.strip().split("\\s+"));
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
