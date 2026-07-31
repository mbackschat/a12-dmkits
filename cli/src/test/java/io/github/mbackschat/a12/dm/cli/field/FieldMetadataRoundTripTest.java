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
 * The "applied ⇒ effected" round-trip guard for field properties and metadata — the deterministic complement to the probe,
 * for the silent-drop / false-{@code applied} class. Two invariants, both for every writable metadata aspect:
 * <ul>
 *   <li><b>effected</b> — a schema-correct value reported {@code applied} must actually land in the model
 *       (write → read back; field-specific properties use {@code field modify}, common metadata uses
 *       {@code meta} (G45), and the original requiredness/annotations
 *       drop the probe's round-trip work surfaced);</li>
 *   <li><b>rejected</b> — a recognized key carrying a <em>wrong-shaped</em> value must be refused, NOT silently
 *       coerced to a default/empty and falsely reported {@code applied}. The schema-driven {@code SpecKeys}
 *       gate now validates key <em>values</em> as well as <em>names</em> (Follow-on B), so a wrong shape is a
 *       structured {@code RK_INVALID_VALUE} — not a plain {@code fail} — end-to-end for field properties.
 *       A retired common-metadata key instead gets {@code RK_UNKNOWN_ARG} with a relocation to {@code meta}.</li>
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
                  "helperText":{"en_US":"MARK_HELPER"}, "global":true, "transient":true }""");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", spec.toString());
        assertEquals(0, res.code(), res::err);
        var meta = CliTestSupport.run("-m", m.toString(), "meta", "/R/F",
                "--label", "en_US=MARK_LABEL", "--internal-description", "en_US=MARK_INT",
                "--external-description", "en_US=MARK_EXT", "--annotation", "mark_anno=MARK_ANNO");
        assertEquals(0, meta.code(), meta::err);

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
        assertTrue(res.out().contains("RK_INVALID_VALUE"),
                () -> "rejected with the structured value-shape code, not a plain fail: " + res.out());
    }

    @Test
    void retiredAnnotationsKeyPointsToMeta(@TempDir Path dir) throws Exception {
        Path m = modelWithField(dir);
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{ \"group\":\"/R\", \"name\":\"F\", \"annotations\":[{\"name\":\"a\",\"value\":\"v\"}] }");
        var res = CliTestSupport.run("-m", m.toString(), "field", "modify", bad.toString());
        assertNotEquals(0, res.code(), () -> "retired annotations must be rejected: " + res.out());
        assertTrue(res.out().contains("RK_UNKNOWN_ARG") && res.out().contains("dmtool meta <ref> --annotation"),
                () -> "the retired key points to the canonical route: " + res.out());
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
        assertTrue(res.out().contains("RK_INVALID_VALUE"),
                () -> "rejected with the structured value-shape code, not a plain fail: " + res.out());
    }
}
