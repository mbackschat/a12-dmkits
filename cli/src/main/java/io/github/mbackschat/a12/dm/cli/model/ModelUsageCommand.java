package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

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
 * {@code field → reading-rules/computations} map for <b>every</b> field, the document uniqueness criteria naming
 * each field, plus the set of fields no rule, computation or criterion uses. The maintainer/audit staple ("which fields are unreferenced?", "what
 * constrains this field?") that previously meant looping {@code where-used} over every field by hand.
 *
 * <p>Seeded by the kernel's {@code getDirectlyReferencedEntities} (so it works even on conditions rulekit reads
 * as {@code Opaque}), but it answers <b>reachability</b> rather than exactness: a field read only through an
 * ancestor GROUP operand is reported read, because the kernel expands a group operand recursively to every field
 * below it. So it <em>deliberately</em> reports more than {@code where-used}, which answers "which rules NAME
 * this entity?" — they differ on exactly the group-reached fields ({@code CG16}; before, this verb called such a
 * field {@code unreferenced}, which an agent acts on as safe-to-delete).
 * A read op: the payload rides the envelope's {@code data} (shape: {@code model-usage.schema}).
 */
@Command(name = "usage", mixinStandardHelpOptions = true,
        description = "Model-wide usage audit: every field's reading rules/computations (including reach through an ancestor group), the document uniqueness criteria naming each field, + the unreferenced set, in `data`.")
final class ModelUsageCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            KernelAdapter adapter = KernelAdapter.ofJson(json, workspace.resolverFor(model));
            List<String> fields = ModelDocument.read(json).fieldPaths();
            // the whole-model reference inverse, built ONCE (O(R+C)) — not the per-field where-used
            // recomputation that made this O(F·(R+C)) and stalled on huge models (Set RP)
            Map<String, Set<String>> byEntity = adapter.referrersByEntity();
            Map<String, Set<String>> criteria = adapter.uniquenessCriteria().byField();
            ObjectNode data = MAPPER.createObjectNode();
            ObjectNode usage = data.putObject("usage");
            ObjectNode criteriaUsage = data.putObject("documentUniquenessCriteria");
            // every field a criterion names, a mounted one included: the criteria come from the expanded model (KF671)
            criteria.forEach((field, names) -> new TreeSet<>(names).forEach(criteriaUsage.putArray(field)::add));
            ArrayNode unreferenced = data.putArray("unreferenced");
            for (String field : fields) {
                // reachability, NOT exactness: a group operand expands recursively to every field below it, so a
                // field read only through `AllFieldsFilled(/Order/Address)` has no direct referrer. Reporting it
                // `unreferenced` is what an agent acts on when it decides a field is safe to delete (CG16).
                Set<String> referrers = new TreeSet<>(KernelAdapter.referrersReaching(byEntity, field));
                ArrayNode arr = usage.putArray(field);
                referrers.forEach(arr::add);
                if (referrers.isEmpty() && !criteria.containsKey(field)) {
                    unreferenced.add(field);
                }
            }
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(),
                    "usage of " + fields.size() + " field(s); " + unreferenced.size() + " unreferenced",
                    data, ConsistencyEvidence.of(adapter));
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            throw withFailureContext("usage failed: ", e);
        }
    }
}
