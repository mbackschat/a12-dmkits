package io.github.mbackschat.a12.dm.cli;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code where-used} verb (F9a, CLI-SPEC §4) — the exact inverse of a rule's referenced entities:
 * given a field or group, list every <b>rule</b> and every <b>computation</b> whose condition
 * <em>directly</em> references it. Uses the kernel's own {@code getDirectlyReferencedEntities} via the
 * adapter (no converter coverage needed), so it works regardless of whether read models its construct.
 * Rules and computations are reported in separate arrays so the two element kinds stay distinguishable —
 * a field read only inside a computation's operation appears under {@code computations}, not {@code rules}.
 *
 * <p>The model is the {@code -m} context (like every other verb); the entity is a <b>positional</b> path
 * (G11) — consistent with {@code rule read <path>} / {@code field read <path>}. {@code --entity} stays as
 * the explicit alias. The pre-G11 {@code where-used <model> --entity=…} form (model passed positionally)
 * self-corrects to the new shape.
 */
@Command(name = "where-used", mixinStandardHelpOptions = true,
        description = "List the rules and computations whose condition directly references a given field or group.")
final class WhereUsedCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<entityPath>",
            description = "the field or group's full (absolute) path inside the model, e.g. /Order/Quantity "
                    + "(positional, like `rule read <path>`; or pass --entity)")
    String entityArg;

    @Option(names = "--entity", paramLabel = "<entityPath>",
            description = "the explicit alias for the positional <entityPath>")
    String entityOpt;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String entity = entityArg != null ? entityArg : entityOpt;
        String model = model();
        if (model == null) {
            // the model is now -m (consistent with every other verb); the pre-G11
            // `where-used <model> --entity=…` form (model passed positionally, no -m) lands here — name the
            // change so it self-corrects rather than failing opaquely
            return fail("where-used now takes -m <model> + a positional <entity> — it no longer takes the model "
                    + "as a positional argument. Try: -m <model.json> where-used "
                    + (entity != null ? entity : "/Group/Field"));
        }
        if (entity == null) {
            return fail("pass the entity to inspect — positionally (`where-used /Order/Quantity`) or via --entity");
        }
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        try {
            KernelAdapter adapter = KernelAdapter.ofJson(modelJson, workspace.resolverFor(model));
            Set<String> rules = adapter.whereUsed(entity);
            Set<String> computations = adapter.whereUsedInComputations(entity);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("entity", entity);
            ArrayNode rulesArr = root.putArray("rules");
            rules.forEach(rulesArr::add);
            ArrayNode compsArr = root.putArray("computations");
            computations.forEach(compsArr::add);
            root.put("count", rules.size() + computations.size());
            out().print(pretty(root));
            return 0;
        } catch (RuntimeException e) {
            return fail("where-used failed: " + e.getMessage());
        }
    }

    private static String pretty(ObjectNode root) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize where-used result", e);
        }
    }
}
