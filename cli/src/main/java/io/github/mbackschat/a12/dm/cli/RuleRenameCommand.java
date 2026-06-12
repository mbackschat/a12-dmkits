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
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule rename <ruleFullName> --to <newName>} — a Tier-2 safe rename (CLI-SPEC §6). A rule is
 * not referenced by anything (conditions reference fields, not rules), so the only gate is a name
 * collision ({@code RK_NAME_EXISTS}); the kernel re-validate is the backstop. Renames the rule's name + id.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Rename a validation rule; a colliding name is refused.")
final class RuleRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<ruleFullName>", description = "the rule's full name (group-path + name)")
    String rule;

    @Option(names = "--to", required = true, paramLabel = "<newName>", description = "the rule's new name")
    String newName;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    IncludeDirOption includes = new IncludeDirOption();

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
        ModelDocument doc = ModelDocument.read(modelJson);
        String newPath = rule.substring(0, rule.lastIndexOf('/') + 1) + newName;
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.pathAvailable(doc, newPath))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("rule", "rename", refusal.get());
        }

        String resultJson;
        try {
            doc.renameRule(rule, newName);
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("rule", "rename", "rename left the model invalid", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("renamed", rule);
        changed.put("to", newPath);
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("rule", "rename",
                    "would rename " + rule + " to " + newPath, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("rule", "rename", "renamed " + rule + " to " + newPath,
                changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
