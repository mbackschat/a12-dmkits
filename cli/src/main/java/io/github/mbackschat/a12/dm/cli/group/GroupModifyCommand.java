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
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group modify <groupPath> (--repeatable <max> | --non-repeatable)} — change an existing
 * group's <b>repeatability</b> in place and write the model back (in place; {@code -o} redirects,
 * {@code --dry-run} previews). {@code --repeatable <max>} (≥2) makes a single group a repetition list;
 * {@code --non-repeatable} collapses a list back to one. The change is re-bound through the kernel gate, so
 * one that breaks the model (e.g. a {@code *}-wildcard rule that now has no list to range over) fails loudly
 * as a {@code rejected} envelope. (Repeatability was settable only at {@code group add} — G15.)
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Change a group's repeatability in place: --repeatable <max> (>=2) or --non-repeatable.")
final class GroupModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.GROUP_PATH)
    @Parameters(index = "0", paramLabel = "<groupPathInModel>",
            description = "the group's full name-path, e.g. /Order/ShippingAddress")
    String group;

    @Option(names = "--repeatable", paramLabel = "<max>",
            description = "make it a repetition list with this maximum number of rows (>= 2)")
    Integer repeatable;

    @Option(names = "--non-repeatable", description = "make it a single (non-repeating) group")
    boolean nonRepeatable;

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
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        // the entity-ref gate runs first, so a bogus group path is a structured refusal (not a bare arg error)
        Optional<Refusal> notFound = new SafetyGate()
                .require(SafetyChecks.exists(ModelDocument.read(modelJson), group, true))
                .firstFailure();
        if (notFound.isPresent()) {
            return refuse("group", "modify", notFound.get());
        }
        if ((repeatable != null) == nonRepeatable) {
            return fail("pass exactly one of --repeatable <max> or --non-repeatable");
        }
        if (repeatable != null && repeatable < 2) {
            return fail("--repeatable <max> must be >= 2 (use --non-repeatable to make it a single group)");
        }
        EditSession session = EditSession.read(modelJson, resolver);
        try {
            if (repeatable != null) {
                session.setRepeatable(group, repeatable);
            } else {
                session.setNonRepeatable(group);
            }
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "modify", "the repeatability change left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("group", group);
        changed.put("repeatable", repeatable != null);
        if (repeatable != null) {
            changed.put("maxRepetitions", repeatable);
        }
        String summary = repeatable != null
                ? "made " + group + " repeatable (max " + repeatable + ")"
                : "made " + group + " non-repeating";
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("group", "modify", "would have " + summary, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("group", "modify", summary, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
