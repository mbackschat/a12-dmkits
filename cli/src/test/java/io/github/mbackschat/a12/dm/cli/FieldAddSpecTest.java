package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rich {@code field add <spec.json>} path (FEATURE-GAPS §3.4/§3.5 on the CLI): a JSON spec carrying the
 * full per-kind config + field metadata is parsed ({@link FieldSpec}), added, and kernel-validated. Each
 * case asserts the verb accepts the spec (exit 0 = kernel-valid) and the configured keys land in the written
 * model — the kernel's own read-back of those keys is locked by {@code modelkit.FieldConfigExtrasTest}.
 */
class FieldAddSpecTest {

    private static Path run(Path dir, String spec) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // en_US-only base
        Path specFile = dir.resolve("field.json");
        Files.writeString(specFile, spec);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add", specFile.toString());
        assertEquals(0, res.code(), res::err);
        return model;
    }

    @Test
    void richNumberSpecWithUnitRangeFlagsAndMetadata(@TempDir Path dir) throws Exception {
        String written = Files.readString(run(dir, """
            {"group":"/Order","name":"Fee","kind":"NUMBER","required":true,"label":{"en_US":"Fee"},
             "number":{"minFractionalDigits":2,"maxFractionalDigits":2,"unit":"AMOUNT","zeroNotAllowed":true,"min":"0"},
             "global":true,"helperText":{"en_US":"the order fee"}}"""));
        assertTrue(written.contains("\"trait\" : \"amount\""), () -> "unit emitted: " + written);
        assertTrue(written.contains("\"minFractionalDigits\" : 2"), "frac-range emitted");
        assertTrue(written.contains("\"zeroNotAllowed\" : true"), "flag emitted");
        assertTrue(written.contains("\"global\" : true"), "metadata emitted");
        assertTrue(written.contains("helperText"), "helper text emitted");
    }

    @Test
    void richStringSpecWithPatternAndSuggestions(@TempDir Path dir) throws Exception {
        String written = Files.readString(run(dir, """
            {"group":"/Order","name":"Code","kind":"STRING",
             "string":{"minLength":3,"maxLength":12,"pattern":"[A-Z]{3,12}","patternMessage":"3-12 uppercase",
                       "suggestions":{"en_US":["ABC","XYZ"]},"alphabeticalSorting":true}}"""));
        assertTrue(written.contains("\"pattern\""), "pattern emitted");
        assertTrue(written.contains("hintList"), "suggestions emitted");
        assertTrue(written.contains("\"alphabeticalSorting\" : true"), "sorting emitted");
    }

    @Test
    void enumSpecWithValuesCategoriesAndInvalidMessage(@TempDir Path dir) throws Exception {
        String written = Files.readString(run(dir, """
            {"group":"/Order","name":"Urgency","kind":"ENUM",
             "enum":{"values":[{"value":"LOW","labels":{"en_US":"Low"}},{"value":"HIGH","labels":{"en_US":"High"}}],
                     "categories":[{"name":"Tier","values":["calm","urgent"]}],
                     "alphabeticalSorting":true,"invalidValueMessage":"pick LOW or HIGH"}}"""));
        assertTrue(written.contains("\"value\" : \"LOW\""), "enum values emitted");
        assertTrue(written.contains("categories"), "categories emitted");
    }

    @Test
    void customSpec(@TempDir Path dir) throws Exception {
        String written = Files.readString(run(dir, """
            {"group":"/Order","name":"Iban","kind":"CUSTOM","custom":{"typeName":"IbanType","minLength":15,"maxLength":34}}"""));
        assertTrue(written.contains("CustomFieldType"), "custom discriminator emitted");
        assertTrue(written.contains("IbanType"), "custom type name emitted");
    }

    @Test
    void rejectsBothSpecAndFlags(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path specFile = dir.resolve("field.json");
        Files.writeString(specFile, "{\"group\":\"/Order\",\"name\":\"X\",\"kind\":\"STRING\"}");
        var res = CliTestSupport.run("-m", model.toString(), "field", "add", specFile.toString(),
                "--group", "/Order", "--name", "Y", "--kind", "NUMBER");
        assertEquals(2, res.code(), "spec + flags together is refused");
    }
}
