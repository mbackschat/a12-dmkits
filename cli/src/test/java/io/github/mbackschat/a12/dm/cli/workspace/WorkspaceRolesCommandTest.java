package io.github.mbackschat.a12.dm.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code dmtool workspace roles <dir>} (FEATURE-GAPS R3) — the workspace roles lint: resolve every model's
 * {@code roles} header annotation against the workspace {@code roles.yaml} and report the cross-file
 * inconsistencies, exiting 1 on any ERROR so CI can gate on it. The flagship finding is an <b>undefined
 * role</b> (a gate even the SME doesn't apply). Fixtures use our own e-commerce domain.
 */
class WorkspaceRolesCommandTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String ROLES = """
            roles:
              - name: shopper
                accessRights: [DOCUMENT_CREATE, QUERY]
              - name: merchant
                accessRights: [MODEL_MANAGE]
            """;

    private static void model(Path dir, String rel, String id, String rolesCsv) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        String ann = rolesCsv == null ? "" : "{\"name\":\"roles\",\"value\":\"" + rolesCsv + "\"}";
        Files.writeString(p, "{\"header\":{\"id\":\"" + id + "\",\"modelType\":\"document\",\"annotations\":["
                + ann + "]},\"content\":{}}");
    }

    private static void roles(Path dir, String rel) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, ROLES);
    }

    @Test
    void aCleanWorkspaceReadsOkAndSurfacesDefinedRolesAndPerModelGating(@TempDir Path dir) throws Exception {
        roles(dir, "auth/roles.yaml");
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,merchant");

        var res = CliTestSupport.run("workspace", "roles", dir.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.path("outcome").asText());
        JsonNode data = j.path("data");
        assertTrue(data.path("rolesFile").asText().endsWith("roles.yaml"), () -> "names the resolved file: " + res.out());
        // surface (c): the defined roles (+ their access rights) and which roles gate each model
        assertEquals("shopper", data.path("definedRoles").get(0).path("name").asText());
        assertEquals("Storefront_DM", data.path("models").get(0).path("id").asText());
        assertEquals("merchant", data.path("models").get(0).path("roles").get(1).asText());
        assertTrue(data.path("findings").isEmpty(), () -> "a clean workspace has no findings: " + res.out());
    }

    @Test
    void anUndefinedRoleFailsTheGate(@TempDir Path dir) throws Exception {
        roles(dir, "auth/roles.yaml");
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,reviewer"); // reviewer undefined

        var res = CliTestSupport.run("workspace", "roles", dir.toString());
        assertNotEquals(0, res.code(), "an undefined role is an ERROR — the gate fails (exit 1)");
        JsonNode finding = M.readTree(res.out()).path("data").path("findings").get(0);
        assertEquals("UNDEFINED_ROLE", finding.path("code").asText());
        assertEquals("reviewer", finding.path("role").asText());
        assertEquals("Storefront_DM", finding.path("modelId").asText());
        // the finding is actionable: it carries a `fix` naming the remedy (parity with RK_* diagnostics)
        assertTrue(finding.path("fix").asText().contains("reviewer"), () -> "fix names the remedy: " + res.out());
    }

    @Test
    void aModelDeclaringRolesWithNoRolesFileWarnsButPasses(@TempDir Path dir) throws Exception {
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper");

        var res = CliTestSupport.run("workspace", "roles", dir.toString());
        assertEquals(0, res.code(), () -> "a warning does not fail the gate: " + res.err());
        assertEquals("NO_ROLES_FILE", M.readTree(res.out()).path("data").path("findings").get(0).path("code").asText());
    }

    @Test
    void theRolesFileOverrideBypassesDiscovery(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my-roles.yaml"), ROLES);
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper");

        var res = CliTestSupport.run("workspace", "roles", dir.toString(),
                "--roles", dir.resolve("my-roles.yaml").toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(M.readTree(res.out()).path("data").path("findings").isEmpty(), res::out);
    }

    @Test
    void aMissingDirectoryIsACorrectiveError(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("workspace", "roles", dir.resolve("nope").toString());
        assertNotEquals(0, res.code(), "a non-directory must fail");
        assertTrue(res.err().toLowerCase().contains("directory"), () -> "names the cause: " + res.err());
    }
}
