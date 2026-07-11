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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelResolver;

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

    // T1: the description-removal peers, matching the removal set the `meta` verb already offers.
    @Option(names = "--remove-internal-description", paramLabel = "<locale>",
            description = "remove the group's internal description for a locale (repeatable)")
    List<String> removeInternalDescriptions = new ArrayList<>();

    @Option(names = "--remove-external-description", paramLabel = "<locale>",
            description = "remove the group's external description for a locale (repeatable)")
    List<String> removeExternalDescriptions = new ArrayList<>();

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
        ModelResolver resolver = workspace.resolverFor(model);
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
        if (repeatable != null && repeatable < 2) {
            return fail("--repeatable <max> must be >= 2 (use --non-repeatable to make it a single group)");
        }
        List<ElementDraft.SortField> parsedSorts;
        try {
            parsedSorts = SortFieldArg.parseAll(sortFields);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        // T4: the setter cascade + `changed` report is single-sourced with the `apply` GROUP_MODIFY op.
        GroupModification mod = GroupModification.builder()
                .repeatable(repeatable).nonRepeatable(nonRepeatable).indexField(indexField)
                .clearIndexField(clearIndexField).sortFields(parsedSorts).clearSortFields(clearSortFields)
                .labels(labels).annotations(annotations).removeAnnotations(removeAnnotations)
                .internalDescriptions(internalDescriptions).externalDescriptions(externalDescriptions)
                .removeInternalDescriptions(removeInternalDescriptions)
                .removeExternalDescriptions(removeExternalDescriptions).build();
        if (mod.isEmpty()) {
            return fail("nothing to change — pass --repeatable/--non-repeatable, "
                    + "--index-field/--clear-index-field, --sort-field/--clear-sort-fields, and/or "
                    + "--label/--annotation/--remove-annotation/--internal-description/--external-description");
        }
        EditSession session = EditSession.read(modelJson, resolver);
        ObjectNode changed = MAPPER.createObjectNode();
        try {
            mod.applyTo(group, session, changed);
        } catch (RuntimeException e) {
            return fail(e.getMessage());
        }
        String resultJson;
        try {
            resultJson = session.validate().toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("group", "modify", "the change left the model invalid", e);
        }
        String summary = "set " + group + ": " + String.join(", ", mod.summaryParts());
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
