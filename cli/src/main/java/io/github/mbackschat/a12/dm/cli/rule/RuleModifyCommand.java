package io.github.mbackschat.a12.dm.cli.rule;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.edit.EditSession;
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
 * previews). At least one of condition / severity / message / metadata is required. A condition change
 * re-expresses the rule (the before/after {@code RenderedDiff} rides {@code changed.condition}); severity/
 * message edits leave the condition untouched. The rule's <b>metadata</b> — {@code --annotation}/
 * {@code --remove-annotation} and per-locale {@code --internal-description}/{@code --external-description}
 * (G39, the rule peer of the group-metadata edits {@code group modify} carries — rules have no label slot) —
 * rides {@link EditSession} (a whitespace-normalizing re-serialize, exactly as {@code group modify}), while
 * condition/severity/message stay targeted textual edits. The whole result is re-bound through the kernel —
 * an invalid edit is a {@code rejected} envelope (nothing written).
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Edit a rule's condition / severity / messages / metadata (--annotation / "
                + "--remove-annotation / --internal-description / --external-description) and write the model "
                + "back (at least one; in place, -o redirects, --dry-run previews).")
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

    @Option(names = "--annotation", paramLabel = "<name>=<value>",
            description = "set a rule annotation (repeatable), e.g. --annotation owner=risk")
    Map<String, String> annotations = new LinkedHashMap<>();

    @Option(names = "--remove-annotation", paramLabel = "<name>",
            description = "remove a rule annotation by name (repeatable; the inverse of --annotation)")
    List<String> removeAnnotations = new ArrayList<>();

    @Option(names = "--internal-description", paramLabel = "<locale>=<text>",
            description = "set a per-locale internal description on the rule (repeatable)")
    Map<String, String> internalDescriptions = new LinkedHashMap<>();

    @Option(names = "--external-description", paramLabel = "<locale>=<text>",
            description = "set a per-locale external description on the rule (repeatable)")
    Map<String, String> externalDescriptions = new LinkedHashMap<>();

    // T1: the description-removal peers (the inverse of --internal-description/--external-description),
    // matching the removal set the `meta` verb already offers.
    @Option(names = "--remove-internal-description", paramLabel = "<locale>",
            description = "remove the rule's internal description for a locale (repeatable)")
    List<String> removeInternalDescriptions = new ArrayList<>();

    @Option(names = "--remove-external-description", paramLabel = "<locale>",
            description = "remove the rule's external description for a locale (repeatable)")
    List<String> removeExternalDescriptions = new ArrayList<>();

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
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        ModelResolver resolver = workspace.resolverFor(model);
        // the rule-exists gate runs first, so a missing rule gets the structured RK_NO_SUCH_RULE refusal
        // regardless of which edit args were passed (CLI-CONFORMANCE-SPEC)
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.ruleExists(KernelAdapter.ofJson(modelJson, resolver), rule))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("rule", "modify", refusal.get());
        }
        boolean metadataChange = !annotations.isEmpty() || !removeAnnotations.isEmpty()
                || !internalDescriptions.isEmpty() || !externalDescriptions.isEmpty()
                || !removeInternalDescriptions.isEmpty() || !removeExternalDescriptions.isEmpty();
        if (condition == null && severity == null && messages.isEmpty() && !metadataChange) {
            return fail("nothing to change — pass at least one of --condition, --severity, --message, "
                    + "--annotation/--remove-annotation, --internal-description/--external-description "
                    + "(and their --remove-… peers)");
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
            // G39: rule metadata (annotations + internal/external descriptions) rides EditSession — the same
            // path group modify uses (G38). The condition/severity/message edits above stay targeted textual
            // ModelWriter edits (RenderedDiff + ;;-comment fidelity); a metadata edit re-serializes whitespace
            // through ModelDocument, exactly as `group modify --annotation` does (rules have no label slot).
            if (metadataChange) {
                EditSession session = EditSession.read(modifiedJson, resolver);
                annotations.forEach((name, value) -> session.setAnnotation(rule, name, value));
                removeAnnotations.forEach(name -> session.removeAnnotation(rule, name));
                internalDescriptions.forEach((locale, text) -> session.setInternalDescription(rule, locale, text));
                externalDescriptions.forEach((locale, text) -> session.setExternalDescription(rule, locale, text));
                removeInternalDescriptions.forEach(locale -> session.removeInternalDescription(rule, locale));
                removeExternalDescriptions.forEach(locale -> session.removeExternalDescription(rule, locale));
                modifiedJson = session.toJson();
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
        annotations.forEach((name, value) -> changed.put("annotation." + name, value));
        removeAnnotations.forEach(name -> changed.putNull("annotation." + name));
        internalDescriptions.forEach((locale, text) -> changed.put("internalDescription." + locale, text));
        externalDescriptions.forEach((locale, text) -> changed.put("externalDescription." + locale, text));

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
