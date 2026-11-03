package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool include read [<reference>]} — read the models this model includes. Unified read contract: no
 * argument lists every include ({@code data.includes}, an array of {@code {reference}}); a {@code <reference>}
 * reads just that one ({@code data.reference}). The referenced model id is an include's only identity on DM 29.4
 * (KERNEL-FINDINGS KF651); resolving the referenced models is not needed to read them.
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the included models into `data` — all, or one by <reference>.")
final class IncludeReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.INCLUDE_REFERENCE)
    @Parameters(index = "0", arity = "0..1", paramLabel = "<reference>",
            description = "an included model's id to read just that one; omit to list every include "
                    + "(or address it with --include / --reference)")
    String reference;

    @Option(names = {"--include", "--reference"}, paramLabel = "<reference>",
            description = "the included model's id — the named-flag alias for the positional (the locator "
                    + "contract: the target-noun flag always works). The positional wins if both are given.")
    String referenceFlag;

    @Override
    public Integer call() {
        String model = model();
        if (reference == null) {   // locator contract: --include/--reference is the named alias for the positional
            reference = referenceFlag;
        }
        String json = readModelJson(model);
        if (json == null) {
            return 2;
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            List<ModelDocument.Include> incs = doc.includes();
            if (reference != null) {
                // an unknown reference is a coded, structured refusal (AP1), consistent with the read family.
                Optional<Refusal> refusal = new SafetyGate()
                        .require(SafetyChecks.includeExists(doc, reference))
                        .firstFailure();
                if (refusal.isPresent()) {
                    return refuse(refusal.get());
                }
                ObjectNode data = MAPPER.createObjectNode();
                data.put("reference", reference);
                ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "read include " + reference, data,
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
