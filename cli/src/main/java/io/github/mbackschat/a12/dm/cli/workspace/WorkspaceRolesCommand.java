package io.github.mbackschat.a12.dm.cli.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.modelkit.RolesFile;
import io.github.mbackschat.a12.dm.modelkit.UsersFile;
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
 * the file doesn't declare), which even the A12 SME doesn't catch. <b>Purely advisory and never blocking:</b>
 * access-control config is often a dev seed or owned by an external IdP, so every finding is a WARNING (or a
 * softer INFO) — never an error — and the verb <b>always exits 0</b>. It informs a BA / agent; it never stops
 * them working on the models.
 *
 * <p>The {@code data} payload surfaces the workspace's access-control picture: the resolved {@code rolesFile},
 * the {@code definedRoles} (+ their access rights), each model's gating {@code roles}, and the {@code
 * findings} (with {@code warnings}/{@code infos} counts). The roles file is discovered under the workspace
 * root ({@code auth/roles.yaml}, then {@code import/auth/roles.yaml}, then {@code user/access-rights.yaml});
 * {@code --roles} overrides discovery. Models are scanned recursively — a workspace is a tree and roles gate
 * it workspace-wide (unlike {@code list}/{@code graph}, which mimic {@code ls}).
 */
@Command(name = "roles", mixinStandardHelpOptions = true,
        description = "Lint a workspace's roles. The contract: a model is gated by a `roles` header ANNOTATION "
                + "(value = comma-separated role names); `auth/roles.yaml` declares the roles; `auth/users.yaml` "
                + "lists users as `username` + `authorities` (the authorities are the edge into roles.yaml). The "
                + "lint resolves that triangle and flags undefined roles / authorities, unreachable + ungated "
                + "models, missing or multiple roles files. Advisory — warnings only, never blocks (always exits 0).")
final class WorkspaceRolesCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
            description = "the workspace root (the directory holding auth/roles.yaml beside models/) (defaults to -w/--workspace, then $DMTOOL_WORKSPACE)")
    String dir;

    @Option(names = "--roles", paramLabel = "<roles.yaml>",
            description = "use this roles file instead of discovering one under the workspace root")
    String rolesOverride;

    @Option(names = "--users", paramLabel = "<users.yaml>",
            description = "use this users file instead of discovering one under the workspace root")
    String usersOverride;

    @Override
    public Integer call() {
        dir = workspaceDir(dir).orElse(null);
        if (dir == null) {
            return fail("pass a workspace directory positionally (dmtool workspace <op> <dir>), or set -w/--workspace (or $DMTOOL_WORKSPACE)");
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return fail("not a directory: " + dir);
        }

        WorkspaceRoles.Resolution resolution;
        try {
            resolution = WorkspaceRoles.resolve(root,
                    Optional.ofNullable(rolesOverride).map(Path::of),
                    Optional.ofNullable(usersOverride).map(Path::of));
        } catch (java.io.UncheckedIOException unreadable) {
            // Split from the parse failure below: `resolve` documents two distinct throws, and one catch over
            // both reported an unreadable file and an unparseable one identically. A file the caller named that
            // cannot be READ is the same class as every other unreadable input (G61), so it gets that code; a
            // file that parsed and was rejected stays on the local-text channel the readers deliberately use.
            String named = rolesOverride != null ? "--roles <file>"
                    // Not spelled with the `.dmtool` directory prefix: `CommandReferencesResolveTest` reads
                    // "dmtool <word>" as a command reference, and this label is a file location, not a verb.
                    : usersOverride != null ? "--users <file>" : "the workspace access-control config";
            return refuseUnreadableInput(named,
                    rolesOverride != null ? rolesOverride : usersOverride != null ? usersOverride : dir,
                    unreadable.getCause() != null ? unreadable.getCause() : new java.io.IOException(
                            unreadable.getMessage()));
        } catch (RuntimeException unparseable) {
            return fail("cannot read access-control config: " + unparseable.getMessage());
        }

        List<Workspace.Model> models;
        try {
            models = Workspace.scan(root, true); // a workspace is a tree — roles gate it workspace-wide
        } catch (RuntimeException e) {
            return fail("scan failed: " + e.getMessage());
        }

        List<RoleFinding> findings = WorkspaceRoles.check(models, resolution);
        long warnings = findings.stream().filter(f -> f.severity() == RoleFinding.Severity.WARNING).count();
        long infos = findings.size() - warnings;

        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", dir);
        data.put("rolesFile", resolution.rolesFiles().size() == 1 ? display(root, resolution.rolesFiles().get(0)) : null);
        data.put("usersFile", resolution.usersFiles().size() == 1 ? display(root, resolution.usersFiles().get(0)) : null);
        data.set("definedRoles", definedRoles(resolution));
        data.set("users", usersJson(resolution));
        data.put("modelCount", models.size());
        data.set("models", modelsJson(models));
        data.put("warnings", warnings);
        data.put("infos", infos);
        data.set("findings", findingsJson(findings));

        String summary = summary(resolution, models.size(), warnings, infos);
        // Advisory only — never blocks. As a QUERY that is now STRUCTURAL: the family holds no verdict, so
        // exitCode() cannot return anything but 0, rather than relying on a hard-coded `valid:true`.
        ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), summary, data,
                ConsistencyEvidence.none());
        out().print(env.toJsonString());
        return env.exitCode(); // always 0 — a roles finding informs, never gates
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

    private ArrayNode usersJson(WorkspaceRoles.Resolution resolution) {
        ArrayNode arr = MAPPER.createArrayNode();
        resolution.users().ifPresent(users -> {
            for (UsersFile.User u : users.users()) {
                ObjectNode n = arr.addObject();
                n.put("username", u.username());
                ArrayNode auth = n.putArray("authorities");
                u.authorities().forEach(auth::add);
            }
        });
        return arr;
    }

    private ArrayNode findingsJson(List<RoleFinding> findings) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (RoleFinding f : findings) {
            ObjectNode n = arr.addObject();
            n.put("severity", f.severity().name());
            n.put("code", f.code().name());
            n.put("message", f.message());
            n.put("fix", f.fix());
            f.modelId().ifPresent(v -> n.put("modelId", v));
            f.modelPath().ifPresent(v -> n.put("modelPath", v));
            f.role().ifPresent(v -> n.put("role", v));
            f.username().ifPresent(v -> n.put("username", v));
        }
        return arr;
    }

    /** A config-file path relative to the workspace root when it lives under it, else its full path. */
    private static String display(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    private static String summary(WorkspaceRoles.Resolution resolution, int modelCount, long warnings, long infos) {
        String files = resolution.multipleRolesFiles() ? "multiple roles files"
                : resolution.rolesFound() ? "1 roles file" : "no roles file";
        return files + ", " + modelCount + " model" + (modelCount == 1 ? "" : "s") + " — "
                + warnings + " warning" + (warnings == 1 ? "" : "s") + ", " + infos + " info" + (infos == 1 ? "" : "s")
                + " (advisory)";
    }
}
