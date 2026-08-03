package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool include read [<alias>]} — read the model's declared includes. Unified read contract: no
 * argument lists every include ({@code data.includes}, an array of {@code {alias, reference}}); an
 * {@code <alias>} reads just that one ({@code data.alias}+{@code reference}). Includes are a flat
 * header-level list; resolving the referenced models is not needed to read them.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the declared model includes into `data` — all, or one by <alias>.")
final class IncludeReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_ALIAS)
    @Parameters(index = "0", arity = "0..1", paramLabel = "<alias>",
            description = "an include's alias to read just that one; omit to list every declared include "
                    + "(or address it with --include / --alias)")
    String alias;

    @Option(names = {"--include", "--alias"}, paramLabel = "<alias>",
            description = "the include's alias — the named-flag alias for the positional (the locator contract: "
                    + "the target-noun flag always works). The positional wins if both are given.")
    String aliasFlag;

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        if (alias == null) {   // locator contract: --include/--alias is the named alias for the positional
            alias = aliasFlag;
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            List<ModelDocument.Include> incs = doc.includes();
            if (alias != null) {
                // an unknown alias is a coded, structured refusal (AP1), consistent with the read family.
                Optional<Refusal> refusal = new SafetyGate()
                        .require(SafetyChecks.includeExists(doc, alias))
                        .firstFailure();
                if (refusal.isPresent()) {
                    return refuse(refusal.get());
                }
                ModelDocument.Include one = incs.stream()
                        .filter(i -> alias.equals(i.alias())).findFirst().orElseThrow();
                ObjectNode data = MAPPER.createObjectNode();
                data.put("alias", one.alias()).put("reference", one.reference());
                ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read include " + alias, data,
                        ConsistencyEvidence.none());
                out().print(env.toJsonString());
                return env.exitCode();
            }
            ObjectNode data = ReadPayloads.includeList(doc);
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    "read " + incs.size() + " include(s)", data, ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
