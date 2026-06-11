package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code remove} verb (CLI-SPEC §4) — delete a field, group, rule, or computation and <b>write the
 * model back</b> (in place by default; {@code -o} redirects, {@code --dry-run} previews — the {@code
 * modify} convention). The <b>safe-remove gate</b>: removing a field/group a rule or computation still
 * references is refused (exit 2, listing the references) unless {@code --cascade}, which removes those
 * referencing rules/computations first (found via the adapter's exact {@code whereUsed}) and then the
 * element. The result is re-bound through the kernel, so a removal that breaks the model fails loudly.
 */
@Command(name = "remove", mixinStandardHelpOptions = true,
        description = "Remove a field/group/rule/computation and WRITE the model back (in place; -o redirects, "
                + "--dry-run previews). A referenced field/group is refused unless --cascade (which also removes "
                + "the rules/computations that reference it).")
final class RemoveCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>",
            description = "the DM-JSON model FILE — distinct from the --field/--group/--rule/--computation path inside it")
    String model;

    @Option(names = "--field", paramLabel = "<path>",
            description = "remove the field at this full name-path, e.g. /Order/Quantity")
    String field;

    @Option(names = "--group", paramLabel = "<path>",
            description = "remove the group at this full name-path (and everything under it)")
    String group;

    @Option(names = "--rule", paramLabel = "<path>",
            description = "remove the rule at this full name, e.g. /Order/SomeRule")
    String rule;

    @Option(names = "--computation", paramLabel = "<path>",
            description = "remove the computation at this full name")
    String computation;

    @Option(names = "--cascade",
            description = "when removing a field/group: also remove the rules/computations that reference it "
                    + "(without this, a referenced field/group is refused, listing the references)")
    boolean cascade;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting <model.json>)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print what would be removed and write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

    @Override
    public Integer call() {
        int targets = (field != null ? 1 : 0) + (group != null ? 1 : 0)
                + (rule != null ? 1 : 0) + (computation != null ? 1 : 0);
        if (targets != 1) {
            return fail("pass exactly one of --field / --group / --rule / --computation <path>");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        String target = field != null ? field : group != null ? group : rule != null ? rule : computation;
        List<String> cascaded = new ArrayList<>();
        EditSession session = EditSession.read(modelJson, resolver);

        try {
            if (field != null || group != null) {
                KernelAdapter adapter = KernelAdapter.ofJson(modelJson, resolver);
                Set<String> refRules = new TreeSet<>(adapter.whereUsed(target));
                Set<String> refComputations = new TreeSet<>(adapter.whereUsedInComputations(target));
                if (!refRules.isEmpty() || !refComputations.isEmpty()) {
                    if (!cascade) {
                        return refuse(target, refRules, refComputations);
                    }
                    for (String r : refRules) {
                        session.removeRule(r);
                        cascaded.add(r);
                    }
                    for (String c : refComputations) {
                        session.removeComputation(c);
                        cascaded.add(c);
                    }
                }
                if (field != null) {
                    session.removeField(target);
                } else {
                    session.removeGroup(target);
                }
            } else if (rule != null) {
                session.removeRule(target);
            } else {
                session.removeComputation(target);
            }
        } catch (RuntimeException e) {
            // no element at path / unresolvable target — a usage error (the modelkit/rulekit IAE convention)
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return reject(target, e);
        }

        out().print(summary(target, cascaded));
        if (dryRun) {
            err().println("(dry run — " + target + " not written)");
            return 0;
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        err().println("removed " + target + " → " + dest);
        return 0;
    }

    private String summary(String target, List<String> cascaded) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("removed", target);
        ArrayNode casc = root.putArray("cascaded");
        cascaded.forEach(casc::add);
        root.put("written", !dryRun);
        return toJson(root);
    }

    /**
     * The safe-remove gate fired: the field/group is still referenced and {@code --cascade} was not given.
     * Surface <em>which</em> rules/computations block it (the proactive {@code whereUsed} answer), structured
     * so an agent can act — pass {@code --cascade}, or remove them first. Exit 2 (nothing written).
     */
    private int refuse(String target, Set<String> refRules, Set<String> refComputations) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("blocked", target);
        ObjectNode refs = root.putObject("referencedBy");
        ArrayNode rules = refs.putArray("rules");
        refRules.forEach(rules::add);
        ArrayNode comps = refs.putArray("computations");
        refComputations.forEach(comps::add);
        root.put("hint", "remove these first, or pass --cascade to remove them together with " + target);
        err().println(toJson(root));
        return 2;
    }

    /**
     * The removal left the model kernel-invalid (rare under {@code --cascade}, possible otherwise): surface
     * the kernel's own diagnostics (the {@code MVK_*} notifications when present, else the failure detail),
     * consistent with {@code validate}/{@code check}. Exit 1.
     */
    private int reject(String target, ModelConsistencyException e) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("valid", false);
        root.put("element", target);
        ArrayNode diagnostics = root.putArray("diagnostics");
        if (e.notifications().isEmpty()) {
            diagnostics.add(e.getMessage());
        } else {
            e.notifications().forEach(n -> diagnostics.add(n.getMessage()));
        }
        err().println(toJson(root));
        return 1;
    }

    private String toJson(ObjectNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return node.toString() + "\n";
        }
    }
}
