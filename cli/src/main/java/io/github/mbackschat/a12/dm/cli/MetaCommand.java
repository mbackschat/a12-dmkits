package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool meta <ref>} — read or edit an element's <b>label + descriptions + annotations</b> (the
 * kernel's {@code ILabelled}/{@code IDescribed}/{@code IAnnotated}). {@code <ref>} is any describable: a
 * field/group/rule by full name-path, or a type definition by id. With <b>no write flags</b> it reads (the
 * current label + internal/external descriptions + annotations into {@code data}); with write flags it
 * sets/removes them, re-binds through the kernel, and writes back ({@code -o}/{@code --dry-run}). The display
 * {@code --label} applies only to a field/group (the only describables that carry one); on a
 * rule/computation/type-def it fails with a clear message. A reference that doesn't exist is a {@code refused}
 * envelope ({@code RK_NO_SUCH_ELEMENT}). (The model-level comment lives on {@code config modify --comment};
 * inline {@code ;;} condition comments are authored in the rule/computation DSL.)
 */
@Command(name = "meta", mixinStandardHelpOptions = true,
        description = "Read or edit an element's label + descriptions + annotations (field/group/rule by path, type-def by id).")
final class MetaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.ELEMENT_REF)
    @Parameters(index = "0", paramLabel = "<ref>",
            description = "the element's full name-path (field/group/rule) or a type-definition id")
    String ref;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--label", paramLabel = "<locale>=<text>",
            description = "set the display label for a locale (repeatable; field/group only). MERGES with the "
                    + "element's existing labels, so a one-locale edit keeps the rest")
    Map<String, String> label = new LinkedHashMap<>();

    // T1: meta accepts the rule/group modify vocabulary as discoverable aliases (--internal-description,
    // --external-description, --annotation, and the two removal peers), so an agent who learned the modify
    // flags reaches for the same names here and they work.
    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = {"--internal", "--internal-description"}, paramLabel = "<locale>=<text>",
            description = "set the INTERNAL description — a maintenance/technical note shown read-only to Form modelers, never to end users — for a locale (repeatable), e.g. --internal en_US=\"…\"")
    Map<String, String> internal = new LinkedHashMap<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = {"--external", "--external-description"}, paramLabel = "<locale>=<text>",
            description = "set the EXTERNAL description — the end-user-facing hint shown beside the field in the form UI — for a locale (repeatable)")
    Map<String, String> external = new LinkedHashMap<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = {"--annotate", "--annotation"}, paramLabel = "<name>=<value>",
            description = "set a name/value annotation (repeatable), e.g. --annotate owner=team")
    Map<String, String> annotate = new LinkedHashMap<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = {"--remove-internal", "--remove-internal-description"}, paramLabel = "<locale>",
            description = "remove the internal description for a locale")
    List<String> removeInternal = new ArrayList<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = {"--remove-external", "--remove-external-description"}, paramLabel = "<locale>",
            description = "remove the external description for a locale")
    List<String> removeExternal = new ArrayList<>();

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--remove-annotation", paramLabel = "<name>", description = "remove the named annotation")
    List<String> removeAnnotation = new ArrayList<>();

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the result here instead of in place (overwriting the model)")
    String outputPath;

    @Option(names = "--dry-run", description = "preview only — write NOTHING")
    boolean dryRun;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    private boolean hasWrites() {
        return !label.isEmpty() || !internal.isEmpty() || !external.isEmpty() || !annotate.isEmpty()
                || !removeInternal.isEmpty() || !removeExternal.isEmpty() || !removeAnnotation.isEmpty();
    }

    @Override
    public Integer call() {
        String model = model();
        String modelJson = readModelJson(model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.elementExists(doc, ref))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse("meta", hasWrites() ? "modify" : "read", refusal.get());
        }

        if (!hasWrites()) {
            ObjectNode data = MAPPER.createObjectNode();
            // label rides only for a field/group (the only describables that carry one)
            doc.labels(ref).ifPresent(labels -> putMap(data.putObject("label"), labels));
            putMap(data.putObject("internalDescription"), doc.internalDescription(ref));
            putMap(data.putObject("externalDescription"), doc.externalDescription(ref));
            putMap(data.putObject("annotations"), doc.annotations(ref));
            ResultEnvelope env = ResultEnvelope.read("meta", "read", "read metadata of " + ref, true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        }

        if (!label.isEmpty()) {
            Optional<Map<String, String>> existing = doc.labels(ref);
            if (existing.isEmpty()) {
                return fail("a label applies only to a field or group; " + ref
                        + " is a rule/computation/type definition (it has no display label)");
            }
        }

        ObjectNode changed = MAPPER.createObjectNode();
        String resultJson;
        try {
            if (!label.isEmpty()) {
                // MERGE with the element's existing labels so a one-locale edit keeps the rest (G17 rule);
                // setLabel still enforces full locale coverage, so an under-specified fresh label fails loudly
                Map<String, String> merged = new LinkedHashMap<>(doc.labels(ref).orElseThrow());
                merged.putAll(label);
                doc.setLabel(ref, merged);
                label.forEach((loc, text) -> changed.put("label." + loc, text));
            }
            internal.forEach((loc, text) -> { doc.setInternalDescription(ref, loc, text); changed.put("internal." + loc, text); });
            external.forEach((loc, text) -> { doc.setExternalDescription(ref, loc, text); changed.put("external." + loc, text); });
            annotate.forEach((name, value) -> { doc.setAnnotation(ref, name, value); changed.put("annotation." + name, value); });
            removeInternal.forEach(loc -> { doc.removeInternalDescription(ref, loc); changed.put("removed.internal." + loc, true); });
            removeExternal.forEach(loc -> { doc.removeExternalDescription(ref, loc); changed.put("removed.external." + loc, true); });
            removeAnnotation.forEach(name -> { doc.removeAnnotation(ref, name); changed.put("removed.annotation." + name, true); });
            resultJson = doc.validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("meta", "modify", "the metadata change is rejected by the kernel", e);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        if (dryRun) {
            ResultEnvelope env = ResultEnvelope.preview("meta", "modify", "would change metadata of " + ref, changed);
            out().print(env.toJsonString());
            return env.exitCode();
        }
        String dest = outputPath != null ? outputPath : model;
        try {
            Files.writeString(Path.of(dest), resultJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ResultEnvelope env = ResultEnvelope.applied("meta", "modify", "changed metadata of " + ref, changed, dest);
        out().print(env.toJsonString());
        return env.exitCode();
    }

    private static void putMap(ObjectNode target, Map<String, String> entries) {
        entries.forEach(target::put);
    }
}
