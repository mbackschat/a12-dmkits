package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The workspace roles lint (FEATURE-GAPS R3) — resolve each model's {@code roles} header annotation against
 * the workspace {@code roles.yaml} ({@link RolesFile}) and report the cross-file inconsistencies. A
 * <b>modelkit structure-consistency check</b> (a sibling of the kernel structural checks, <em>not</em> a rule
 * {@code MVK_*}): the kernel never sees {@code roles.yaml}, so this linkage is modelkit's to make.
 *
 * <p>It mirrors the three states the A12 SME enforces and <b>adds the one it doesn't</b>:
 * <ul>
 *   <li><b>{@code UNDEFINED_ROLE}</b> (ERROR, the flagship) — a model gates to a role the file doesn't
 *       declare. The SME explicitly permits this ("additional roles can be specified"), so a dangling role
 *       reference ships silently today; this is the value R3 adds.</li>
 *   <li><b>{@code MISSING_ROLE_ASSIGNMENT}</b> (ERROR) — a roles file is present but a model carries no
 *       {@code roles} annotation (the SME requires every model to be gated once a file exists).</li>
 *   <li><b>{@code NO_ROLES_FILE}</b> (WARNING) — a model declares roles but no roles file was found.</li>
 *   <li><b>{@code MULTIPLE_ROLES_FILES}</b> (ERROR) — more than one roles file (the SME blocks the
 *       workspace); which one defines the roles is ambiguous, so name-checking is skipped.</li>
 * </ul>
 *
 * <p>{@link #resolve} locates the file (the {@code auth/roles.yaml} SME convention, then the project-template
 * {@code import/auth/roles.yaml}, then the Preview-App {@code user/access-rights.yaml}; an explicit override
 * bypasses discovery); {@link #check} is the pure verdict over a scanned model set + that resolution.
 */
public final class WorkspaceRoles {

    private WorkspaceRoles() {
    }

    /** Conventional roles-file locations relative to a workspace root, in precedence order (CONVENTIONS sweep). */
    private static final List<String> CONVENTIONS =
            List.of("auth/roles.yaml", "import/auth/roles.yaml", "user/access-rights.yaml");

    /**
     * The outcome of locating the workspace roles file: the file(s) found and, when exactly one was found,
     * its parsed content. Zero files → {@link #found()} false; more than one → {@link #multiple()} true (and
     * {@link #roles()} empty, since which file governs is ambiguous).
     */
    public record Resolution(List<Path> files, Optional<RolesFile> roles) {
        public Resolution {
            files = List.copyOf(files);
        }

        /** True iff at least one roles file was found. */
        public boolean found() {
            return !files.isEmpty();
        }

        /** True iff more than one roles file was found (the SME-blocking state). */
        public boolean multiple() {
            return files.size() > 1;
        }
    }

    /** One cross-file roles inconsistency — the modelkit-native peer of a kernel diagnostic (not an {@code MVK_*}). */
    public record RoleFinding(Severity severity, Code code, String message,
            Optional<String> modelId, Optional<String> modelPath, Optional<String> role) {

        /** A finding's blocking weight — only an {@link #ERROR} fails the workspace gate. */
        public enum Severity { ERROR, WARNING }

        /** The class of inconsistency (a stable, machine-readable discriminator). */
        public enum Code { UNDEFINED_ROLE, MISSING_ROLE_ASSIGNMENT, NO_ROLES_FILE, MULTIPLE_ROLES_FILES }

        static RoleFinding undefinedRole(Workspace.Model m, String role) {
            return new RoleFinding(Severity.ERROR, Code.UNDEFINED_ROLE,
                    "role '" + role + "' gating " + m.id() + " is not defined in the workspace roles file",
                    Optional.of(m.id()), Optional.of(m.path()), Optional.of(role));
        }

        static RoleFinding missingAssignment(Workspace.Model m) {
            return new RoleFinding(Severity.ERROR, Code.MISSING_ROLE_ASSIGNMENT,
                    m.id() + " has no roles annotation, but the workspace has a roles file "
                            + "(every model must be gated once a roles file exists)",
                    Optional.of(m.id()), Optional.of(m.path()), Optional.empty());
        }

        static RoleFinding noRolesFile() {
            return new RoleFinding(Severity.WARNING, Code.NO_ROLES_FILE,
                    "models declare roles but no workspace roles file was found "
                            + "(expected auth/roles.yaml beside models/)",
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        static RoleFinding multipleRolesFiles(List<Path> files) {
            return new RoleFinding(Severity.ERROR, Code.MULTIPLE_ROLES_FILES,
                    "found " + files.size() + " roles files — a workspace must have exactly one: " + files,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** The conventional-location roles files that exist under {@code workspaceRoot}, in precedence order. */
    public static List<Path> discover(Path workspaceRoot) {
        List<Path> found = new ArrayList<>();
        for (String convention : CONVENTIONS) {
            Path candidate = workspaceRoot.resolve(convention);
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Locate (and, when unambiguous, parse) the workspace roles file. {@code override}, when present, is used
     * verbatim and bypasses discovery. A single discovered/overridden file is parsed into {@link
     * Resolution#roles()}; zero or several leave it empty.
     *
     * @throws IllegalArgumentException if the resolved single file isn't a parseable {@code roles.yaml}
     * @throws UncheckedIOException     if it can't be read
     */
    public static Resolution resolve(Path workspaceRoot, Optional<Path> override) {
        List<Path> files = override.map(List::of).orElseGet(() -> discover(workspaceRoot));
        Optional<RolesFile> roles = files.size() == 1 ? Optional.of(parse(files.get(0))) : Optional.empty();
        return new Resolution(files, roles);
    }

    /**
     * The roles verdict over a scanned model set and a {@link #resolve resolution} — the ordered findings
     * (model order; per-model, gating-role order). Empty means every gated role is defined and every model is
     * gated. Pure: no I/O, no kernel.
     */
    public static List<RoleFinding> check(List<Workspace.Model> models, Resolution resolution) {
        List<RoleFinding> findings = new ArrayList<>();
        if (resolution.multiple()) {
            findings.add(RoleFinding.multipleRolesFiles(resolution.files()));
            return List.copyOf(findings); // ambiguous which file governs — don't name-check against a guess
        }
        if (!resolution.found()) {
            boolean anyGated = models.stream().anyMatch(m -> !m.roles().isEmpty());
            if (anyGated) {
                findings.add(RoleFinding.noRolesFile());
            }
            return List.copyOf(findings);
        }
        RolesFile roles = resolution.roles().orElseThrow();
        for (Workspace.Model m : models) {
            if (m.roles().isEmpty()) {
                findings.add(RoleFinding.missingAssignment(m));
                continue;
            }
            for (String role : m.roles()) {
                if (!roles.defines(role)) {
                    findings.add(RoleFinding.undefinedRole(m, role));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static RolesFile parse(Path file) {
        try {
            return RolesFile.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read roles file " + file, e);
        }
    }
}
