package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The YAML/OpenAPI sibling of {@link NumberSpecScaleFidelityTest}: a numeric schema bound is transported into
 * A12 DM-JSON, so the YAML fallback parser must retain the authored decimal scale just like the JSON parser.
 */
class YamlNumberSchemaScaleFidelityTest {

    @TempDir
    Path dir;

    @Test
    void anImportedYamlSchemaBoundKeepsItsAuthoredScale() throws Exception {
        Path schema = dir.resolve("schema.yaml");
        Files.writeString(schema, """
                openapi: 3.0.3
                info:
                  title: Order
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        amount:
                          type: number
                          minimum: 0.50
                          maximum: 10.00
                """);

        CliTestSupport.Result out = CliTestSupport.run(
                "model", "import-jsonschema", "--schema", schema.toString(), "--component", "Order");

        assertEquals(0, out.code(), () -> "YAML import succeeds: " + out.err());
        assertTrue(out.out().contains("0.50"),
                () -> "an authored YAML 0.50 bound must not degrade to 0.5: " + out.out());
        assertTrue(out.out().contains("10.00"),
                () -> "an authored YAML 10.00 bound must not degrade to 10.0: " + out.out());
    }
}
