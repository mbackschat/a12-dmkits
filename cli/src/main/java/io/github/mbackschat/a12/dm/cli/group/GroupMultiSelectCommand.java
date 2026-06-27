package io.github.mbackschat.a12.dm.cli.group;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool group multiselect --parent <p> --name <n> --field <f> [--values v,v,…] --max-rows <n>} —
 * expand a <b>Multi-Select</b> group template (FEATURE-GAPS): a repeatable group holding one selection
 * field (enum when {@code --values} is given, else a free string). The kernel skeleton ({@code usageType},
 * repeatability, the single-field + requiredness rules) is generated; you supply the field and the max number
 * of selections. Re-bound through the kernel ({@code MultiSelectGroupRule}); an invalid result is a
 * {@code rejected} envelope.
 */
@Command(name = "multiselect", mixinStandardHelpOptions = true,
        description = "Add a multi-select group template (a repeatable enum/string selection field).")
final class GroupMultiSelectCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group, by full name-path, e.g. /Product")
    String parent;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the multi-select group's name")
    String name;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--field", required = true, paramLabel = "<name>", description = "the single selection field's name")
    String field;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--values", paramLabel = "<v,v,…>", split = ",",
            description = "enum values for the field (comma-separated); omit for a free-text string field")
    List<String> values;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--max-rows", required = true, paramLabel = "<n>",
            description = "the max number of selections (the group's repeatability; must be > 1)")
    int maxRows;

    @Option(names = "--required-if-parent",
            description = "a selected row's value is required only when the group is present (default: always required)")
    boolean requiredIfParent;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        String target = parent + "/" + name;
        ModelDocument doc = ModelDocument.read(modelJson);
        SafetyGate gate = new SafetyGate().require(SafetyChecks.multiSelectMinRows(maxRows));
        if (!parent.isEmpty()) {
            gate = gate.require(SafetyChecks.exists(doc, parent, true));
        }
        Optional<Refusal> refusal = gate.firstFailure();
        if (refusal.isPresent()) {
            return refuse("group", "multiselect", refusal.get());
        }
        String resultJson;
        try {
            doc.addMultiSelectGroup(parent, name, m -> {
                if (values != null && !values.isEmpty()) {
                    m.enumField(field, values.toArray(String[]::new));
                } else {
                    m.stringField(field);
                }
                m.maxRows(maxRows);
                if (requiredIfParent) {
                    m.requiredIfParentPresent();
                }
            });
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "multiselect", "the multi-select group is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", target);
        changed.put("field", field);
        changed.put("maxRows", maxRows);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "multiselect",
                    "would add multi-select group " + target, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "multiselect",
                "added multi-select group " + target, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
