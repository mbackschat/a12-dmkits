package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool rule modify <rulePath> [--condition <DSL>] [--severity <S>] [--message <loc>=<txt>]} — edit
 * an existing rule in place and write the model back (in place; {@code -o} redirects, {@code --dry-run}
 * previews). At least one of condition / severity / message is required. A condition change re-expresses
 * the rule (the before/after {@code RenderedDiff} rides {@code changed.condition}); severity/message edits
 * leave the condition untouched. The whole result is re-bound through the kernel — an invalid edit is a
 * {@code rejected} envelope (nothing written).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Edit a rule's condition / severity / messages and write the model back "
                + "(at least one; in place, -o redirects, --dry-run previews).")
final class RuleModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.RULE_PATH)
    @Parameters(index = "0", paramLabel = "<rulePathInModel>",
            description = "the rule's full name — its slash-path in the model, e.g. /Order/SomeRule")
    String rule;

    @ParamKind(ParamKind.Kind.FREEFORM)
    @Option(names = "--condition", paramLabel = "<DSL>",
            description = "the new condition in A12 DSL — true on a VIOLATION (the error scenario)")
    String condition;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--severity", paramLabel = "ERROR|WARNING|INFO",
            description = "set the rule's severity in place (the condition is untouched)")
    Severity severity;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--message", paramLabel = "<locale>=<text>",
            description = "set a per-locale error message (repeatable); replaces the rule's messages")
    Map<String, String> messages = new LinkedHashMap<>();

    @Option(names = "--comment", paramLabel = "<text>",
            description = "set an explanatory ;; comment on the rule (with --condition; omit to keep the existing one)")
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
        if (condition == null && severity == null && messages.isEmpty()) {
            return fail("nothing to change — pass at least one of --condition, --severity, --message");
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

        String modifiedJson;
        try {
            modifiedJson = modelJson;
            if (condition != null) {
                // --comment wins as the new leading comment; omitted, replaceCondition carries the existing one
                modifiedJson = ModelWriter.replaceCondition(modifiedJson, rule,
                        Comments.withLeadingComment(comment, condition));
            }
            if (severity != null) {
                modifiedJson = ModelWriter.replaceSeverity(modifiedJson, rule, severity.name());
            }
            if (!messages.isEmpty()) {
                List<LocalizedText> msgs = messages.entrySet().stream()
                        .map(e -> new LocalizedText(e.getKey(), e.getValue())).toList();
                modifiedJson = ModelWriter.replaceMessages(modifiedJson, rule, msgs);
            }
        } catch (RuntimeException e) {
            return fail("cannot apply the change: " + e.getMessage());
        }

        try {
            BoundModel.ofJson(modifiedJson, resolver);
        } catch (RuntimeException e) {
            Diagnostic d = Diagnostic.precheck(Optional.empty(),
                    "the edit is rejected by the kernel (use `rule check` for diagnostics): " + e.getMessage(),
                    new Diagnostic.Where(rule, Optional.empty()), Optional.empty(), Optional.empty());
            ResultEnvelope env = ResultEnvelope.rejected("rule", "modify", "edit rejected by the kernel", List.of(d));
            out().print(env.toJsonString());
            return env.exitCode();
        }

        ObjectNode changed = MAPPER.createObjectNode();
        if (condition != null) {
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
                // the diff fields (before/after/changed/lossiness/unified) ride `changed` directly — the
                // established condition-modify shape; severity/message (below) are added as sibling keys
                changed.setAll((ObjectNode) MAPPER.readTree(diff.toJson()));
            } catch (IOException | RuntimeException e) {
                return fail("re-expression failed: " + e.getMessage());
            }
        }
        if (severity != null) {
            changed.put("severity", severity.name());
        }
        if (!messages.isEmpty()) {
            ArrayNode m = changed.putArray("messages");
            messages.forEach((locale, text) -> m.addObject().put("locale", locale).put("text", text));
        }

        if (dryRun) {
            String summary = (condition != null ? "would re-express " : "would modify ") + rule;
            ResultEnvelope env = ResultEnvelope.preview("rule", "modify", summary, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), modifiedJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        String summary = (condition != null ? "re-expressed " : "modified ") + rule;
        ResultEnvelope env = ResultEnvelope.applied("rule", "modify", summary, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
