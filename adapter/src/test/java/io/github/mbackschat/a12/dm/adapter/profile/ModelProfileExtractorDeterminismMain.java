package io.github.mbackschat.a12.dm.adapter.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Fresh-process extraction entry point for {@link ModelProfileExtractorCrossJvmDeterminismTest}. */
final class ModelProfileExtractorDeterminismMain {

    private ModelProfileExtractorDeterminismMain() {}

    public static void main(String[] args) {
        try (var input = ModelProfileExtractorDeterminismMain.class.getResourceAsStream("/models/order.dm.json")) {
            if (input == null) {
                throw new IllegalStateException("missing /models/order.dm.json");
            }
            String model = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(ModelProfiles.extract(model));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
