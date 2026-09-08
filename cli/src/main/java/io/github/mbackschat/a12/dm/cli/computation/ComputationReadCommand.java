package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation read <computationPath>} — read a computation back into rulekit's typed form.
 * A read op: the read-back rides {@code data} ({@code converted}+{@code calculatedField}, or {@code
 * converted:false}+{@code reason} for an Opaque construct — tolerant, never an error).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read a computation back into rulekit's typed form (tolerant of Opaque constructs).")
final class ComputationReadCommand extends BaseCommand {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp; "
                    + "omit to list every computation's full name (or address it with --computation)")
    String computation;

    @Option(names = "--computation", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — the named-flag alias for the positional (the locator "
                    + "contract: the target-noun flag always works). The positional wins if both are given.")
    String computationFlag;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (computation == null) {   // locator contract: --computation is the named alias for the positional
            computation = computationFlag;
        }
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            if (computation == null) {
                return readNameIndex("computations", adapter.computationFullNames(),
                        ConsistencyEvidence.of(adapter));
            }
            if (!adapter.computationFullNames().contains(computation)) {
                return refuseNotFound(RkCode.NO_SUCH_COMPUTATION,
                        "no computation at '" + computation + "'", computation,
                        "pass an existing computation's full name (list them with `computation read` — no arg); "
                                + "computations: " + adapter.computationFullNames());
            }
            // the SAME builder the apply-embedded COMPUTATION_READ op uses, so the two shapes cannot drift
            ObjectNode data = ReadPayloads.computation(adapter, computation);
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read " + computation,
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("read failed: ", e);
        }
    }
}
