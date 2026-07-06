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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool group modify <groupPath>} — change an existing group's <b>repeatability</b> and/or its
 * <b>index field</b> in place and write the model back (in place; {@code -o} redirects, {@code --dry-run}
 * previews). {@code --repeatable <max>} (≥2) makes a single group a repetition list, {@code --non-repeatable}
 * collapses a list back to one; {@code --index-field <fieldName>} designates a direct-child field as the
 * repetition row-key (arms {@code RepetitionNotUnique} + parallel iteration; requires a repeatable group),
 * {@code --clear-index-field} removes it; {@code --sort-field <child>[:asc|desc]} (repeatable) declares the
 * row display sort, {@code --clear-sort-fields} removes it; and the group's <b>metadata</b> — per-locale
 * {@code --label}, {@code --annotation}/{@code --remove-annotation}, and per-locale
 * {@code --internal-description}/{@code --external-description} (G38, the group peer of the field-metadata edits
 * {@code field modify} carries). At least one directive is required. The change is re-bound through the kernel
 * gate, so one that breaks the model (e.g. a {@code *}-wildcard rule that now has no list to range over) fails
 * loudly as a {@code rejected} envelope. (Repeatability was settable only at {@code group add} — G15; the index
 * field only via the builder — G24; sort fields — G28; group metadata — G38.)
 */
