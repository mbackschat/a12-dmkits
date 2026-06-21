package io.github.mbackschat.a12.dm.cli.include;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * CLI over a <b>multi-file</b> model (F-RES): a model that {@code include}s another document model and
 * uses a typedef must load through the CLI verbs, resolving the referenced model by id. The CLI defaults
 * to resolving against the directory of {@code <model.json>} (siblings resolve with no flag); an explicit
 * {@code -w/--workspace} points at the workspace tree when the included models live elsewhere. Before this,
 * every verb loaded self-contained and a multi-file app failed with "model references a separate model …".
 */
class IncludeResolverVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A reusable price list (included), with a `ListPrice` number field. */
    private static String pricelistJson() {
        return DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p
                        .numberField("ListPrice", n -> n.fractionDigits(2))
                        .field("Currency", FieldKind.STRING))
                .build().toJson();
    }

    /** A storefront that includes `pricelist` (mounted at /Storefront/Prices) and uses a Sku typedef. */
    private static String storefrontJson() {
        return DocumentModelDraft.named("storefront").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .include("pricelist", "pricelist")
                .rootGroup("Storefront", s -> s
                        .field("StoreName", FieldKind.STRING)
                        .typedefField("ProductSku", "Sku")
                        .mount("Prices", "pricelist"))
                .build().toJson();
    }

    /** Write both models as siblings; return the including model's path. */
    private static Path workspace(Path dir) throws Exception {
        Files.writeString(dir.resolve("pricelist.dm.json"), pricelistJson());
        Path storefront = dir.resolve("storefront.dm.json");
        Files.writeString(storefront, storefrontJson());
        return storefront;
    }

    @Test
    void validateResolvesASiblingIncludeByDefault(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", workspace(dir).toString(), "model", "validate");
        assertEquals(0, res.code(), () -> "the multi-file model should validate: " + res.err() + res.out());
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), () -> "valid: " + res.out());
    }

    @Test
    void describeSeesIncludedAndTypedefFields(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", workspace(dir).toString(), "model", "describe");
        assertEquals(0, res.code(), res::err);
        // the mounted include's field and the typedef field both surface in the model card
        assertTrue(res.out().contains("/Storefront/Prices/ListPrice"),
                () -> "the included field should appear: " + res.out());
        assertTrue(res.out().contains("/Storefront/ProductSku"),
                () -> "the typedef field should appear: " + res.out());
    }

    @Test
    void checkValidatesACandidateAgainstAnIncludingModel(@TempDir Path dir) throws Exception {
        // exercises the RuleValidator resolver path: the candidate lives in the owned /Storefront group, but
        // the model includes pricelist, so the injected-rule model can only be expanded + checked with the
        // resolver — without it the kernel's expand step fails on the unresolved include
        var res = CliTestSupport.run("-m", workspace(dir).toString(), "rule", "check",
                "--field", "/Storefront/StoreName",
                "--condition", "FieldNotFilled(StoreName)", "--code", "STORE_NAME_REQ");
        assertEquals(0, res.code(), () -> "the candidate should validate over the multi-file model: " + res.err() + res.out());
        assertTrue(MAPPER.readTree(res.out()).get("valid").asBoolean(), () -> "valid: " + res.out());
    }

    @Test
    void resolverSkipsAnUnreadableSubdirectory(@TempDir Path dir) throws Exception {
        // Reproduces the CI failure: the default -w/--workspace is the model's parent, which on a CI runner
        // can be a shared /tmp containing root-only dirs (/tmp/systemd-private-*). The directory resolver
        // must SKIP a subdir it can't enter, not abort the whole walk with AccessDeniedException.
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions needed to make a dir unreadable");
        Path model = workspace(dir);                                  // pricelist + storefront siblings
        Path locked = Files.createDirectory(dir.resolve("locked"));   // a sibling dir the walk will hit
        Files.setPosixFilePermissions(locked, Set.of());              // chmod 000 → can't be entered
        try {
            var res = CliTestSupport.run("-m", model.toString(), "model", "validate");   // default include-dir = dir (holds `locked`)
            assertEquals(0, res.code(), () -> "the unreadable sibling dir must be skipped, not fatal: " + res.err() + res.out());
            assertTrue(MAPPER.readTree(res.out()).get("valid").asBoolean(), () -> "valid: " + res.out());
        } finally {
            Files.setPosixFilePermissions(locked, Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));   // restore so @TempDir can clean up
        }
    }

    @Test
    void includeDirResolvesWhenTheIncludedModelLivesElsewhere(@TempDir Path dir) throws Exception {
        // included model in a separate `lib/` tree; the including model in the working dir alongside nothing
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Files.writeString(lib.resolve("pricelist.dm.json"), pricelistJson());
        Path apps = Files.createDirectories(dir.resolve("apps"));
        Path storefront = apps.resolve("storefront.dm.json");
        Files.writeString(storefront, storefrontJson());

        // no sibling pricelist → the default (apps/) can't resolve it; -w/--workspace lib does
        var noHint = CliTestSupport.run("-m", storefront.toString(), "model", "validate");
        assertEquals(1, noHint.code(), () -> "without -w/--workspace the sibling default can't resolve it: " + noHint.out());
        // an unresolvable reference is a STRUCTURED rejection (corrective), not a raw stack trace / internal error
        assertEquals("rejected", MAPPER.readTree(noHint.out()).get("outcome").asText(),
                () -> "an unresolvable include must be a structured rejection: " + noHint.out());
        assertEquals("RK_UNRESOLVED_REFERENCE",
                MAPPER.readTree(noHint.out()).get("diagnostics").get(0).get("code").asText());

        var withDir = CliTestSupport.run("-m", storefront.toString(), "model", "validate", "-w", lib.toString());
        assertEquals(0, withDir.code(), () -> "-w/--workspace resolves the include: " + withDir.err() + withDir.out());
        assertTrue(MAPPER.readTree(withDir.out()).get("valid").asBoolean());
    }
}
