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

    private static void users(Path dir, String rel, String yaml) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, yaml);
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
    void anUndefinedRoleIsANonBlockingWarning(@TempDir Path dir) throws Exception {
        roles(dir, "auth/roles.yaml");
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper,reviewer"); // reviewer undefined

        var res = CliTestSupport.run("workspace", "roles", dir.toString());
        assertEquals(0, res.code(), () -> "roles findings never block — the verb always exits 0: " + res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.path("outcome").asText());
        JsonNode finding = j.path("data").path("findings").get(0);
        assertEquals("UNDEFINED_ROLE", finding.path("code").asText());
        assertEquals("WARNING", finding.path("severity").asText(), "advisory only — never an error");
        assertEquals("reviewer", finding.path("role").asText());
        assertEquals("Storefront_DM", finding.path("modelId").asText());
        // the finding is actionable: it carries a `fix` naming the remedy (parity with RK_* diagnostics)
        assertTrue(finding.path("fix").asText().contains("reviewer"), () -> "fix names the remedy: " + res.out());
    }

    @Test
    void aUserWithAnUndefinedAuthorityIsANonBlockingWarning(@TempDir Path dir) throws Exception {
        roles(dir, "auth/roles.yaml");                                       // defines shopper, merchant
        model(dir, "models/Storefront_DM.json", "Storefront_DM", "shopper"); // clean model
        users(dir, "auth/users.yaml", """
                users:
                  - username: alice
                    authorities: [shopper]
                  - username: bob
                    authorities: [admin]
                """);                                                        // bob's 'admin' is undefined

        var res = CliTestSupport.run("workspace", "roles", dir.toString());
        assertEquals(0, res.code(), () -> "users findings never block either — exit 0: " + res.err());
        JsonNode data = M.readTree(res.out()).path("data");
        assertTrue(data.path("usersFile").asText().endsWith("users.yaml"), () -> "names the users file: " + res.out());
        assertEquals("bob", data.path("users").get(1).path("username").asText());
        JsonNode finding = data.path("findings").get(0);
        assertEquals("UNDEFINED_AUTHORITY", finding.path("code").asText());
        assertEquals("WARNING", finding.path("severity").asText());
        assertEquals("bob", finding.path("username").asText());
        assertEquals("admin", finding.path("role").asText());
        assertTrue(finding.path("fix").asText().contains("admin"), () -> "fix names the remedy: " + res.out());
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

    /**
     * <b>An UNREADABLE roles file and an UNPARSEABLE one are different answers.</b>
     *
     * <p>{@code WorkspaceRoles.resolve} documents two throws — {@code UncheckedIOException} when a resolved
     * file cannot be read, {@code IllegalArgumentException} when it cannot be parsed — and one
     * {@code catch (RuntimeException)} reported both as "cannot read access-control config". The unreadable half
     * is the same class as every other caller-named input that will not open (G61), so it carries
     * {@code RK_INPUT_UNREADABLE}; the parse failure stays on the local-text channel a reader's own
     * parse/render failure deliberately uses. This test exists because the exclusion was originally justified by
     * READING the exception type and assuming it meant "invalid content" — the assumption was half wrong, and
     * only the two cases side by side show it.
     *
     * <p>A DIRECTORY named as the roles file is the portable trigger for the unreadable half: the resolver
     * accepts a directory as a candidate, then reads it, which fails on every platform without needing
     * permission games.
     */
    @Test
    void anUnreadableRolesFileIsCodedWhileAnUnparseableOneStaysLocalText(@TempDir Path dir) throws Exception {
        model(dir, "shop/order.dm.json", "Order_DM", "shopper");
        Path asDirectory = dir.resolve("roles-as-dir.yaml");
        Files.createDirectories(asDirectory);

        var unreadable = CliTestSupport.run("workspace", "roles", dir.toString(),
                "--roles", asDirectory.toString());
        assertEquals(2, unreadable.code(), () -> unreadable.out() + unreadable.err());
        assertTrue(unreadable.out().contains("RK_INPUT_UNREADABLE"),
                () -> "a roles file that will not open is the unreadable-input class: "
                        + unreadable.out() + unreadable.err());
        assertTrue(unreadable.out().contains("--roles <file>"),
                () -> "and it names the argument the caller gave: " + unreadable.out());

        Path unparseableFile = dir.resolve("roles-bad.yaml");
        Files.writeString(unparseableFile, "roles: [ this is not: valid: yaml\n");
        var unparseable = CliTestSupport.run("workspace", "roles", dir.toString(),
                "--roles", unparseableFile.toString());
        assertEquals(2, unparseable.code(), () -> unparseable.out() + unparseable.err());
        assertTrue(unparseable.err().contains("cannot read access-control config"),
                () -> "a file that opened and would not parse keeps the reader's own text: "
                        + unparseable.err() + unparseable.out());
        assertTrue(!unparseable.out().contains("RK_INPUT_UNREADABLE"),
                () -> "and must not claim it could not be read: " + unparseable.out());
    }
}
