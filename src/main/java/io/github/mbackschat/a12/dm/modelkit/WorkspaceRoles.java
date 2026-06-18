package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The workspace roles lint (FEATURE-GAPS R3) — resolve each model's {@code roles} header annotation against
 * the workspace {@code roles.yaml} ({@link RolesFile}) and report the cross-file inconsistencies. A
 * <b>modelkit structure-consistency check</b> (a sibling of the kernel structural checks, <em>not</em> a rule
 * {@code MVK_*}): the kernel never sees {@code roles.yaml}, so this linkage is modelkit's to make.
 *
 * <p><b>Advisory, never blocking.</b> Access-control config is often a dev seed and/or owned by an external
 * IdP (a full-stack workspace has no users file at all), so a roles inconsistency must <em>inform</em> a BA /
 * agent, never <em>stop</em> them working on the models. Every finding is therefore a {@link Severity#WARNING}
 * (or a softer {@link Severity#INFO}) — <b>never an error, never a gate</b>. The verb always exits 0.
 *
 * <p>It surfaces the states the A12 SME treats as errors — but as warnings, deliberately more lenient (the SME
 * <em>enforces</em>; this tool <em>advises</em>):
 * <ul>
 *   <li><b>{@code UNDEFINED_ROLE}</b> (the flagship) — a model gates to a role the file doesn't declare. The
 *       SME explicitly permits this ("additional roles can be specified"), so a dangling role reference ships
 *       silently today; surfacing it is the value R3 adds.</li>
 *   <li><b>{@code MISSING_ROLE_ASSIGNMENT}</b> — a roles file is present but a model carries no {@code roles}
 *       annotation.</li>
 *   <li><b>{@code NO_ROLES_FILE}</b> — a model declares roles but no roles file was found.</li>
 *   <li><b>{@code MULTIPLE_ROLES_FILES}</b> — more than one roles file; which one defines the roles is
 *       ambiguous, so name-checking is skipped.</li>
 * </ul>
 *
 * <p>{@link #resolve} locates the file (the {@code auth/roles.yaml} SME convention, then the project-template
 * {@code import/auth/roles.yaml}, then the Preview-App {@code user/access-rights.yaml}; an explicit override
 * bypasses discovery); {@link #check} is the pure verdict over a scanned model set + that resolution.
 */
public final class WorkspaceRoles {

    private WorkspaceRoles() {
    }

    /** Conventional roles-file locations relative to a workspace root, in precedence order. */
    private static final List<String> ROLES_CONVENTIONS =
            List.of("auth/roles.yaml", "import/auth/roles.yaml", "user/access-rights.yaml");

    /** Conventional users-file locations — a single {@code users.yaml} list, or a {@code users/} directory of per-user files. */
    private static final List<String> USERS_CONVENTIONS =
            List.of("auth/users.yaml", "auth/users", "import/auth/users.yaml", "import/auth/users");

    /**
     * The outcome of locating the workspace access-control config: the roles file(s) and users file(s)/dir(s)
     * found, and — when exactly one of each was found — its parsed content. For each side: zero → not found;
     * more than one → ambiguous (the parsed content stays empty, since which file governs is unclear).
     */
    public record Resolution(List<Path> rolesFiles, Optional<RolesFile> roles,
            List<Path> usersFiles, Optional<UsersFile> users) {
        public Resolution {
            rolesFiles = List.copyOf(rolesFiles);
            usersFiles = List.copyOf(usersFiles);
        }

        /** True iff at least one roles file was found. */
        public boolean rolesFound() {
            return !rolesFiles.isEmpty();
        }

        /** True iff more than one roles file was found (the SME-blocking state). */
        public boolean multipleRolesFiles() {
            return rolesFiles.size() > 1;
        }

        /** True iff at least one users file/dir was found. */
        public boolean usersFound() {
            return !usersFiles.isEmpty();
        }
    }

    /**
     * One cross-file roles inconsistency — the modelkit-native peer of a kernel diagnostic (not an
     * {@code MVK_*}). Carries a {@code message} (the problem) <b>and a {@code fix}</b> (the concrete remedy),
     * parity with the {@code {meaning, fix}} shape of the {@code RK_*} diagnostic catalog — so a finding is
     * actionable, not just descriptive.
     */
    public record RoleFinding(Severity severity, Code code, String message, String fix,
            Optional<String> modelId, Optional<String> modelPath, Optional<String> role, Optional<String> username) {

        /**
         * A finding's weight — purely advisory ranking, <b>never blocking</b>: {@link #WARNING} for a real
         * cross-file inconsistency, {@link #INFO} for a softer tidiness note. There is deliberately no
         * {@code ERROR} — a roles/users finding never fails a gate (the verb always exits 0).
         */
        public enum Severity { WARNING, INFO }

        /** The class of inconsistency (a stable, machine-readable discriminator). */
        public enum Code {
            UNDEFINED_ROLE, MISSING_ROLE_ASSIGNMENT, NO_ROLES_FILE, MULTIPLE_ROLES_FILES,
            /** A user holds an authority (role name) the roles file doesn't declare — the users-edge twin (R4). */
            UNDEFINED_AUTHORITY,
            /** A model gates to roles no user holds — nobody can access it (reachability, R4). */
            UNREACHABLE_MODEL,
            /** A declared role no user holds and no model gates to — a dead definition (tidiness, R4, INFO). */
            ORPHAN_ROLE
        }

        static RoleFinding undefinedRole(Workspace.Model m, String role) {
            return new RoleFinding(Severity.WARNING, Code.UNDEFINED_ROLE,
                    "role '" + role + "' gating " + m.id() + " is not defined in the workspace roles file",
                    "declare '" + role + "' in the workspace roles file, or remove it from " + m.id()
                            + "'s roles annotation",
                    Optional.of(m.id()), Optional.of(m.path()), Optional.of(role), Optional.empty());
        }

        static RoleFinding missingAssignment(Workspace.Model m) {
            return new RoleFinding(Severity.WARNING, Code.MISSING_ROLE_ASSIGNMENT,
                    m.id() + " has no roles annotation, but the workspace has a roles file "
                            + "(every model must be gated once a roles file exists)",
                    "add a roles annotation to " + m.id() + " naming the role(s) that may access it "
                            + "(a defined role from the workspace roles file)",
                    Optional.of(m.id()), Optional.of(m.path()), Optional.empty(), Optional.empty());
        }

        static RoleFinding noRolesFile() {
            return new RoleFinding(Severity.WARNING, Code.NO_ROLES_FILE,
                    "models declare roles but no workspace roles file was found "
                            + "(expected auth/roles.yaml beside models/)",
                    "add a workspace roles file (auth/roles.yaml beside models/) declaring the roles the "
                            + "models use, or remove the models' roles annotations",
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        static RoleFinding multipleRolesFiles(List<Path> files) {
            return new RoleFinding(Severity.WARNING, Code.MULTIPLE_ROLES_FILES,
                    "found " + files.size() + " roles files — a workspace must have exactly one: " + files,
                    "keep exactly one roles file in the workspace; remove the extra(s): " + files,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        static RoleFinding undefinedAuthority(UsersFile.User user, String authority) {
            return new RoleFinding(Severity.WARNING, Code.UNDEFINED_AUTHORITY,
                    "user '" + user.username() + "' holds authority '" + authority
                            + "', which is not defined in the workspace roles file",
                    "declare '" + authority + "' in the workspace roles file, or remove it from "
                            + user.username() + "'s authorities",
                    Optional.empty(), Optional.empty(), Optional.of(authority), Optional.of(user.username()));
        }

        static RoleFinding unreachableModel(Workspace.Model m) {
            return new RoleFinding(Severity.WARNING, Code.UNREACHABLE_MODEL,
                    m.id() + " is gated to " + m.roles() + " but no user holds any of those roles — no one can access it",
                    "grant one of " + m.roles() + " to a user in the workspace users file, or change "
                            + m.id() + "'s roles",
                    Optional.of(m.id()), Optional.of(m.path()), Optional.empty(), Optional.empty());
        }

        static RoleFinding orphanRole(String role) {
            return new RoleFinding(Severity.INFO, Code.ORPHAN_ROLE,
                    "role '" + role + "' is declared but no user holds it and no model gates to it",
                    "assign '" + role + "' to a user or a model, or remove it from the workspace roles file",
                    Optional.empty(), Optional.empty(), Optional.of(role), Optional.empty());
        }
    }

    /** The conventional-location files that exist under {@code workspaceRoot}, in {@code conventions} order. */
    private static List<Path> discoverIn(Path workspaceRoot, List<String> conventions) {
        List<Path> found = new ArrayList<>();
        for (String convention : conventions) {
            Path candidate = workspaceRoot.resolve(convention);
            if (Files.isRegularFile(candidate) || Files.isDirectory(candidate)) {
                found.add(candidate);
            }
        }
        return List.copyOf(found);
    }

    /** The conventional-location roles files that exist under {@code workspaceRoot}, in precedence order. */
    public static List<Path> discoverRoles(Path workspaceRoot) {
        return discoverIn(workspaceRoot, ROLES_CONVENTIONS);
    }

    /**
     * Locate (and, when unambiguous, parse) the workspace roles + users config. An override, when present, is
     * used verbatim and bypasses discovery for that side. A single discovered/overridden roles file → {@link
     * Resolution#roles()}; a single users file/dir → {@link Resolution#users()}; zero or several leave the
     * respective parse empty.
     *
     * @throws IllegalArgumentException if a resolved single file isn't parseable
     * @throws UncheckedIOException     if it can't be read
     */
    public static Resolution resolve(Path workspaceRoot, Optional<Path> rolesOverride, Optional<Path> usersOverride) {
        List<Path> rolesFiles = rolesOverride.map(List::of).orElseGet(() -> discoverRoles(workspaceRoot));
        Optional<RolesFile> roles = rolesFiles.size() == 1
                ? Optional.of(parseRoles(rolesFiles.get(0))) : Optional.empty();

        List<Path> usersFiles = usersOverride.map(List::of)
                .orElseGet(() -> discoverIn(workspaceRoot, USERS_CONVENTIONS));
        Optional<UsersFile> users = usersFiles.size() == 1
                ? Optional.of(parseUsers(usersFiles.get(0))) : Optional.empty();

        return new Resolution(rolesFiles, roles, usersFiles, users);
    }

    /**
     * The verdict over a scanned model set and a {@link #resolve resolution} — the ordered advisory findings.
     * Roles findings come first (model order; per-model, gating-role order), then the users-edge findings
     * (per user, authority order). Empty means a clean workspace. Pure: no I/O, no kernel.
     */
    public static List<RoleFinding> check(List<Workspace.Model> models, Resolution resolution) {
        List<RoleFinding> findings = new ArrayList<>();
        if (resolution.multipleRolesFiles()) {
            findings.add(RoleFinding.multipleRolesFiles(resolution.rolesFiles()));
            return List.copyOf(findings); // ambiguous which file governs — don't name-check against a guess
        }
        if (!resolution.rolesFound()) {
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
        // users edge (R4): an authority a user holds must be a role the file declares. Needs the roles file
        // (to know what's defined), so it runs only in this single-roles-file branch.
        resolution.users().ifPresent(users -> {
            for (UsersFile.User user : users.users()) {
                for (String authority : user.authorities()) {
                    if (!roles.defines(authority)) {
                        findings.add(RoleFinding.undefinedAuthority(user, authority));
                    }
                }
            }
            // reachability (R4): a model nobody can open (no user holds any of its gating roles) → WARNING;
            // a declared role nothing uses (no user, no model) → INFO (tidiness).
            for (Workspace.Model m : models) {
                if (!m.roles().isEmpty() && m.roles().stream().noneMatch(users::anyoneHolds)) {
                    findings.add(RoleFinding.unreachableModel(m));
                }
            }
            Set<String> gatedRoles = models.stream().flatMap(m -> m.roles().stream()).collect(Collectors.toSet());
            for (String role : roles.names()) {
                if (!users.anyoneHolds(role) && !gatedRoles.contains(role)) {
                    findings.add(RoleFinding.orphanRole(role));
                }
            }
        });
        return List.copyOf(findings);
    }

    private static RolesFile parseRoles(Path file) {
        try {
            return RolesFile.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read roles file " + file, e);
        }
    }

    /** Parse a users target — a single {@code users.yaml} list, or a {@code users/} directory of per-user files. */
    private static UsersFile parseUsers(Path target) {
        try {
            if (!Files.isDirectory(target)) {
                return UsersFile.parse(Files.readString(target));
            }
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target, "*.yaml")) {
                stream.forEach(files::add);
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            List<UsersFile.User> users = new ArrayList<>();
            for (Path f : files) {
                try {
                    users.add(UsersFile.parseUser(Files.readString(f)));
                } catch (IllegalArgumentException notAUser) {
                    // a stray non-user yaml in users/ — skip it, don't abort the scan
                }
            }
            return new UsersFile(users);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read users file " + target, e);
        }
    }
}
