package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * IF145 — {@code model seed} must prepare its input through the SAME kernel-free expander every other runtime
 * verb uses ({@code InterpreterEngine.prepareForEval}), not read raw DM-JSON and load it directly. Two
 * consequences of that boundary are locked here over own-domain (insurance / e-commerce) fixtures: an included
 * sub-model's fields are instantiated (the mount is not silently dropped), and an imported typedef field is
 * seeded from its RESOLVED kind (not flattened to STRING), and opaque element ids are normalized without losing
 * structure or constraints. The loader-side refusal of any residue that escapes preparation is the sibling
 * {@code ModelLoaderPreparedGuardTest}; the eval-path twin is
 * {@code IncludeEvalDefaultPathTest}.
 */
class ModelSeedPreparationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final ObjectMapper DM_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    // ── include: a policy mounting a benefits sub-model ─────────────────────────────────────────────

    /** The included benefits sub-model: a Coverage amount + a Kind, no rules. */
    private static String benefits() {
        return DocumentModelDraft.named("benefits").locale("en_US")
                .rootGroup("Benefits", b -> b
                        .numberField("Coverage", n -> n.fractionDigits(2))
                        .field("Kind", FieldKind.STRING))
                .build().toJson();
    }

    /** A policy mounting benefits at /Policy/Cover. */
    private static String policyMountingBenefits() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .include("benefits", "benefits")
                .rootGroup("Policy", p -> p
                        .field("PolicyNo", FieldKind.STRING)
                        .mount("Cover", "benefits"))
                .build().toJson();
    }

    // ── typedef import: a storefront importing a Money number type ───────────────────────────────────

    /** A type-def-only provider carrying a Money number type (2 fraction digits). */
    private static String catalogTypes() {
        return DocumentModelDraft.typeDefModel("catalogtypes").locale("en_US")
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2)))
                .build().toJson();
    }

    /** A storefront whose Price field is typed by the imported Money definition. */
    private static String storefrontImportingMoney() {
        return DocumentModelDraft.named("storefront").locale("en_US")
                .importTypeDefs("catalogtypes")
                .rootGroup("Storefront", s -> s
                        .field("Sku", FieldKind.STRING)
                        .typedefField("Price", "Money"))
                .build().toJson();
    }

    /** Write the base model + its referenced model to {@code dir}; return the base model path. */
    private static Path workspace(Path dir, String baseName, String baseJson, String refName, String refJson)
            throws IOException {
        Files.writeString(dir.resolve(refName), refJson);
        Path base = dir.resolve(baseName);
        Files.writeString(base, baseJson);
        return base;
    }

    @Test
    void seedInstantiatesTheMountedSubmodelsFields(@TempDir Path dir) throws Exception {
        Path model = workspace(dir, "policy.dm.json", policyMountingBenefits(), "benefits.dm.json", benefits());

        CliTestSupport.Result seed = CliTestSupport.run("-m", model.toString(), "model", "seed");

        assertEquals(0, seed.code(), () -> "seed succeeds: " + seed.out() + seed.err());
        JsonNode fields = M.readTree(seed.out());
        assertTrue(fields.path("Policy").hasNonNull("PolicyNo"),
                () -> "seed emits the base field: " + seed.out());
        // Without preparation the raw (unexpanded) model reaches the loader, so the mounted Cover subtree is
        // absent from the walked model and never seeded — this is the reproducer.
        assertTrue(fields.path("Policy").path("Cover").hasNonNull("Coverage"),
                () -> "seed instantiates the MOUNTED sub-model's field (was silently dropped): " + seed.out());
    }

    @Test
    void seedResolvesAnImportedTypedefFieldToItsDeclaredKind(@TempDir Path dir) throws Exception {
        Path model = workspace(dir, "storefront.dm.json", storefrontImportingMoney(),
                "catalogtypes.dm.json", catalogTypes());

        CliTestSupport.Result seed = CliTestSupport.run("-m", model.toString(), "model", "seed");

        assertEquals(0, seed.code(), () -> "seed succeeds: " + seed.out() + seed.err());
        JsonNode fields = M.readTree(seed.out());
        String price = fields.path("Storefront").path("Price").asText();
        // Preparation inlines the imported Money type, so Price is seeded as a NUMBER (a decimal literal).
        // Without it the TypeDefType flattens to STRING and Price would be a "s..." string token instead.
        assertTrue(price.matches("-?\\d+(\\.\\d+)?"),
                () -> "the imported typedef field is seeded from its resolved NUMBER kind, not STRING: " + seed.out());
    }

    @Test
    void seedNormalizesOpaqueElementIdsWithoutLosingStructureOrConstraints(@TempDir Path dir) throws Exception {
        String prepared = DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Claim", claim -> claim
                        .stringField("Reference", field -> field.minLength(4).maxLength(4))
                        .numberField("Amount", field -> field.fractionDigits(2).min("10.00").max("20.00")))
                .build().toJson();
        Path namePathModel = dir.resolve("claims-name-path.dm.json");
        Path opaqueModel = dir.resolve("claims-opaque.dm.json");
        Files.writeString(namePathModel, prepared);
        Files.writeString(opaqueModel, withOpaqueElementIds(prepared));

        CliTestSupport.Result namePathSeed = CliTestSupport.run(
                "-m", namePathModel.toString(), "model", "seed", "--seed", "19");
        CliTestSupport.Result opaqueSeed = CliTestSupport.run(
                "-m", opaqueModel.toString(), "model", "seed", "--seed", "19");

        assertEquals(0, namePathSeed.code(), () -> "name-path seed succeeds: " + namePathSeed.out() + namePathSeed.err());
        assertEquals(0, opaqueSeed.code(), () -> "opaque-id seed succeeds: " + opaqueSeed.out() + opaqueSeed.err());
        assertEquals(M.readTree(namePathSeed.out()), M.readTree(opaqueSeed.out()),
                "opaque ids cross portable workspace preparation and preserve the model's structure and constraints");
    }

    private static String withOpaqueElementIds(String json) throws IOException {
        JsonNode root = DM_JSON.readTree(json);
        replaceElementIds(root);
        return DM_JSON.writeValueAsString(root);
    }

    private static void replaceElementIds(JsonNode node) {
        if (node instanceof ObjectNode object && object.hasNonNull("type") && object.hasNonNull("id")) {
            object.put("id", "opaque-" + object.path("name").asText());
        }
        node.forEach(ModelSeedPreparationTest::replaceElementIds);
    }
}
