package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ComputationPreconditionFidelityTest {

    private static final List<String> PRECONDITION_AXES = List.of(
            "computations.programs.syntax.ast.variadicArity",
            "computations.programs.syntax.dispatch.catalogOperators",
            "computations.programs.syntax.dispatch.constructCombinations");

    @Test
    void compactProfileDoesNotGainAQuantifierPrecondition() throws IOException {
        ModelProfile target = readProfile("compact.json");
        ModelProfile actual = ModelProfileExtractor.extract(
                ModelSynthesizer.synthesize(ModelProfileJson.write(target), 0L));
        ProfileComparison comparison = ModelProfileComparator.compare(target, actual);

        for (String path : PRECONDITION_AXES) {
            assertEquals(
                    ProfileComparison.Verdict.PASS,
                    comparison.requireAxis(path).outcome().verdict(),
                    path);
        }
    }

    private static ModelProfile readProfile(String fileName) throws IOException {
        String directory = System.getProperty("profile.fixture.dir");
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(
                    "profile.fixture.dir must point at the committed profile fixture directory");
        }
        return ModelProfileJson.read(Files.readString(
                Path.of(directory, fileName),
                StandardCharsets.UTF_8));
    }
}
