package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.cli.rule.RuleMessages;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (computation == null) {   // locator contract: --computation is the named alias for the positional
            computation = computationFlag;
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
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
            ReadComputation read = adapter.readComputation(computation);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("computation", computation);
            if (read instanceof ReadComputation.Converted converted) {
                data.put("converted", true);
                Computation comp = converted.computation();
                data.put("calculatedField", comp.computedField().path());
                // slot parity (probe 07): echo the FORMULA — the commonPrecondition + each alternative's
                // (precondition?, operation), rendered as DSL — so the computed value is confirmable through
                // `computation read` alone, not only via `computation format`.
                DraftComputation draft = comp.draft();
                if (draft.commonPrecondition() != null) {
                    data.put("commonPrecondition", draft.commonPrecondition());
                }
                ArrayNode alternatives = data.putArray("alternatives");
                for (DraftComputation.Alternative alt : draft.alternatives()) {
                    ObjectNode node = alternatives.addObject();
                    if (alt.precondition() != null) {
                        node.put("precondition", alt.precondition());
                    }
                    node.put("operation", alt.operation());
                    if (alt.toleranceRangeOp() != null) {
                        node.put("toleranceRangeOp", alt.toleranceRangeOp().wire());
                    }
                }
                RuleMessages.putInto(data, comp.messages());
            } else {
                data.put("converted", false);
                data.put("reason", ((ReadComputation.Opaque) read).reason());
            }
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read " + computation,
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("read failed: ", e);
        }
    }
}
