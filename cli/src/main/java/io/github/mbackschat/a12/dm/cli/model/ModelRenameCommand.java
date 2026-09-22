package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ModelWrite;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.Refusal;
import io.github.mbackschat.a12.dm.cli.SafetyChecks;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;

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

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model rename --to <newId>} — change the model's header {@code id} (its identity) and write
 * back (in place; {@code -o}/{@code --dry-run}). A <b>single-model</b> refactor with a cross-model
 * <b>safety gate</b> (CLI-SPEC §6): with a workspace ({@code -w}) it scans the siblings and <b>refuses</b>
 * (writing nothing) when one <em>includes</em>/imports the old id — the cross-model rename cascade is out of
 * scope (FEATURE-GAPS §4 OQ1), so repoint those includers first — and refuses a new id that <b>collides</b>
 * with a sibling ({@code RK_NAME_EXISTS}). Without {@code -w} there are no siblings to check, so it is a pure
 * single-model id edit. The result re-binds through the kernel; an invalid result is a {@code rejected}
 * envelope.
 */
@Command(name = "rename", mixinStandardHelpOptions = true,
        description = "Change the model's header id (single-model; refuses if a sibling model includes the old id "
                + "— a -w workspace sibling on the CLI, one of the `references` sent over MCP).")
final class ModelRenameCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParamKind(ParamKind.Kind.NEW_NAME)
    @Option(names = "--to", required = true, paramLabel = "<newId>", description = "the model's new header id")
    String newId;

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
        ModelDocument doc = ModelDocument.read(modelJson);
        String oldId = doc.id();
        if (newId.isBlank()) {
            return fail("--to <newId> must be a non-empty model id");
        }
        if (newId.equals(oldId)) {
            return fail("the model id is already '" + oldId + "' — nothing to rename");
        }

        // the cross-model safety gate: only checkable with a workspace (siblings to scan). Refuses — never
        // a best-effort cascade — if a sibling references the old id, or the new id collides.
        Refusal refusal = crossModelRefusal(model, oldId);
        if (refusal != null) {
            return refuse(refusal);
        }

        ModelResolver resolver = workspace.resolverFor(model);
        String resultJson;
        try {
            resultJson = doc.setModelId(newId).validate(resolver).toJson();
        } catch (ModelConsistencyException e) {
            return emitKernelRejection("the id change is rejected by the kernel", e);
        }
        ObjectNode changed = MAPPER.createObjectNode();
        changed.put("id", newId);
        changed.put("from", oldId);
        String summary = "renamed model id " + oldId + " → " + newId;
        return emitMutation(ModelWrite.of(resultIdentity())
                .summaries("would have " + summary,
                        summary)
                .changed(changed)
                .json(resultJson)
                .gatedBy(ConsistencyEvidence.of(doc))
                .dryRun(dryRun)
                .destination(outputPath, model));
    }

    /**
     * The cross-model gate: scan every {@code -w} workspace root for siblings; refuse on a new-id collision
     * ({@code RK_NAME_EXISTS}) or on a sibling that includes/imports the old id ({@code RK_REFERENCED}).
     * Returns {@code null} when safe (or when there is no workspace to scan).
     */
    private Refusal crossModelRefusal(String model, String oldId) {
        Map<String, String> idToFile = new LinkedHashMap<>();   // sibling id → relative path, for diagnostics
        List<String> includers = new ArrayList<>();
        boolean collision = false;
        for (Path root : workspace.roots(model)) {
            for (Workspace.Model m : Workspace.scan(root, true)) {
                if (m.id().equals(oldId)) {
                    continue;                                   // this very model (or a same-id copy) — skip
                }
                idToFile.putIfAbsent(m.id(), m.path());
                if (m.id().equals(newId)) {
                    collision = true;
                }
                boolean refsOld = m.includes().stream().anyMatch(r -> oldId.equals(r.ref()))
                        || m.typedefImports().stream().anyMatch(r -> oldId.equals(r.ref()));
                if (refsOld && !includers.contains(m.id())) {
                    includers.add(m.id());
                }
            }
        }
        if (collision) {
            return SafetyChecks.nameExists(newId, "model",
                    "another model in the workspace already has id '" + newId + "' — choose a different id");
        }
        if (!includers.isEmpty()) {
            ObjectNode data = MAPPER.createObjectNode();
            ArrayNode models = data.putObject("referencedBy").putArray("models");
            includers.forEach(models::add);
            Diagnostic diagnostic = Diagnostic.precheck(Optional.of(RkCode.REFERENCED.code()),
                    "model id '" + oldId + "' is referenced by " + includers.size()
                            + " other model(s); renaming it would dangle their include/import",
                    Diagnostic.Where.path(oldId),
                    Optional.of("repoint those models' include/import to the new id first "
                            + "(the cross-model rename cascade is out of scope), then rename"),
                    Optional.empty());
            return new Refusal("model id '" + oldId + "' is referenced — refusing to rename", data, diagnostic);
        }
        return null;
    }
}
