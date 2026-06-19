package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The "applied ⇒ effected" round-trip guard for field metadata — the deterministic complement to the probe,
 * for the silent-drop / false-{@code applied} class. Two invariants, both for every writable metadata aspect:
 * <ul>
 *   <li><b>effected</b> — a schema-correct value reported {@code applied} must actually land in the model
 *       (write → read back; the original {@code field modify} metadata bug + the requiredness/annotations
 *       drop the probe's round-trip work surfaced);</li>
 *   <li><b>rejected</b> — a recognized key carrying a <em>wrong-shaped</em> value must be refused, NOT silently
 *       coerced to a default/empty and falsely reported {@code applied}. {@code SpecKeys} validates key
 *       <em>names</em>; this guards the value <em>shapes</em> the {@code FieldSpec} parsers used to swallow
 *       ({@code required:"MANDATORY"} → silently OPTIONAL; {@code annotations:[…]} / {@code label:"…"} →
 *       silently empty).</li>
 * </ul>
 */
class FieldMetadataRoundTripTest {

    private static Path modelWithField(Path dir) throws Exception {
        Path m = dir.resolve("m.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "rt", "--locale", "en_US",
                "--root", "R", "-o", m.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add",
                "--group", "/R", "--name", "F", "--kind", "STRING").code());
        return m;
    }

    @Test
    void everyMetadataAspectAppliedActuallyLandsInTheModel(@TempDir Path dir) throws Exception {
        Path m = modelWithField(dir);
        Path spec = dir.resolve("mod.json");
        Files.writeString(spec, """
                { "group":"/R", "name":"F", "required":true,
                  "label":{"en_US":"MARK_LABEL"}, "helperText":{"en_US":"MARK_HELPER"},
                  "internalDescription":{"en_US":"MARK_INT"}, "externalDescription":{"en_US":"MARK_EXT"},
                  "annotations":{"mark_anno":"MARK_ANNO"}, "global":true, "transient":true }""");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());
        assertEquals(0, res.code(), res::err);

        // every aspect the verb reported applied must be visible in the written model — no false `applied`
        String written = Files.readString(m);
        for (String marker : List.of("MARK_LABEL", "MARK_HELPER", "MARK_INT", "MARK_EXT", "MARK_ANNO",
                "requirednessConfig", "\"global\"", "\"transient\"")) {
            assertTrue(written.contains(marker), () -> "aspect not effected (silent drop): " + marker + "\n" + written);
        }
    }

    @Test
    void wrongShapedRequiredIsRejectedNotSilentlyApplied(@TempDir Path dir) throws Exception {
        Path m = modelWithField(dir);
        Path bad = dir.resolve("bad.json");
        // "MANDATORY" is the kernel's own term — exactly what an agent would reach for — but the parser only
        // understands true/false/"ifParentPresent". It must be refused, not silently coerced to OPTIONAL.
        Files.writeString(bad, "{ \"group\":\"/R\", \"name\":\"F\", \"required\":\"MANDATORY\" }");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", bad.toString());
        assertNotEquals(0, res.code(), () -> "required:\"MANDATORY\" must be rejected, not silently applied: " + res.out());
    }

    @Test
    void wrongShapedAnnotationsIsRejectedNotSilentlyApplied(@TempDir Path dir) throws Exception {
        Path m = modelWithField(dir);
        Path bad = dir.resolve("bad.json");
        // the array shape (vs the schema's object {name:value}) used to parse to an empty list → silent drop
        Files.writeString(bad, "{ \"group\":\"/R\", \"name\":\"F\", \"annotations\":[{\"name\":\"a\",\"value\":\"v\"}] }");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", bad.toString());
        assertNotEquals(0, res.code(), () -> "annotations as an array must be rejected, not silently dropped: " + res.out());
    }

    @Test
    void wrongShapedLocalizedTextIsRejectedNotSilentlyApplied(@TempDir Path dir) throws Exception {
        Path m = modelWithField(dir);
        Path bad = dir.resolve("bad.json");
        // helperText as a bare string (vs the {locale:text} object) parsed to an empty map → silent drop (unlike
        // label, helperText has no locale-coverage check downstream to catch it, so it slips through silently).
        Files.writeString(bad, "{ \"group\":\"/R\", \"name\":\"F\", \"helperText\":\"just a string\" }");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", bad.toString());
        assertNotEquals(0, res.code(), () -> "helperText as a bare string must be rejected, not silently dropped: " + res.out());
    }
}
