package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** End-to-end locks for synthesis from the one current profile format. */
class ModelSynthesizerCurrentProfileTest {

    @Test
    void synthesizesAKernelValidTypedRuleAndComputationModel() {
        String synthesized = ModelSynthesizer.synthesize(profileWithPrograms(), 42L);

        BoundModel.ofJson(synthesized);
        ModelProfile result = ModelProfileExtractor.extract(synthesized);
        assertEquals(1L, result.rules().conversion().converted());
        assertEquals(1L, result.computations().programs().conversion().converted());
        assertEquals(0L, result.rules().conversion().outcomes().counts().getOrDefault("OPAQUE", 0L));
        assertEquals(
                0L,
                result.computations().programs().conversion().outcomes().counts().getOrDefault("OPAQUE", 0L));
    }

    @Test
    void sameCurrentProfileAndSeedAreByteIdenticalInOneJvm() {
        String profile = profileWithPrograms();

        assertEquals(
                ModelSynthesizer.synthesize(profile, 20260720L),
                ModelSynthesizer.synthesize(profile, 20260720L));
    }

    @Test
    void contrastingCurrentProfilesRetainDistinctStructure() {
        String flat = ModelSynthesizer.synthesize(
                ModelProfiles.extract(readModel("person.dm.json")), 7L);
        String nested = ModelSynthesizer.synthesize(
                ModelProfiles.extract(readModel("order.dm.json")), 7L);

        BoundModel.ofJson(flat);
        BoundModel.ofJson(nested);
        ModelProfile flatProfile = ModelProfileExtractor.extract(flat);
        ModelProfile nestedProfile = ModelProfileExtractor.extract(nested);
        assertEquals(1L, flatProfile.structure().elementCount("GROUP"));
        assertEquals(4L, nestedProfile.structure().elementCount("GROUP"));
        assertEquals(0L, flatProfile.structure().repeatability().outcomes().total());
        assertEquals(1L, nestedProfile.structure().repeatability().outcomes().total());
        assertNotEquals(flatProfile.structure(), nestedProfile.structure());
    }

    @Test
    void refusesUnboundedStructureAndRuntimeCapacityInsteadOfSynthesizingACheaperModel() {
        IllegalArgumentException repeatability = assertThrows(
                IllegalArgumentException.class,
                () -> ModelSynthesizer.synthesize(
                        ModelProfileJson.write(ProfileJsonFixture.profile(false)),
                        42L));
        assertTrue(repeatability.getMessage().contains("$.structure.repeatability"));

        IllegalArgumentException runtime = assertThrows(
                IllegalArgumentException.class,
                () -> ModelSynthesizer.synthesize(
                        ModelProfileJson.write(SynthesisTestProfiles.withUnboundedRuleScan(
                                SynthesisTestProfiles.boundedTiedProfile())),
                        42L));
        assertTrue(runtime.getMessage().contains("$.rules.runtime.evaluation.scans.capacity"));
    }

    private static String profileWithPrograms() {
        String structure = DocumentModelDraft.named("SynthesisTarget").locale("en_US")
                .rootGroup("Portfolio", root -> {
                    root.numberField("Gross", field -> field.fractionDigits(2, 2));
                    root.numberField("Net", field -> field.fractionDigits(2, 2));
                    root.field("Base", FieldKind.NUMBER);
                    root.field("Status", FieldKind.STRING);
                    root.field("Code", FieldKind.STRING);
                    root.group("Lines", lines -> {
                        lines.repeatable(10);
                        lines.numberField("Amount", field -> field.fractionDigits(2, 2));
                        lines.field("Quantity", FieldKind.NUMBER);
                        lines.field("Note", FieldKind.STRING);
                    });
                })
                .build()
                .toJson();
        BoundModel model = BoundModel.ofJson(structure);
        var status = model.str("/Portfolio/Status");
        var rule = model.rule(model.group("/Portfolio"))
                .name("StatusRequired")
                .errorField(status)
                .when(status.filled())
                .code("STATUS_REQUIRED")
                .severity(Severity.ERROR)
                .message(Dsl.en("status is required"))
                .build();
        var computation = model.computation(model.num("/Portfolio/Net"))
                .name("NetFromGross")
                .calc(model.num("/Portfolio/Gross").asNumber().expression())
                .message(Dsl.en("net calculation"))
                .build();
        return ModelProfiles.extract(model.addAll(java.util.List.of(rule), java.util.List.of(computation)).toJson());
    }

    private static String readModel(String name) {
        try (var input = ModelSynthesizerCurrentProfileTest.class.getResourceAsStream("/models/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing model resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
