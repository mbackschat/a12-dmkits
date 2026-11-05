package io.github.mbackschat.a12.dm.cli.criterion;

import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool criterion read [<name>]} — read the model's document uniqueness criteria as A12 stores them. No
 * argument returns the whole {@code documentUniquenessCriteria} member (empty when the model declares none); a
 * {@code <name>} returns that one entry, which is also a valid {@code criterion add} spec.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the document uniqueness criteria into `data` — all, or one by <name>.")
final class CriterionReadCommand extends BaseCommand {

    @ParamKind(ParamKind.Kind.CRITERION_NAME)
    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
            description = "a criterion's name to read just that one; omit to read every criterion "
                    + "(or address it with --criterion)")
    String name;

    @Option(names = "--criterion", paramLabel = "<name>",
            description = "the criterion's name — the named-flag alias for the positional (the locator contract: "
                    + "the target-noun flag always works). The positional wins if both are given.")
    String nameFlag;

    @Override
    public Integer call() {
        String model = model();
        if (name == null) {
            name = nameFlag;
        }
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        ModelDocument doc = ModelDocument.read(json);
        if (name != null) {
            Optional<Refusal> refusal = new SafetyGate().require(SafetyChecks.criterionExists(doc, name)).firstFailure();
            if (refusal.isPresent()) {
                return refuse(refusal.get());
            }
            return emit(ReadPayloads.criterion(doc, name), "read uniqueness criterion " + name);
        }
        ObjectNode data = ReadPayloads.criterionList(doc);
        return emit(data, "read " + data.path(ReadPayloads.CRITERIA).size() + " uniqueness criterion(s)");
    }

    private int emit(ObjectNode data, String summary) {
        ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary, data, ConsistencyEvidence.none());
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
