package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool include add --alias <a> --reference <r> --parent <path> --name <n>} — declare a header
 * model-reference and mount it as a group, then write the model back (in place; {@code -o}/{@code --dry-run}).
 * {@code --repeatability} mounts a repeating sub-form; {@code --exclude-rules}/{@code --exclude-computations}
 * drop the included model's own rules/computations on expand (FEATURE-GAPS §3.9). The referenced model is
 * resolved + kernel-validated via {@code -w/--workspace}; a missing reference or an invalid mount is a
 * {@code rejected} envelope.
 */
@Command(name = "add", mixinStandardHelpOptions = true,
        description = "Declare a model include (--alias → --reference) and mount it as --name under --parent.")
final class IncludeAddCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--alias", required = true, paramLabel = "<alias>",
            description = "the include alias used by the mount")
    String alias;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--reference", required = true, paramLabel = "<reference>",
            description = "the referenced model's header id (resolved via -w/--workspace)")
    String reference;

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Option(names = "--parent", required = true, paramLabel = "<path>",
            description = "the parent group's full name-path to mount under (e.g. /Order)")
    String parent;

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--name", required = true, paramLabel = "<name>",
            description = "the mount group's name (the included content resolves under this path)")
    String name;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--repeatability", defaultValue = "1", paramLabel = "<n>",
            description = "mount as a repeatable sub-form of up to <n> rows (default 1 = single)")
    int repeatability;

    @Option(names = "--exclude-rules",
            description = "exclude the included model's own rules when the mount is expanded")
    boolean excludeRules;

    @Option(names = "--exclude-computations",
            description = "exclude the included model's own computations when the mount is expanded")
    boolean excludeComputations;

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
        String mountPath = parent + "/" + name;
        if (!parent.isEmpty()) {   // "" = a root mount, which has no parent group to check
            Optional<Refusal> refusal = new SafetyGate()
                    .require(SafetyChecks.exists(ModelDocument.read(modelJson), parent, true))
                    .firstFailure();
            if (refusal.isPresent()) {
                return refuse("include", "add", refusal.get());
            }
        }
        MountOptions options = MountOptions.repeatable(repeatability);
        if (excludeRules) {
            options = options.excludingRules();
        }
        if (excludeComputations) {
            options = options.excludingComputations();
        }
        String resultJson;
        try {
            resultJson = ModelDocument.read(modelJson)
                    .addInclude(alias, reference)
                    .mount(parent, name, alias, options)
                    .validate(resolver)
                    .toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("include", "add", "the include is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("added", alias);
        changed.put("reference", reference);
        changed.put("mountedAt", mountPath);
        if (repeatability != 1) {
            changed.put("repeatability", repeatability);
        }
        if (excludeRules) {
            changed.put("excludeRules", true);
        }
        if (excludeComputations) {
            changed.put("excludeComputations", true);
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("include", "add", "would add include " + alias, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("include", "add", "added include " + alias, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
