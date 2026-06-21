package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

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
    void enumSpecWithPerLocaleErrorMessages(@TempDir Path dir) throws Exception {
        // G23: per-locale invalid-value text (not the single-string shorthand). Build a clean bilingual
        // model (en_US + de_DE) — both the value labels and the error messages must cover both locales.
        Path model = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", model.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "de_DE").code());
        Path specFile = dir.resolve("tier.json");
        Files.writeString(specFile, """
            {"group":"/Doc","name":"Tier","kind":"ENUM",
             "enum":{"values":[{"value":"GOLD","labels":{"en_US":"Gold","de_DE":"Gold"}},
                               {"value":"SILVER","labels":{"en_US":"Silver","de_DE":"Silber"}}],
                     "errorMessages":{"en_US":"pick a valid tier","de_DE":"bitte eine gueltige Stufe waehlen"}}}""");
        var res = CliTestSupport.run("-m", model.toString(), "field", "add", specFile.toString());
        assertEquals(0, res.code(), res::err);
        String written = Files.readString(model);
        assertTrue(written.contains("pick a valid tier"), "en_US message emitted");
        assertTrue(written.contains("bitte eine gueltige Stufe waehlen"), "de_DE message emitted (per-locale, not duplicated)");
    }

    @Test
    void requiredFieldWithACustomRequiredMessage(@TempDir Path dir) throws Exception {
        // G23: per-locale custom error text for a REQUIRED field (the "Use Default Error Messages" deselected
        // case) — attaches to requirednessConfig.errorMessage.
        Path model = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", model.toString()).code());
        Path specFile = dir.resolve("email.json");
        Files.writeString(specFile, """
            {"group":"/Doc","name":"Email","kind":"STRING","required":true,
             "requiredMessages":{"en_US":"An email address is required"}}""");
        var res = CliTestSupport.run("-m", model.toString(), "field", "add", specFile.toString());
        assertEquals(0, res.code(), res::err);
        String written = Files.readString(model);
        assertTrue(written.contains("An email address is required"), "the custom required message persists");
        assertTrue(written.contains("requirednessConfig"), "it lives on the requiredness config");
    }

    @Test
    void requiredMessageOnAnOptionalFieldIsRejected(@TempDir Path dir) throws Exception {
        // a custom required message needs the field to be required (have a requirednessConfig) — fail-fast
        Path model = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", model.toString()).code());
        Path specFile = dir.resolve("x.json");
        Files.writeString(specFile, """
            {"group":"/Doc","name":"X","kind":"STRING","requiredMessages":{"en_US":"oops"}}""");
        var res = CliTestSupport.run("-m", model.toString(), "field", "add", specFile.toString());
        assertEquals(2, res.code(), () -> "a required message without requiredness is a spec error: " + res.out() + res.err());
        assertTrue(res.err().toLowerCase().contains("required"),
                () -> "names the requiredness precondition: " + res.err());
    }

    @Test
    void enumCategoryCarriesAnOptionalDescription(@TempDir Path dir) throws Exception {
        // SME 1133: a category may carry an optional description. It's an editor-level note (part of the
        // model, not workspace settings) the kernel core tolerates + preserves (KERNEL-FINDINGS §8).
        String written = Files.readString(run(dir, """
            {"group":"/Order","name":"Urgency","kind":"ENUM",
             "enum":{"values":[{"value":"LOW"},{"value":"HIGH"}],
                     "categories":[{"name":"Tier","description":"escalation grouping","values":["calm","urgent"]}]}}"""));
        assertTrue(written.contains("escalation grouping"), "the category description persists in the model");
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
