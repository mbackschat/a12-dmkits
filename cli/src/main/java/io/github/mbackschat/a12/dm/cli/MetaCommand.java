package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool meta <ref>} — read or edit an element's <b>label + descriptions + annotations</b> (the
 * kernel's {@code ILabelled}/{@code IDescribed}/{@code IAnnotated}). {@code <ref>} is any describable: a
 * field/group/rule/computation by full name-path, or a type definition by id. With <b>no write flags</b> it reads (the
 * current label + internal/external descriptions + annotations into {@code data}); with write flags it
 * sets/removes them, re-binds through the kernel, and writes back ({@code -o}/{@code --dry-run}). The display
 * {@code --label} applies only to a field/group (the only describables that carry one); on a
 * rule/computation/type-def it fails with a clear message. A reference that doesn't exist is a {@code refused}
 * envelope ({@code RK_NO_SUCH_ELEMENT}). (The model-level comment lives on {@code config modify --comment};
 * inline {@code ;;} condition comments are authored in the rule/computation DSL.)
 */
@Command(name = "meta", mixinStandardHelpOptions = true,
        description = "Read or edit an element's label + descriptions + annotations "
                + "(field/group/rule/computation by path, type-def by id).")
final class MetaCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.ELEMENT_REF)
    @Parameters(index = "0", paramLabel = "<ref>",
            description = "the element's full name-path (field/group/rule/computation) or a type-definition id")
    String ref;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--label", paramLabel = "<locale>=<text>",
            description = "set the display label for a locale (repeatable; field/group only). MERGES with the "
                    + "element's existing labels, so a one-locale edit keeps the rest")
    Map<String, String> label = new LinkedHashMap<>();

    // G45: the explicit long names are the manifest's canonical spellings; the shorter forms remain ergonomic
    // synonyms on this ONE metadata route, not aliases for any target-specific modifier.
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

    private MetaModification modification() {
        return new MetaModification(label, annotate, removeAnnotation, internal, external,
                removeInternal, removeExternal);
    }

    @Override
    public Integer call() {
        MetaModification modification = modification();
        boolean hasWrites = !modification.isEmpty();
        if (!hasWrites && optionMatched("--dry-run", "--output")) {
            return fail("--dry-run and -o/--output require a metadata change");
        }
        String model = model();
        String modelJson = readModelJson(
                hasWrites ? ResultIdentity.META_MODIFY : ResultIdentity.META_READ, model);
        if (modelJson == null) {
            return 2;
        }
        ModelResolver resolver = workspace.resolverFor(model);
        ModelDocument doc = ModelDocument.read(modelJson);

        Optional<Refusal> refusal = new SafetyGate()
                .require(SafetyChecks.elementExists(doc, ref))
                .require(SafetyChecks.labelSupported(doc, ref, !modification.labels().isEmpty()))
                .firstFailure();
        if (refusal.isPresent()) {
            return refuse(hasWrites ? ResultIdentity.META_MODIFY : ResultIdentity.META_READ, refusal.get());
        }

        if (!hasWrites) {
            ObjectNode data = MAPPER.createObjectNode();
            // label rides only for a field/group (the only describables that carry one)
            doc.labels(ref).ifPresent(labels -> putMap(data.putObject("label"), labels));
            putMap(data.putObject("internalDescription"), doc.internalDescription(ref));
            putMap(data.putObject("externalDescription"), doc.externalDescription(ref));
            putMap(data.putObject("annotations"), doc.annotations(ref));
            // ModelDocument.read parses and indexes; no consistency gate runs on this path, so no verdict and no
            // provenance are claimed (this verb was one of the seven measured reporting a fabricated valid:true).
            ResultEnvelope env = ResultEnvelope.queryResult(ResultIdentity.META_READ, "read metadata of " + ref, data,
                    ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        }

        ObjectNode changed = MAPPER.createObjectNode();
        // Declared outside the try so the gate's receipt is still in scope for the envelope below.
        EditSession session = EditSession.read(modelJson, resolver);
        String resultJson;
        try {
            modification.applyTo(ref, doc, session, changed);
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection(ResultIdentity.META_MODIFY,
                    "the metadata change is rejected by the kernel", e);
        }
        return emitMutation(ModelWrite.of(ResultIdentity.META_MODIFY)
                .summaries("would change metadata of " + ref,
                        "changed metadata of " + ref)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(session))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }

    private static void putMap(ObjectNode target, Map<String, String> entries) {
        entries.forEach(target::put);
    }
}
