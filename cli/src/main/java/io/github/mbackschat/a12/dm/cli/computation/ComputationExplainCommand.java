package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool computation explain <computationPath>} — project a computation into a structured {@code
 * ComputationExplanation} (computed field, common precondition + per-alternative precondition/operation,
 * warning suppressions, messages, gloss). A read op: the explanation rides {@code data}; an Opaque computation comes back as
 * {@code explained:false} + reason.
 */
@Command(name = "explain", mixinStandardHelpOptions = true,
        description = "Project a computation as a structured explanation.")
final class ComputationExplainCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.COMPUTATION_PATH)
    @Parameters(index = "0", paramLabel = "<computationPathInModel>",
            description = "the computation's full name — its slash-path in the model, e.g. /Order/TotalComp")
    String computation;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            BoundModel bound = BoundModel.ofJson(json, workspace.resolverFor(model));
            KernelAdapter adapter = KernelAdapter.of(bound);
            // an unknown computation is a coded, structured refusal (AP1), consistent with the read family.
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.computationExists(adapter, computation))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
            }
            ReadComputation read = adapter.readComputation(computation);
            JsonNode data;
            String summary;
            // Exhaustive over the sealed read: the cast this replaced meant "not Converted is Opaque", so a
            // third state would have been a ClassCastException on a computation the kernel accepts rather
            // than a compile error.
            switch (read) {
                case ReadComputation.Converted converted -> {
                    data = readGeneratedJson(
                            new ModelContext(bound).explainComputation(converted.computation()).toJson());
                    summary = "explained " + computation;
                }
                case ReadComputation.Opaque opaque -> {
                    ObjectNode d = MAPPER.createObjectNode();
                    d.put("computation", computation);
                    d.put("explained", false);
                    d.put("reason", opaque.reason());
                    data = d;
                    summary = computation + " is Opaque (not modeled)";
                }
            }
            ObjectNode explained = (ObjectNode) data;
            var suppressions = explained.putArray("errorCodesToSuppress");
            adapter.computationErrorCodesToSuppress(computation).forEach(suppressions::add);
            // Owner-level like the suppressions: an explained formula already carries the same value, an Opaque
            // one has no other source for it.
            adapter.computationRoundingMode(computation).stored().ifPresent(mode -> explained.put("roundingMode", mode));
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary, data,
                    ConsistencyEvidence.of(bound));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("explain failed: ", e);
        }
    }
}
