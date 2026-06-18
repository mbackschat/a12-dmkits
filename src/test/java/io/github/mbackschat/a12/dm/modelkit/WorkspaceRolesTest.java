package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles.RoleFinding;
import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles.RoleFinding.Code;
import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles.RoleFinding.Severity;

/**
 * The workspace roles lint (FEATURE-GAPS R3) — resolve each model's {@code roles} header annotation against
 * the workspace {@code roles.yaml} and flag the cross-file inconsistencies. The flagship is the
 * <b>undefined-role</b> finding, which even the SME doesn't do (it lets you type any role name); alongside it
 * are the states the SME treats as errors (a roles file present but a model ungated; multiple roles files).
 * <b>Every finding is non-blocking</b> — a {@code WARNING} (or softer {@code INFO}), never an error, never a
 * gate. Fixtures use our own e-commerce domain (the license-safe rule).
 */
class WorkspaceRolesTest {

    private static final String STOREFRONT_ROLES = """
            roles:
              - name: shopper
                accessRights: [DOCUMENT_CREATE, QUERY]
              - name: merchant
                accessRights: [DOCUMENT_CREATE, MODEL_MANAGE]
            """;

    /** A minimal DM-JSON model — enough for the kernel-free scan: a header with id/type and the roles annotation. */
    private static void writeModel(Path dir, String rel, String id, String rolesCsv) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        String ann = rolesCsv == null ? "" : "{\"name\":\"roles\",\"value\":\"" + rolesCsv + "\"}";
        Files.writeString(p, "{\"header\":{\"id\":\"" + id + "\",\"modelType\":\"document\",\"annotations\":["
                + ann + "]},\"content\":{}}");
    }

    private static void writeRoles(Path dir, String rel, String yaml) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, yaml);
    }

    @Test
    void scanReadsTheModelGatingRolesAsACommaSplitList(@TempDir Path dir) throws Exception {
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,merchant");
        Workspace.Model m = Workspace.scan(dir, true).get(0);
        assertEquals(List.of("shopper", "merchant"), m.roles(), "multi-role is the norm — the CSV value is split");
    }

    @Test
    void aCleanWorkspaceHasNoFindings(@TempDir Path dir) throws Exception {
        writeRoles(dir, "auth/roles.yaml", STOREFRONT_ROLES);
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,merchant");
        writeModel(dir, "models/Catalog_DM.json", "Catalog_DM", "merchant");

        WorkspaceRoles.Resolution res = WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty());
        assertTrue(res.rolesFound() && !res.multipleRolesFiles(), "the single auth/roles.yaml is discovered");
        assertEquals(List.of("shopper", "merchant"), List.copyOf(res.roles().orElseThrow().names()));
        assertTrue(WorkspaceRoles.check(Workspace.scan(dir, true), res).isEmpty(), "every gated role is defined");
    }

    @Test
    void anUndefinedRoleIsAWarning(@TempDir Path dir) throws Exception {
        writeRoles(dir, "auth/roles.yaml", STOREFRONT_ROLES);
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,reviewer"); // reviewer undefined

        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true),
                WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty()));
        assertEquals(1, findings.size());
        RoleFinding f = findings.get(0);
        assertEquals(Code.UNDEFINED_ROLE, f.code());
        assertEquals(Severity.WARNING, f.severity());
        assertEquals(Optional.of("reviewer"), f.role());
        assertEquals(Optional.of("Storefront_DM"), f.modelId());
        // the fix turns the diagnosis into an action — it names both remedies (declare the role / drop the gating)
        assertTrue(f.fix().contains("reviewer") && f.fix().contains("Storefront_DM"),
                () -> "the fix names the role and the model: " + f.fix());
    }

    @Test
    void aFilePresentButAnUngatedModelIsAMissingAssignmentWarning(@TempDir Path dir) throws Exception {
        writeRoles(dir, "auth/roles.yaml", STOREFRONT_ROLES);
        writeModel(dir, "models/Ungated_DM.json", "Ungated_DM", null); // no roles annotation

        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true),
                WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty()));
        assertEquals(1, findings.size());
        assertEquals(Code.MISSING_ROLE_ASSIGNMENT, findings.get(0).code());
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertEquals(Optional.of("Ungated_DM"), findings.get(0).modelId());
        assertFalse(findings.get(0).fix().isBlank(), "every finding carries an actionable fix");
    }

    @Test
    void rolesDeclaredButNoFileIsAWarning(@TempDir Path dir) throws Exception {
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper");

        WorkspaceRoles.Resolution res = WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty());
        assertFalse(res.rolesFound(), "no roles file discovered");
        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true), res);
        assertEquals(1, findings.size());
        assertEquals(Code.NO_ROLES_FILE, findings.get(0).code());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void multipleRolesFilesIsAWarning(@TempDir Path dir) throws Exception {
        writeRoles(dir, "auth/roles.yaml", STOREFRONT_ROLES);
        writeRoles(dir, "import/auth/roles.yaml", STOREFRONT_ROLES);
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper");

        WorkspaceRoles.Resolution res = WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty());
        assertTrue(res.multipleRolesFiles(), "both conventional locations are found");
        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true), res);
        assertEquals(Code.MULTIPLE_ROLES_FILES, findings.get(0).code());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void aUserHoldingAnUndefinedAuthorityIsAWarning(@TempDir Path dir) throws Exception {
        writeRoles(dir, "auth/roles.yaml", STOREFRONT_ROLES); // defines shopper, merchant
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,merchant"); // both roles used (no orphan)
        // alice covers both gating roles (model reachable); bob holds 'admin' — undefined (the users-edge twin)
        writeRoles(dir, "auth/users.yaml", """
                users:
                  - username: alice
                    authorities: [shopper, merchant]
                  - username: bob
                    authorities: [admin]
                """);

        WorkspaceRoles.Resolution res = WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty());
        assertTrue(res.usersFound(), "auth/users.yaml is discovered");
        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true), res);
        assertEquals(1, findings.size(), () -> "only bob's undefined authority: " + findings);
        RoleFinding f = findings.get(0);
        assertEquals(Code.UNDEFINED_AUTHORITY, f.code());
        assertEquals(Severity.WARNING, f.severity(), "advisory only — never an error");
        assertEquals(Optional.of("admin"), f.role());
        assertEquals(Optional.of("bob"), f.username());
        assertTrue(f.fix().contains("admin") && f.fix().contains("bob"), () -> "fix names the authority + user: " + f.fix());
    }

    @Test
    void reachabilityFlagsUnreachableModelsAndOrphanRoles(@TempDir Path dir) throws Exception {
        // shopper is held + gating (clean); merchant gates Catalog but no user holds it (unreachable);
        // auditor is declared but neither held nor gating (orphan).
        writeRoles(dir, "auth/roles.yaml", """
                roles:
                  - name: shopper
                  - name: merchant
                  - name: auditor
                """);
        writeRoles(dir, "auth/users.yaml", "users:\n  - username: alice\n    authorities: [shopper]\n");
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper"); // reachable (alice)
        writeModel(dir, "models/Catalog_DM.json", "Catalog_DM", "merchant");      // unreachable (no holder)

        List<RoleFinding> findings = WorkspaceRoles.check(Workspace.scan(dir, true),
                WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty()));

        RoleFinding unreachable = findings.stream().filter(f -> f.code() == Code.UNREACHABLE_MODEL).findFirst().orElseThrow();
        assertEquals(Severity.WARNING, unreachable.severity());
        assertEquals(Optional.of("Catalog_DM"), unreachable.modelId());

        RoleFinding orphan = findings.stream().filter(f -> f.code() == Code.ORPHAN_ROLE).findFirst().orElseThrow();
        assertEquals(Severity.INFO, orphan.severity(), "orphan role is INFO — softest, still non-blocking");
        assertEquals(Optional.of("auditor"), orphan.role());

        assertTrue(findings.stream().noneMatch(f -> f.code() == Code.UNDEFINED_AUTHORITY), "alice is clean");
    }

    @Test
    void discoveryToleratesThePreviewAppConventionAndAnExplicitOverride(@TempDir Path dir) throws Exception {
        writeRoles(dir, "user/access-rights.yaml", STOREFRONT_ROLES); // the Preview-App workspace convention
        writeModel(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper");
        assertTrue(WorkspaceRoles.resolve(dir, Optional.empty(), Optional.empty()).rolesFound(), "user/access-rights.yaml is discovered");

        // an explicit override bypasses discovery and is used verbatim
        Path override = dir.resolve("custom/my-roles.yaml");
        writeRoles(dir, "custom/my-roles.yaml", STOREFRONT_ROLES);
        WorkspaceRoles.Resolution res = WorkspaceRoles.resolve(dir, Optional.of(override), Optional.empty());
        assertEquals(List.of(override), res.rolesFiles());
    }
}
