package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule modify <rulePath> --condition <DSL>} — the F8 re-express loop: replace an existing
 * rule's condition, validate it against the kernel, and write the model back (in place; {@code -o}
 * redirects, {@code --dry-run} previews). The before/after {@code RenderedDiff} rides {@code changed}; a
 * kernel-rejected re-expression yields a {@code rejected} envelope (nothing written).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Re-express a rule's condition and write the model back (in place; -o redirects, --dry-run previews).")
final class RuleModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--condition", required = true, paramLabel = "<DSL>",
            description = "the new condition in A12 DSL — true on a VIOLATION (the error scenario)")
    String condition;

    @Option(names = "--comment", paramLabel = "<text>",
            description = "set an explanatory ;; comment on the rule (omit to keep the rule's existing leading comment)")
    String comment;

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — print the before/after diff and write NOTHING")
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
        IDocumentModelReferenceResolver resolver = includes.resolverFor(model);
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.ruleExists(KernelAdapter.ofJson(modelJson, resolver), rule))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("rule", "modify", refusal.get());
        }

        // --comment sets a new leading comment (it wins over the preserved old one); omitted, replaceCondition
        // carries the rule's existing leading comment by default.
        String newCondition = io.github.mbackschat.a12.dm.rulekit.dsl.Comments.withLeadingComment(comment, condition);
        String modifiedJson;
        try {
            modifiedJson = ModelWriter.replaceCondition(modelJson, rule, newCondition);
        } catch (RuntimeException e) {
            return fail("cannot apply the new condition: " + e.getMessage());
        }
        try {
            BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            Diagnostic d = Diagnostic.precheck(Optional.empty(),
                    "the re-expression is rejected by the kernel (use `rule check` for diagnostics): " + e.getMessage(),
                    new Diagnostic.Where(rule, Optional.empty()), Optional.empty(), Optional.empty());
            ResultEnvelope env = ResultEnvelope.rejected("rule", "modify",
                    "re-expression rejected by the kernel", List.of(d));
            out().print(env.toJsonString());
            return env.exitCode();
        }

        JsonNode changed;
        try {
            KernelAdapter original = KernelAdapter.ofJson(modelJson, resolver);
            KernelAdapter modified = KernelAdapter.ofJson(modifiedJson, resolver);
            RenderedDiff diff;
            if (original.read(rule) instanceof ReadResult.Converted
                    && modified.read(rule) instanceof ReadResult.Converted modConverted) {
                diff = original.diff(rule, modConverted.condition());
            } else {
                diff = RenderedDiff.between(original.canonicalConditionText(rule), modified.canonicalConditionText(rule),
                        List.of("the rule's condition isn't fully modeled by rulekit (Opaque) — this is a textual diff"));
            }
            changed = MAPPER.readTree(diff.toJson());
        } catch (IOException | RuntimeException e) {
            return fail("re-expression failed: " + e.getMessage());
        }

        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("rule", "modify", "would re-express " + rule, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), modifiedJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("rule", "modify", "re-expressed " + rule, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
