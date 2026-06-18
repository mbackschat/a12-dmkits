package io.github.mbackschat.a12.dm.cli.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.modelkit.RolesFile;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles;
import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles.RoleFinding;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool workspace roles <dir>} — the workspace roles lint (FEATURE-GAPS R3). Resolve every model's
 * {@code roles} header annotation against the workspace {@code roles.yaml} and report the cross-file
 * inconsistencies the kernel can't see — the flagship being an <b>undefined role</b> (a model gates to a role
 * the file doesn't declare), which even the A12 SME doesn't catch. A read (never mutates), but it <b>gates</b>:
 * it exits 1 if any finding is an ERROR (undefined role / a model left ungated when a roles file exists /
 * multiple roles files), 0 otherwise — so a pre-commit hook or CI step can call it directly.
 *
 * <p>The {@code data} payload surfaces the workspace's access-control picture: the resolved {@code rolesFile},
 * the {@code definedRoles} (+ their access rights), each model's gating {@code roles}, and the {@code
 * findings}. The roles file is discovered under the workspace root ({@code auth/roles.yaml}, then {@code
 * import/auth/roles.yaml}, then {@code user/access-rights.yaml}); {@code --roles} overrides discovery. Models
 * are scanned recursively — a workspace is a tree and roles gate it workspace-wide (unlike {@code list}/{@code
 * graph}, which mimic {@code ls}).
 */
@Command(name = "roles", mixinStandardHelpOptions = true,
        description = "Lint a workspace's roles: resolve each model's `roles` annotation against roles.yaml; "
                + "flag undefined roles (+ ungated models / missing or multiple roles files); exit 1 on any ERROR.")
final class WorkspaceRolesCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<dir>",
            description = "the workspace root (the directory holding auth/roles.yaml beside models/)")
    String dir;

    @Option(names = "--roles", paramLabel = "<roles.yaml>",
            description = "use this roles file instead of discovering one under the workspace root")
    String rolesOverride;

    @Override
    public Integer call() {
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return fail("not a directory: " + dir);
        }

        WorkspaceRoles.Resolution resolution;
        try {
            resolution = WorkspaceRoles.resolve(root,
                    java.util.Optional.ofNullable(rolesOverride).map(Path::of));
        } catch (RuntimeException badRolesFile) {
            return fail("cannot read roles file: " + badRolesFile.getMessage());
        }

        List<Workspace.Model> models;
        try {
            models = Workspace.scan(root, true); // a workspace is a tree — roles gate it workspace-wide
        } catch (RuntimeException e) {
            return fail("scan failed: " + e.getMessage());
        }

        List<RoleFinding> findings = WorkspaceRoles.check(models, resolution);
        long errors = findings.stream().filter(f -> f.severity() == RoleFinding.Severity.ERROR).count();
        long warnings = findings.size() - errors;

        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", dir);
        data.put("rolesFile", resolution.files().size() == 1 ? display(root, resolution.files().get(0)) : null);
        data.set("definedRoles", definedRoles(resolution));
        data.put("modelCount", models.size());
        data.set("models", modelsJson(models));
        data.put("errors", errors);
        data.put("warnings", warnings);
        data.set("findings", findingsJson(findings));

        String summary = summary(resolution, models.size(), errors, warnings);
        ResultEnvelope env = ResultEnvelope.read("workspace", "roles", summary, errors == 0, List.of(), data);
        out().print(env.toJsonString());
        return env.exitCode(); // 0 when no ERROR finding, 1 otherwise (the gate)
    }

    private ArrayNode definedRoles(WorkspaceRoles.Resolution resolution) {
        ArrayNode arr = MAPPER.createArrayNode();
        resolution.roles().ifPresent(roles -> {
            for (RolesFile.Role r : roles.roles()) {
                ObjectNode n = arr.addObject();
                n.put("name", r.name());
                if (!r.description().isBlank()) {
                    n.put("description", r.description());
                }
                ArrayNode rights = n.putArray("accessRights");
                r.accessRights().forEach(rights::add);
            }
        });
        return arr;
    }

    private ArrayNode modelsJson(List<Workspace.Model> models) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Workspace.Model m : models) {
            ObjectNode n = arr.addObject();
            n.put("id", m.id());
            n.put("path", m.path());
            ArrayNode roles = n.putArray("roles");
            m.roles().forEach(roles::add);
        }
        return arr;
    }

    private ArrayNode findingsJson(List<RoleFinding> findings) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (RoleFinding f : findings) {
            ObjectNode n = arr.addObject();
            n.put("severity", f.severity().name());
            n.put("code", f.code().name());
            n.put("message", f.message());
            f.modelId().ifPresent(v -> n.put("modelId", v));
            f.modelPath().ifPresent(v -> n.put("modelPath", v));
            f.role().ifPresent(v -> n.put("role", v));
        }
        return arr;
    }

    /** A roles-file path relative to the workspace root when it lives under it, else its full path. */
    private static String display(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    private static String summary(WorkspaceRoles.Resolution resolution, int modelCount, long errors, long warnings) {
        String files = resolution.multiple() ? "multiple roles files"
                : resolution.found() ? "1 roles file" : "no roles file";
        return files + ", " + modelCount + " model" + (modelCount == 1 ? "" : "s") + " — "
                + errors + " error" + (errors == 1 ? "" : "s") + ", " + warnings + " warning" + (warnings == 1 ? "" : "s");
    }
}
