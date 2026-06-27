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
 * {@code dmtool group attachment --parent <p> --name <n>} — expand an <b>Attachment</b> group template
 * (FEATURE-GAPS): the kernel's canonical file-attachment composition (8 fields + 4 rules +
 * {@code content} config) is generated; you only tune the exposed knobs ({@code --repeatable},
 * {@code --max-size-bytes}, {@code --allowed-mime}, {@code --categories}). The mandated rules are authored
 * in the model's condition language — an attachment on a non-{@code en_US}/{@code de_DE} model is refused
 * (not crashed). Re-bound through the kernel ({@code AttachmentGroupRule}); an invalid result is a
 * {@code rejected} envelope. Per-rule message wording can be adjusted afterward with {@code rule modify --message}.
 */
@Command(name = "attachment", mixinStandardHelpOptions = true,
        description = "Add an attachment group template (the canonical 8-field + 4-rule file-attachment composition).")
final class GroupAttachmentCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group, by full name-path, e.g. /Doc")
    String parent;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>", description = "the attachment group's name")
    String name;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--repeatable", paramLabel = "<max>",
            description = "make it a list of up to <max> attachments (default 1 = single)")
    Integer repeatable;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--max-size-bytes", paramLabel = "<n>", description = "constrain the size field to a maximum (bytes)")
    Long maxSizeBytes;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--allowed-mime", paramLabel = "<m,m,…>", split = ",",
            description = "restrict mime_type to this set (comma-separated; else a free string)")
    List<String> allowedMime;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--categories", paramLabel = "<c,c,…>", split = ",",
            description = "restrict category to this set (comma-separated; else a free string)")
    List<String> categories;

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
        SafetyGate gate = new SafetyGate().require(SafetyChecks.attachmentLocaleSupported(doc));
        if (!parent.isEmpty()) {
            gate = gate.require(SafetyChecks.exists(doc, parent, true));
        }
        Optional<Refusal> refusal = gate.firstFailure();
        if (refusal.isPresent()) {
            return refuse("group", "attachment", refusal.get());
        }
        String resultJson;
        try {
            doc.addAttachmentGroup(parent, name, a -> {
                if (repeatable != null) {
                    a.repeatable(repeatable);
                }
                if (maxSizeBytes != null) {
                    a.maxSizeBytes(maxSizeBytes);
                }
                if (allowedMime != null && !allowedMime.isEmpty()) {
                    a.allowedMimeTypes(allowedMime.toArray(String[]::new));
                }
                if (categories != null && !categories.isEmpty()) {
                    a.categories(categories.toArray(String[]::new));
                }
            });
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "attachment", "the attachment group is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", target);
        changed.put("template", "attachment");
        if (repeatable != null) {
            changed.put("repeatable", repeatable);
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "attachment",
                    "would add attachment group " + target, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "attachment",
                "added attachment group " + target, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
