package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code dmtool model usage} — the model-wide usage/audit read (FEATURE-GAPS G9): in ONE call, the
 * {@code field → referencing-rules/computations} map for <b>every</b> field, plus the set of fields no
 * rule or computation reads. The maintainer/audit staple ("which fields are unreferenced?", "what
 * constrains this field?") that previously meant looping {@code where-used} over every field by hand.
 *
 * <p>Built on the same {@code getDirectlyReferencedEntities} primitive {@code where-used} / {@code rule
 * deps} use (via the adapter's {@code whereUsed} + {@code whereUsedInComputations}), so it can never
 * disagree with them about what "references" means — and it works even on conditions rulekit reads as
 * {@code Opaque}. A read op: the payload rides the envelope's {@code data} (shape: {@code model-usage.schema}).
 */
@Command(name = "usage", mixinStandardHelpOptions = true,
        description = "Model-wide usage audit: every field's referencing rules/computations + the unreferenced set, in `data`.")
final class ModelUsageCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            List<String> fields = ModelDocument.read(json).fieldPaths();
            // the whole-model reference inverse, built ONCE (O(R+C)) — not the per-field where-used
            // recomputation that made this O(F·(R+C)) and stalled on huge models (Set RP)
            Map<String, Set<String>> byEntity = adapter.referrersByEntity();
            ObjectNode data = MAPPER.createObjectNode();
            ObjectNode usage = data.putObject("usage");
            ArrayNode unreferenced = data.putArray("unreferenced");
            for (String field : fields) {
                Set<String> referrers = new TreeSet<>(byEntity.getOrDefault(field, Set.of()));
                ArrayNode arr = usage.putArray(field);
                referrers.forEach(arr::add);
                if (referrers.isEmpty()) {
                    unreferenced.add(field);
                }
            }
            ResultEnvelope env = ResultEnvelope.queryResult("model", "usage",
                    "usage of " + fields.size() + " field(s); " + unreferenced.size() + " unreferenced",
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("usage failed: ", e);
        }
    }
}