@Command(name = "modify", mixinStandardHelpOptions = true,
        description = "Change a group's repeatability (--repeatable <max> / --non-repeatable), index field "
                + "(--index-field <fieldName> / --clear-index-field), row sort (--sort-field "
                + "<child>[:asc|desc] / --clear-sort-fields), and/or metadata (--label / --annotation / "
                + "--remove-annotation / --internal-description / --external-description) in place.")
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

    @Option(names = "--index-field", paramLabel = "<fieldName>",
            description = "designate a direct-child field as the repetition row-key (the index field — arms "
                    + "RepetitionNotUnique + parallel iteration; requires a repeatable group)")
    String indexField;

    @Option(names = "--clear-index-field", description = "remove the group's index field")
    boolean clearIndexField;

    @Option(names = "--sort-field", paramLabel = "<child>[:asc|desc]",
            description = "declare a row display-sort key — a direct-child field, optionally :asc (default) or "
                    + ":desc; repeat for a multi-key sort (in order); requires a repeatable group. Replaces any "
                    + "existing sort (display/USB-only — not consumed by validation/computation)")
    List<String> sortFields = new ArrayList<>();

    @Option(names = "--clear-sort-fields", description = "remove the group's declared row sort fields")
    boolean clearSortFields;

    @Option(names = "--label", paramLabel = "<locale>=<text>",
            description = "set a per-locale group label (repeatable), e.g. --label en_US=Shipping")
    Map<String, String> labels = new LinkedHashMap<>();

    @Option(names = "--annotation", paramLabel = "<name>=<value>",
            description = "set a group annotation (repeatable), e.g. --annotation owner=fulfilment")
    Map<String, String> annotations = new LinkedHashMap<>();

    @Option(names = "--remove-annotation", paramLabel = "<name>",
            description = "remove a group annotation by name (repeatable; the inverse of --annotation)")
    List<String> removeAnnotations = new ArrayList<>();

    @Option(names = "--internal-description", paramLabel = "<locale>=<text>",
            description = "set a per-locale internal description (repeatable)")
    Map<String, String> internalDescriptions = new LinkedHashMap<>();

    @Option(names = "--external-description", paramLabel = "<locale>=<text>",
            description = "set a per-locale external description (repeatable)")
    Map<String, String> externalDescriptions = new LinkedHashMap<>();

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
        if (repeatable != null && nonRepeatable) {
            return fail("pass at most one of --repeatable <max> or --non-repeatable");
        }
        if (indexField != null && clearIndexField) {
            return fail("pass at most one of --index-field <fieldName> or --clear-index-field");
        }
        if (!sortFields.isEmpty() && clearSortFields) {
            return fail("pass at most one of --sort-field <child> or --clear-sort-fields");
        }
        boolean repeatabilityChange = repeatable != null || nonRepeatable;
        boolean indexChange = indexField != null || clearIndexField;
        boolean sortChange = !sortFields.isEmpty() || clearSortFields;
        boolean metadataChange = !labels.isEmpty() || !annotations.isEmpty() || !removeAnnotations.isEmpty()
                || !internalDescriptions.isEmpty() || !externalDescriptions.isEmpty();
        if (!repeatabilityChange && !indexChange && !sortChange && !metadataChange) {
            return fail("nothing to change — pass --repeatable/--non-repeatable, "
                    + "--index-field/--clear-index-field, --sort-field/--clear-sort-fields, and/or "
                    + "--label/--annotation/--remove-annotation/--internal-description/--external-description");
        }
        if (repeatable != null && repeatable < 2) {
            return fail("--repeatable <max> must be >= 2 (use --non-repeatable to make it a single group)");
        }
        List<ElementDraft.SortField> parsedSorts;
        try {
            parsedSorts = SortFieldArg.parseAll(sortFields);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        EditSession session = EditSession.read(modelJson, resolver);
        try {
            if (repeatable != null) {
                session.setRepeatable(group, repeatable);
            } else if (nonRepeatable) {
                session.setNonRepeatable(group);
            }
            if (indexField != null) {
                session.setIndexField(group, indexField);
            } else if (clearIndexField) {
                session.clearIndexField(group);
            }
            if (!parsedSorts.isEmpty()) {
                session.setSortFields(group, parsedSorts);
            } else if (clearSortFields) {
                session.clearSortFields(group);
            }
            if (!labels.isEmpty()) {
                session.setLabel(group, labels);
            }
            annotations.forEach((name, value) -> session.setAnnotation(group, name, value));
            removeAnnotations.forEach(name -> session.removeAnnotation(group, name));
            internalDescriptions.forEach((locale, text) -> session.setInternalDescription(group, locale, text));
            externalDescriptions.forEach((locale, text) -> session.setExternalDescription(group, locale, text));
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }

        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "modify", "the change left the model invalid", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("group", group);
        List<String> summaryParts = new ArrayList<>();
        if (repeatabilityChange) {
            changed.put("repeatable", repeatable != null);
            if (repeatable != null) {
                changed.put("maxRepetitions", repeatable);
                summaryParts.add("repeatable (max " + repeatable + ")");
            } else {
                summaryParts.add("non-repeating");
            }
        }
        if (indexField != null) {
            changed.put("indexField", indexField);
            summaryParts.add("index field " + indexField);
        } else if (clearIndexField) {
            changed.putNull("indexField");
            summaryParts.add("index field cleared");
        }
        if (!parsedSorts.isEmpty()) {
            ArrayNode sortArr = changed.putArray("sortFields");
            List<String> labels = new ArrayList<>();
            for (ElementDraft.SortField sf : parsedSorts) {
                ObjectNode n = sortArr.addObject();
                n.put("name", sf.fieldName());
                n.put("order", sf.order().name());
                labels.add(sf.fieldName() + " " + sf.order().name().toLowerCase(java.util.Locale.ROOT));
            }
            summaryParts.add("sort by " + String.join(", ", labels));
        } else if (clearSortFields) {
            changed.putArray("sortFields");
            summaryParts.add("sort fields cleared");
        }
        labels.forEach((locale, text) -> {
            changed.put("label." + locale, text);
            summaryParts.add("label[" + locale + "]");
        });
        annotations.forEach((name, value) -> {
            changed.put("annotation." + name, value);
            summaryParts.add("annotation " + name);
        });
        removeAnnotations.forEach(name -> {
            changed.putNull("annotation." + name);
            summaryParts.add("annotation " + name + " removed");
        });
        internalDescriptions.forEach((locale, text) -> {
            changed.put("internalDescription." + locale, text);
            summaryParts.add("internal-description[" + locale + "]");
        });
        externalDescriptions.forEach((locale, text) -> {
            changed.put("externalDescription." + locale, text);
            summaryParts.add("external-description[" + locale + "]");
        });
        String summary = "set " + group + ": " + String.join(", ", summaryParts);
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
