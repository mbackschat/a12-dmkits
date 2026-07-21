package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ComputationPreconditionFidelityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aPlainPreconditionDoesNotInventAQuantifier() throws Exception {
        Path fixture = Path.of(System.getProperty("profile.fixture.dir"), "compact.json");
        ObjectNode target = (ObjectNode) JSON.readTree(Files.readString(fixture)).deepCopy();
        ObjectNode computations = (ObjectNode) target.path("computations");
        computations.put("preconditionedCount", 1);
        computations.set("constructs", JSON.createObjectNode());
        ObjectNode operators = JSON.createObjectNode();
        operators.put("FieldFilled", 1);
        computations.set("operators", operators);

        String model = ModelSynthesizer.synthesize(target.toString(), 0L);
        var actual = ModelProfileJson.read(ModelProfiles.extract(model));

        assertTrue(actual.computations().preconditionedCount() >= 1);
        assertEquals(0, actual.computations().constructs().getOrDefault("QUANTIFIER", 0));
    }
}
