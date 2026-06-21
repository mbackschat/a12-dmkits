package io.github.mbackschat.a12.dm.cli.config;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool config modify [--decimal-separator <s>] [--time-zone <tz>] [--condition-language <code>]} —
 * change one or more document-config settings and write the model back (in place; {@code -o}/{@code --dry-run}).
 * At least one setting is required. Re-bound through the kernel; an invalid result is a {@code rejected} envelope.
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Set decimal-separator / time-zone / condition-language / model label / annotation / "
                + "roles / add a display locale (at least one required).")
final class ConfigModifyCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--decimal-separator", paramLabel = "<sep>",
            description = "the decimal separator, e.g. \".\" or \",\"")
    String decimalSeparator;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--time-zone", paramLabel = "<tz>",
            description = "the time zone, e.g. Europe/Berlin or UTC")
    String timeZone;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--condition-language", paramLabel = "<code>",
            description = "the condition language code, e.g. en_US or de_DE")
    String conditionLanguage;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--label", paramLabel = "<locale>=<text>",
            description = "set a per-locale model label (repeatable), e.g. --label en_US=Permit")
    Map<String, String> labels = new LinkedHashMap<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--annotation", paramLabel = "<name>=<value>",
            description = "set a model-level annotation (repeatable), e.g. --annotation owner=permits-team")
    Map<String, String> annotations = new LinkedHashMap<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--role", paramLabel = "<name>",
            description = "add an access-control role to the model's `roles` header annotation (repeatable). "
                    + "MERGES with the existing roles (a one-role edit keeps the rest); the `workspace roles` "
                    + "lint binds these to auth/roles.yaml")
    java.util.List<String> roles = new java.util.ArrayList<>();

    @Option(names = "--clear-roles", description = "remove the model's `roles` header annotation entirely")
    boolean clearRoles;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--supported-character", paramLabel = "<char-or-range>",
            description = "restrict allowed characters (repeatable): a char or a range like 'A-Z'. "
                    + "Replaces the whole set; pass none to leave it unchanged. e.g. --supported-character A-Z")
    java.util.List<String> supportedCharacters = new java.util.ArrayList<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--comment", paramLabel = "<text>",
            description = "set the model-level comment (a free-text model note / description)")
    String comment;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--add-locale", paramLabel = "<code>",
            description = "register a display locale on the model (repeatable), e.g. --add-locale de_DE — "
                    + "appends to the header.locales registry. The kernel then requires every rule/computation "
                    + "to carry a message for it, so provision those first (or in the same `apply`)")
    java.util.List<String> addLocales = new java.util.ArrayList<>();

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
        if (decimalSeparator == null && timeZone == null && conditionLanguage == null
                && labels.isEmpty() && annotations.isEmpty() && supportedCharacters.isEmpty() && comment == null
                && addLocales.isEmpty() && roles.isEmpty() && !clearRoles) {
            return fail("nothing to change — pass at least one of --decimal-separator, --time-zone, "
                    + "--condition-language, --label, --annotation, --supported-character, --comment, --add-locale, "
                    + "--role, --clear-roles");
        }
        if (!roles.isEmpty() && clearRoles) {
            return fail("pass either --role <name> (add roles) or --clear-roles, not both");
        }
        String modelJson;
        try {
            modelJson = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        IDocumentModelReferenceResolver resolver = workspace.resolverFor(model);
        // pre-check the one scalar the kernel stores without validating (AP3); bad time-zone/locale re-bind-reject.
        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.decimalSeparatorValid(decimalSeparator))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("config", "modify", refusal.get());
        }
        ObjectNode changed = MAPPER.createObjectNode();
        String resultJson;
        try {
            ModelDocument doc = ModelDocument.read(modelJson);
            if (decimalSeparator != null) {
                doc.setDecimalSeparator(decimalSeparator);
                changed.put("decimalSeparator", decimalSeparator);
            }
            if (timeZone != null) {
                doc.setTimeZone(timeZone);
                changed.put("timeZone", timeZone);
            }
            if (conditionLanguage != null) {
                doc.setConditionLanguage(conditionLanguage);
                changed.put("conditionLanguage", conditionLanguage);
            }
            labels.forEach((locale, text) -> {
                doc.setModelLabel(locale, text);
                changed.put("label." + locale, text);
            });
            annotations.forEach((name, value) -> {
                doc.setModelAnnotation(name, value);
                changed.put("annotation." + name, value);
            });
            if (!supportedCharacters.isEmpty()) {
                doc.setSupportedCharacters(supportedCharacters);
                changed.put("supportedCharacters", String.join(" ", supportedCharacters));
            }
            if (comment != null) {
                doc.setModelComment(comment);
                changed.put("comment", comment);
            }
            ArrayNode addedLocales = MAPPER.createArrayNode();
            for (String code : addLocales) {
                if (!doc.locales().contains(code)) {
                    doc.addLocale(code);
                    addedLocales.add(code);
                }
            }
            if (!addedLocales.isEmpty()) {
                changed.set("addedLocales", addedLocales);
            }
            if (!roles.isEmpty()) {
                // MERGE into the comma-separated `roles` annotation (dedupe, keep order) — a one-role edit
                // keeps the rest (the G17 rule), unlike a raw `--annotation roles=…` that clobbers the list
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
                String existing = doc.modelAnnotations().get("roles");
                if (existing != null && !existing.isBlank()) {
                    for (String r : existing.split(",")) {
                        if (!r.isBlank()) {
                            merged.add(r.trim());
                        }
                    }
                }
                merged.addAll(roles);
                String joined = String.join(",", merged);
                doc.setModelAnnotation("roles", joined);
                changed.put("roles", joined);
            }
            if (clearRoles) {
                doc.removeModelAnnotation("roles");
                changed.putNull("roles");
            }
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("config", "modify", "the config change is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("config", "modify", "would change document config", changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("config", "modify", "changed document config", changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
