package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.RejectionBasis;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.SafetyGate;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
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
 * previews). At least one of condition / severity / message is required. A condition change
 * re-expresses the rule (the before/after {@code RenderedDiff} rides {@code changed.condition}); severity/
 * message edits leave the condition untouched. Common metadata is edited through {@code meta <ref>} (G45).
 * The whole result is re-bound through the kernel — an invalid edit is a {@code rejected} envelope (nothing written).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Edit a rule's condition / severity / messages and write the model back "
                + "(at least one; in place, -o redirects, --dry-run previews). Use `meta <ref>` for metadata.")
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
            description = "upsert a per-locale error message (repeatable); MERGES with the rule's existing "
                    + "messages — other locales are kept, so a one-locale edit can't drop the rest")
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
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        // the rule-exists gate runs first, so a missing rule gets the structured RK_NO_SUCH_RULE refusal
        // regardless of which edit args were passed (CLI-CONFORMANCE-SPEC)
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.ruleExists(KernelAdapter.ofJson(modelJson, resolver), rule))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(refusal.get());
        }
        if (condition == null && severity == null && messages.isEmpty()) {
            return fail("nothing to change — pass at least one of --condition, --severity, or --message");
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
                // G17: merge (upsert) per locale rather than replacing the whole set — so updating one
                // translation can't silently drop the others (a localization-pass footgun). Start from the
                // rule's existing messages, then upsert each provided locale, preserving the rest.
                Map<String, String> merged = new LinkedHashMap<>();
                for (LocalizedText existing : KernelAdapter.ofJson(modelJson, resolver).ruleMessages(rule)) {
                    merged.put(existing.locale(), existing.text());
                }
                merged.putAll(messages);
                List<LocalizedText> msgs = merged.entrySet().stream()
                        .map(e -> new LocalizedText(e.getKey(), e.getValue())).toList();
                modifiedJson = ModelWriter.replaceMessages(modifiedJson, rule, msgs);
            }
        } catch (RuntimeException e) {
            return fail("cannot apply the change: " + e.getMessage());
        }

        BoundModel rebound;
        try {
            rebound = BoundModel.ofJson(modifiedJson, resolver);
        } catch (ModelConsistencyException e) {
            // The gate reached a verdict, so the kernel's OWN notifications are the diagnostics — one per
            // problem, each carrying the class it named. This used to publish a single hand-rolled PRECHECK
            // diagnostic wrapping the joined message, beside verification:KERNEL_CONFIRMED: a rulekit-origin
            // finding attributed to a kernel arbitration, and the kernel's per-problem detail discarded. Its
            // corrective pointed at `rule check`, which cannot reach an error text at all.
            return emitKernelRejection("edit rejected by the kernel", e);
        } catch (RuntimeException e) {
            // No verdict was reached (a parse/render failure), so this claims neither a class nor an arbitrator.
            Diagnostic d = Diagnostic.precheck(Optional.empty(),
                    "the edit could not be bound to the model: " + e.getMessage(),
                    new Diagnostic.Where(rule, Optional.empty()), Optional.empty(), Optional.empty());
            ResultEnvelope env = ResultEnvelope.rejected(resultIdentity(), "edit rejected before the kernel gate",
                    List.of(d), RejectionBasis.preflight());
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
                changed.setAll((ObjectNode) readGeneratedJson(diff.toJson()));
            } catch (RuntimeException e) {
                throw withFailureContext("re-expression failed: ", e);
            }
        }
        if (severity != null) {
            changed.put("severity", severity.name());
        }
        if (!messages.isEmpty()) {
            ArrayNode m = changed.putArray("messages");
            messages.forEach((locale, text) -> m.addObject().put("locale", locale).put("text", text));
        }
        String verb = condition != null ? "re-express" : "modify";
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would " + verb + " " + rule,
                        (condition != null ? "re-expressed " : "modified ") + rule)
                .changed(changed)
                .json(modifiedJson)
                .gatedBy(ConsistencyEvidence.of(rebound))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }
}
