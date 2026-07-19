package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * IG91 — {@code model seed} must prepare its input through the SAME kernel-free expander every other runtime
 * verb uses ({@code InterpreterEngine.expandForEval}), not read raw DM-JSON and load it directly. Before the
 * fix, an includes-bearing model reached {@code InterpreterModelLoader.load} unexpanded: the mounted sub-model's
 * fields were absent from the model the seeder walked, so the seeded instance silently omitted the entire
 * mounted subtree — a structurally plausible but wrong seed. Own domain (an insurance policy mounting a benefits
 * sub-model). Companion of {@code IncludeEvalDefaultPathTest} (the same seam on the eval path).
 */
class ModelSeedIncludeTest {

    private static final ObjectMapper M = new ObjectMapper();

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

    /** Write the base model + its included model to {@code dir}; return the base model path. */
    private static Path workspace(Path dir, String baseJson, String includedJson) throws IOException {
        Files.writeString(dir.resolve("benefits.dm.json"), includedJson);
        Path base = dir.resolve("policy.dm.json");
        Files.writeString(base, baseJson);
        return base;
    }

    @Test
    void seedInstantiatesTheMountedSubmodelsFields(@TempDir Path dir) throws Exception {
        Path model = workspace(dir, policyMountingBenefits(), benefits());

        CliTestSupport.Result seed = CliTestSupport.run("-m", model.toString(), "model", "seed");

        assertEquals(0, seed.code(), () -> "seed succeeds: " + seed.out() + seed.err());
        JsonNode fields = M.readTree(seed.out()).path("fields");
        assertTrue(fields.path("Policy").hasNonNull("PolicyNo"),
                () -> "seed emits the base field: " + seed.out());
        // Before IG91's fix the raw (unexpanded) model reached the loader, so the mounted Cover subtree was
        // absent from the walked model and never seeded — this is the reproducer.
        assertTrue(fields.path("Policy").path("Cover").hasNonNull("Coverage"),
                () -> "seed instantiates the MOUNTED sub-model's field (was silently dropped): " + seed.out());
    }
}
